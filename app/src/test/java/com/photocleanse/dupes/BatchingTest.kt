package com.photocleanse.dupes

import com.photocleanse.data.DupRow
import com.photocleanse.data.Photo
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BatchingTest {
    @get:Rule val tmp = TemporaryFolder()

    private var nextId = 1L
    private fun row(group: Long, hidden: Boolean = false, kind: Int = 1) = DupRow(
        groupId = group, kind = kind, score = 1f, isBest = false, width = 100, height = 100, sharpness = 1f,
        hasCameraExif = true, photo = Photo(id = nextId++, path = "/p$nextId.jpg", modified = 0, size = 1, hidden = hidden),
    )

    private fun group(id: Long, n: Int, hidden: Boolean = false) = (1..n).map { row(id, hidden && it == n) }

    @Test fun `whole groups are packed until the batch is full`() {
        val rows = group(1, 10) + group(2, 12) + group(3, 9) + group(4, 2)
        val b = buildDupBatch(rows, true, 30)
        assertEquals(listOf(1L, 2L), b.map { it.groupId }) // 10+12=22, +9 would exceed 30
        assertTrue(b.sumOf { it.rows.size } <= 30)
    }

    @Test fun `first group is always taken even when larger than the batch size`() {
        val b = buildDupBatch(group(1, 45) + group(2, 3), true, 30)
        assertEquals(listOf(1L), b.map { it.groupId })
        assertEquals(45, b[0].rows.size)
    }

    @Test fun `batch limit is honoured at the minimum and maximum setting`() {
        val rows = (1L..30L).flatMap { group(it, 4) } // 30 groups of 4
        assertEquals(2, buildDupBatch(rows, true, 10).size)   // 4+4 = 8, next would be 12
        assertEquals(25, buildDupBatch(rows, true, 100).size) // 25 * 4 = 100
        assertTrue(buildDupBatch(rows, true, 100).sumOf { it.rows.size } <= 100)
    }

    @Test fun `a group bigger than the minimum batch is still shown whole`() {
        val b = buildDupBatch(group(1, 14) + group(2, 2), true, 10)
        assertEquals(1, b.size)
        assertEquals(14, b[0].rows.size)
    }

    @Test fun `groups with a hidden-folder photo are skipped completely unless hidden is included`() {
        val rows = group(1, 3, hidden = true) + group(2, 3)
        assertEquals(listOf(2L), buildDupBatch(rows, false).map { it.groupId })
        assertEquals(listOf(1L, 2L), buildDupBatch(rows, true).map { it.groupId })
    }

    @Test fun `groups with fewer than two photos are skipped`() {
        assertTrue(buildDupBatch(group(1, 1), true).isEmpty())
    }

    @Test fun `empty input and kind mapping`() {
        assertTrue(buildDupBatch(emptyList(), true).isEmpty())
        assertEquals(DupKind.SIMILAR, buildDupBatch(listOf(row(1, kind = 2), row(1, kind = 2)), true)[0].kind)
    }

    @Test fun `row order within a group is preserved`() {
        val rows = group(1, 4)
        assertEquals(rows.map { it.photo.id }, buildDupBatch(rows, true)[0].rows.map { it.photo.id })
    }

    @Test fun `fullyDeletedGroups flags groups where no copy survives`() {
        val groupOf = mapOf(1L to 10L, 2L to 10L, 3L to 20L, 4L to 20L)
        assertEquals(listOf(10L), fullyDeletedGroups(groupOf, setOf(1L, 2L, 3L)))
        assertTrue(fullyDeletedGroups(groupOf, setOf(1L, 3L)).isEmpty())
        assertTrue(fullyDeletedGroups(groupOf, emptySet()).isEmpty())
        assertEquals(setOf(10L, 20L), fullyDeletedGroups(groupOf, setOf(1L, 2L, 3L, 4L)).toSet())
    }

    // ---- filesIdentical ----

    private fun f(name: String, bytes: ByteArray) = tmp.newFile(name).also { it.writeBytes(bytes) }

    @Test fun `identical files are detected, including multi-buffer files`() {
        val data = ByteArray(200_000) { (it * 31).toByte() }
        assertTrue(filesIdentical(f("a", data).path, f("b", data.copyOf()).path))
    }

    @Test fun `single differing byte deep in the file is detected`() {
        val data = ByteArray(200_000) { (it * 31).toByte() }
        val other = data.copyOf().also { it[150_000] = (it[150_000] + 1).toByte() }
        assertFalse(filesIdentical(f("a", data).path, f("b", other).path))
    }

    @Test fun `different lengths, empty and missing files`() {
        assertFalse(filesIdentical(f("a", ByteArray(10)).path, f("b", ByteArray(11)).path))
        assertTrue(filesIdentical(f("e1", ByteArray(0)).path, f("e2", ByteArray(0)).path))
        assertFalse(filesIdentical(f("c", ByteArray(5)).path, File(tmp.root, "missing").path))
    }

    @Test fun `file size exactly one buffer`() {
        val data = ByteArray(64 * 1024) { 7 }
        assertTrue(filesIdentical(f("a", data).path, f("b", data.copyOf()).path))
        assertFalse(filesIdentical(f("c", data).path, f("d", data.copyOf().also { it[65535] = 8 }).path))
    }
}
