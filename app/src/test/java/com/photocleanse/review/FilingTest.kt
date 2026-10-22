package com.photocleanse.review

import com.photocleanse.data.Photo
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FilingTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun photo(id: Long, path: String) = Photo(id = id, path = path, modified = 0, size = 1, hidden = false)

    @Test fun `uniqueName appends -1 -2 before the extension`() {
        assertEquals("a.jpg", uniqueName("a.jpg") { false })
        assertEquals("a-1.jpg", uniqueName("a.jpg") { it == "a.jpg" })
        assertEquals("a-2.jpg", uniqueName("a.jpg") { it in setOf("a.jpg", "a-1.jpg") })
        assertEquals("noext-1", uniqueName("noext") { it == "noext" })
        assertEquals(".hidden-1", uniqueName(".hidden") { it == ".hidden" })
    }

    @Test fun `session moves on to the next unassigned photo and finishes`() {
        val s = FilingSession(listOf(photo(1, "/a"), photo(2, "/b")))
        s.assign("/dest/x")
        assertEquals(1, s.index)
        s.assign("/dest/y")
        assertTrue(s.finished)
        assertEquals(listOf("/dest/x", "/dest/y"), s.plan().map { it.destDir })
    }

    @Test fun `undo forgets the previous assignment and skipped photos are not planned`() {
        val s = FilingSession(listOf(photo(1, "/a"), photo(2, "/b"), photo(3, "/c")))
        s.assign("/x")
        s.undo()
        assertEquals(0, s.index)
        assertTrue(s.destinationsSnapshot().isEmpty())
        s.assign("/x"); s.skipRest()
        assertEquals(listOf(1L), s.plan().map { it.photo.id })
    }

    @Test fun `undo prefers the selected filed photo, else the latest one`() {
        val s = FilingSession(listOf(photo(1, "/a"), photo(2, "/b"), photo(3, "/c")))
        s.assign("/x"); s.assign("/y") // 1, 2 filed; on 3
        s.goTo(0); s.undo() // viewing filed photo 1
        assertEquals(0, s.index)
        assertEquals(setOf(2L), s.destinationsSnapshot().keys)
        s.goTo(2); s.undo() // viewing unfiled 3 -> latest is 2
        assertEquals(1, s.index)
        assertTrue(s.destinationsSnapshot().isEmpty())
    }

    @Test fun `listFolders shows dot folders only when asked`() {
        val root = tmp.newFolder("r2")
        File(root, ".h/.deep").mkdirs(); File(root, "v").mkdirs()
        assertEquals(listOf("v"), listFolders(root).map { it.name })
        val all = listFolders(root, showHidden = true)
        assertEquals(listOf(".h", "v"), all.map { it.name })
        assertTrue(all.first().hasChildren)
        assertFalse(listFolders(root, showHidden = true, checkChildren = false).first().hasChildren)
    }

    @Test fun `reassigning replaces the destination`() {
        val s = FilingSession(listOf(photo(1, "/a"), photo(2, "/b")))
        s.assign("/x"); s.goTo(0); s.assign("/y")
        assertEquals("/y", s.destinationsSnapshot()[1L])
    }

    @Test fun `movePhotos moves files and renames clashes including inside one batch`() {
        val src = tmp.newFolder("src"); val dest = tmp.newFolder("dest")
        File(dest, "a.jpg").writeText("existing")
        val a1 = File(src, "a.jpg").apply { writeText("one") }
        val sub = tmp.newFolder("src2"); val a2 = File(sub, "a.jpg").apply { writeText("two") }
        val out = movePhotos(listOf(Move(photo(1, a1.path), dest.path), Move(photo(2, a2.path), dest.path)))
        assertEquals(2, out.moved.size)
        assertEquals(listOf("a-1.jpg", "a-2.jpg"), out.moved.map { File(it.newPath).name })
        assertEquals("existing", File(dest, "a.jpg").readText())
        assertEquals("one", File(dest, "a-1.jpg").readText())
        assertFalse(a1.exists()); assertFalse(a2.exists())
    }

    @Test fun `missing source is reported as failed and others continue`() {
        val dest = tmp.newFolder("dest")
        val ok = File(tmp.newFolder("s"), "b.jpg").apply { writeText("x") }
        val out = movePhotos(listOf(Move(photo(1, "/nope/a.jpg"), dest.path), Move(photo(2, ok.path), dest.path)))
        assertEquals(listOf(1L), out.failed.map { it.id })
        assertEquals(listOf(2L), out.moved.map { it.photo.id })
    }

    @Test fun `listFolders hides dot folders sorts and flags children`() {
        val root = tmp.newFolder("root")
        File(root, "b/inner").mkdirs(); File(root, "A").mkdirs(); File(root, ".hid").mkdirs(); File(root, "f.txt").writeText("")
        val l = listFolders(root)
        assertEquals(listOf("A", "b"), l.map { it.name })
        assertEquals(listOf(false, true), l.map { it.hasChildren })
    }

    @Test fun `folder name validation`() {
        assertTrue(validFolderName("Trips 2024"))
        assertFalse(validFolderName("  ")); assertFalse(validFolderName("a/b")); assertFalse(validFolderName(".."))
    }
}

class FolderHueTest {
    @Test fun `hue is stable, in range, case-insensitive and varies by name`() {
        val names = listOf("Family", "Holidays", "Pets", "Pokemon Cards", "Pokemon Binder", "Memes", "Work")
        names.forEach { assertTrue(folderHue(it) in 0..359) }
        assertEquals(folderHue("Family"), folderHue(" family "))
        assertTrue(names.map { folderHue(it) }.toSet().size >= 6)
        assertEquals(folderHue("Family"), folderHue("Family")) // pure
    }
}

class FolderTreeTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun `scan is read once and browsing needs no disk`() {
        val root = tmp.newFolder("p")
        File(root, "Trips/Rome").mkdirs(); File(root, ".secret/x").mkdirs(); File(root, "Pets").mkdirs()
        val t = FolderTree.scan(root.path)
        File(root, "Trips").deleteRecursively() // gone from disk; the cache must not care
        assertEquals(listOf("Pets", "Trips"), t.children(root.path, false).map { it.name })
        assertEquals(listOf(false, true), t.children(root.path, false).map { it.hasChildren })
        assertEquals(listOf(".secret", "Pets", "Trips"), t.children(root.path, true).map { it.name })
        assertEquals(listOf("Rome"), t.children(File(root, "Trips").path, false).map { it.name })
    }

    @Test fun `hasChildren ignores hidden-only children unless shown`() {
        val root = tmp.newFolder("p2")
        File(root, "A/.h").mkdirs()
        val t = FolderTree.scan(root.path)
        assertFalse(t.children(root.path, false).single().hasChildren)
        assertTrue(t.children(root.path, true).single().hasChildren)
    }

    @Test fun `add keeps order and makes the new folder browsable`() {
        val root = tmp.newFolder("p3")
        File(root, "b").mkdirs()
        val t = FolderTree.scan(root.path)
        t.add(root.path, "a"); t.add(root.path, "a")
        assertEquals(listOf("a", "b"), t.children(root.path, false).map { it.name })
        t.add(File(root, "a").path, "sub")
        assertTrue(t.children(root.path, false).first().hasChildren)
    }
}
