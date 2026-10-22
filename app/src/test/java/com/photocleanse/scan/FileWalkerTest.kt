package com.photocleanse.scan

import com.photocleanse.data.IdPath
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FileWalkerTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun file(rel: String, content: String = "x"): File {
        val f = File(tmp.root, rel)
        f.parentFile!!.mkdirs()
        f.writeText(content)
        return f
    }

    private fun walk(skip: (File) -> Boolean = { false }) =
        FileWalker.walk(tmp.root, skip).associateBy { File(it.path).relativeTo(tmp.root).invariantSeparatorsPath }

    @Test fun `finds all image types case-insensitively`() {
        listOf("a.jpg", "b.JPEG", "c.png", "d.GIF", "e.webp", "f.heic", "g.bmp", "h.avif", "i.tiff").forEach { file(it) }
        assertEquals(9, walk().size)
    }

    @Test fun `ignores non images and extensionless or dot-only names`() {
        listOf("notes.txt", "video.mp4", "jpg", "weird.", "data.json", ".nomedia").forEach { file(it) }
        assertTrue(walk().isEmpty())
    }

    @Test fun `finds nested images`() {
        file("DCIM/Camera/2024/a.jpg")
        assertTrue(walk().containsKey("DCIM/Camera/2024/a.jpg"))
    }

    @Test fun `images in hidden folders are included and flagged hidden`() {
        file(".secret/a.jpg")
        file("pics/.private/deep/b.png")
        file("pics/c.png")
        val r = walk()
        assertEquals(3, r.size)
        assertTrue(r[".secret/a.jpg"]!!.hidden)
        assertTrue(r["pics/.private/deep/b.png"]!!.hidden)
        assertFalse(r["pics/c.png"]!!.hidden)
    }

    @Test fun `hidden file in normal folder is flagged hidden`() {
        file("pics/.hidden.jpg")
        assertTrue(walk()["pics/.hidden.jpg"]!!.hidden)
    }

    @Test fun `root itself is never considered hidden`() {
        val root = tmp.newFolder(".rootdir")
        File(root, "a.jpg").writeText("x")
        val r = FileWalker.walk(root).toList()
        assertEquals(1, r.size)
        assertFalse(r[0].hidden)
    }

    @Test fun `skipDir prunes whole subtrees`() {
        file("Android/data/app/a.jpg")
        file("ok/b.jpg")
        val r = walk { it.name == "data" }
        assertEquals(setOf("ok/b.jpg"), r.keys)
    }

    private fun scanWalk() = FileWalker.walk(tmp.root, { shouldSkipDir(it, tmp.root) })
        .map { File(it.path).relativeTo(tmp.root).invariantSeparatorsPath }.toSet()

    @Test fun `thumbnail folders are excluded wherever they are, other hidden folders are not`() {
        file(".thumbnails/a.jpg")
        file("DCIM/.thumbnails/b.jpg")
        file("DCIM/.thumbnails/deeper/c.jpg")
        file("Pictures/.THUMBNAILS/d.jpg")
        file("DCIM/.thumbs_other/e.jpg")
        file(".secret/f.jpg")
        file("DCIM/Camera/g.jpg")
        assertEquals(setOf("DCIM/.thumbs_other/e.jpg", ".secret/f.jpg", "DCIM/Camera/g.jpg"), scanWalk())
    }

    @Test fun `a normal folder merely containing the word thumbnails is still scanned`() {
        file("thumbnails/a.jpg")
        file("My .thumbnails backup/b.jpg")
        assertEquals(setOf("thumbnails/a.jpg", "My .thumbnails backup/b.jpg"), scanWalk())
    }

    @Test fun `Android data and obb are skipped only directly under root`() {
        file("Android/data/x/a.jpg"); file("Android/obb/b.jpg"); file("Android/media/c.jpg"); file("Other/Android/data/d.jpg")
        assertEquals(setOf("Android/media/c.jpg", "Other/Android/data/d.jpg"), scanWalk())
    }

    @Test fun `records size and modified time`() {
        val f = file("a.jpg", "12345")
        f.setLastModified(1_700_000_000_000L)
        val img = walk()["a.jpg"]!!
        assertEquals(5L, img.size)
        assertEquals(1_700_000_000_000L, img.modified)
    }

    @Test fun `missing root yields nothing and does not throw`() {
        assertTrue(FileWalker.walk(File(tmp.root, "nope")).toList().isEmpty())
    }

    @Test fun `onEntry is called for every entry`() {
        file("a.jpg"); file("d/b.txt")
        var n = 0
        FileWalker.walk(tmp.root, onEntry = { n++ }).toList()
        assertEquals(3, n) // a.jpg, d, d/b.txt
    }

    @Test fun `exception from onEntry aborts the walk (cancellation)`() {
        file("a.jpg"); file("b.jpg")
        var thrown = false
        try {
            FileWalker.walk(tmp.root, onEntry = { throw IllegalStateException("cancel") }).toList()
        } catch (e: IllegalStateException) { thrown = true }
        assertTrue(thrown)
    }

    @Test fun `staleIds returns only rows missing from seen`() {
        val rows = listOf(IdPath(1, "/r/a"), IdPath(2, "/r/b"), IdPath(3, "/r/c"))
        assertEquals(listOf(2L), staleIds(rows, setOf("/r/a", "/r/c"), listOf("/r")))
        assertTrue(staleIds(rows, setOf("/r/a", "/r/b", "/r/c"), listOf("/r")).isEmpty())
    }

    @Test fun `isImageFile edge cases`() {
        assertTrue(isImageFile("a.b.PNG"))
        assertFalse(isImageFile("png"))
        assertFalse(isImageFile(".png.txt"))
        assertFalse(isImageFile(""))
    }
}
