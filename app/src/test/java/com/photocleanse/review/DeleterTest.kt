package com.photocleanse.review

import com.photocleanse.data.Photo
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DeleterTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun photo(id: Long, path: String) = Photo(id = id, path = path, modified = 0, size = 1, hidden = false)

    /** In-memory fake that can simulate refusal or exceptions per path. */
    private class FakeOps(
        files: Set<String>,
        val refuse: Set<String> = emptySet(),
        val throwOn: Set<String> = emptySet(),
    ) : FileOps {
        val present = files.toMutableSet()
        val deleteCalls = mutableListOf<String>()
        override fun exists(path: String) = path in present
        override fun delete(path: String): Boolean {
            deleteCalls += path
            if (path in throwOn) throw SecurityException("denied")
            if (path in refuse) return false
            return present.remove(path)
        }
    }

    @Test fun `deletes only the listed files`() {
        val ops = FakeOps(setOf("/a", "/b", "/c"))
        val out = deletePhotos(listOf(photo(1, "/a"), photo(3, "/c")), ops)
        assertEquals(listOf(1L, 3L), out.deleted.map { it.id })
        assertTrue(out.failed.isEmpty())
        assertEquals(setOf("/b"), ops.present)
    }

    @Test fun `already missing file counts as deleted without delete call`() {
        val ops = FakeOps(emptySet())
        val out = deletePhotos(listOf(photo(1, "/gone")), ops)
        assertEquals(1, out.deleted.size)
        assertTrue(ops.deleteCalls.isEmpty())
    }

    @Test fun `refused delete is reported as failed and others continue`() {
        val ops = FakeOps(setOf("/a", "/b"), refuse = setOf("/a"))
        val out = deletePhotos(listOf(photo(1, "/a"), photo(2, "/b")), ops)
        assertEquals(listOf(1L), out.failed.map { it.id })
        assertEquals(listOf(2L), out.deleted.map { it.id })
    }

    @Test fun `exception is reported as failed and others continue`() {
        val ops = FakeOps(setOf("/a", "/b"), throwOn = setOf("/a"))
        val out = deletePhotos(listOf(photo(1, "/a"), photo(2, "/b")), ops)
        assertEquals(listOf(1L), out.failed.map { it.id })
        assertEquals(listOf(2L), out.deleted.map { it.id })
    }

    @Test fun `empty list does nothing`() {
        val ops = FakeOps(setOf("/a"))
        val out = deletePhotos(emptyList(), ops)
        assertTrue(out.deleted.isEmpty() && out.failed.isEmpty() && ops.deleteCalls.isEmpty())
    }

    @Test fun `real file ops delete real files and leave siblings alone`() {
        val a = tmp.newFile("a.jpg"); val b = tmp.newFile("b.jpg")
        val out = deletePhotos(listOf(photo(1, a.path)))
        assertEquals(1, out.deleted.size)
        assertFalse(a.exists())
        assertTrue(b.exists())
    }

    @Test fun `real file ops fail on non-empty directory path instead of removing contents`() {
        val dir = tmp.newFolder("d"); File(dir, "x.jpg").writeText("x")
        val out = deletePhotos(listOf(photo(1, dir.path)))
        assertEquals(1, out.failed.size)
        assertTrue(File(dir, "x.jpg").exists())
    }
}
