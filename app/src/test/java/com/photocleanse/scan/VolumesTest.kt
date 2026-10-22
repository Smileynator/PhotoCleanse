package com.photocleanse.scan

import com.photocleanse.data.IdPath
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VolumesTest {
    private fun roots(vararg p: String) = Volumes.dedupeRoots(p.map { File(it) }).map { it.path.replace('\\', '/') }

    @Test fun `volume root is derived from the app-specific directory`() {
        assertEquals("/storage/ABCD-1234", Volumes.volumeRootFromAppDir("/storage/ABCD-1234/Android/data/com.photocleanse/files"))
        assertEquals("/storage/emulated/0", Volumes.volumeRootFromAppDir("/storage/emulated/0/Android/data/com.x/files"))
        assertNull(Volumes.volumeRootFromAppDir("/storage/ABCD-1234/something/else"))
        assertNull(Volumes.volumeRootFromAppDir("/Android/data/x"))
    }

    @Test fun `distinct volumes are kept in order`() {
        assertEquals(listOf("/storage/emulated/0", "/storage/ABCD-1234", "/storage/USB"), roots("/storage/emulated/0", "/storage/ABCD-1234", "/storage/USB"))
    }

    @Test fun `duplicates and nested roots are dropped`() {
        assertEquals(listOf("/storage/emulated/0"), roots("/storage/emulated/0", "/storage/emulated/0", "/storage/emulated/0/Pictures"))
        assertEquals(listOf("/storage/emulated/0"), roots("/storage/emulated/0/Pictures", "/storage/emulated/0"))
    }

    @Test fun `similar prefixes are not mistaken for nesting`() {
        assertEquals(listOf("/storage/sd", "/storage/sd2"), roots("/storage/sd", "/storage/sd2"))
    }

    @Test fun `mounted states`() {
        assertTrue(Volumes.isReadableState("mounted"))
        assertTrue(Volumes.isReadableState("mounted_ro"))
        assertFalse(Volumes.isReadableState("unmounted"))
        assertFalse(Volumes.isReadableState("removed"))
        assertFalse(Volumes.isReadableState("ejecting"))
    }

    @Test fun `isUnder is strict and separator agnostic`() {
        assertTrue(isUnder("/a/b/c.jpg", "/a/b"))
        assertTrue(isUnder("/a/b/c.jpg", "/a/b/"))
        assertTrue(isUnder("C:\\a\\b\\c.jpg", "C:\\a\\b"))
        assertFalse(isUnder("/a/bc/c.jpg", "/a/b"))
        assertFalse(isUnder("/a/b", "/a/b"))
    }

    @Test fun `staleIds only considers rows of scanned volumes`() {
        val rows = listOf(IdPath(1, "/in/a.jpg"), IdPath(2, "/in/b.jpg"), IdPath(3, "/sd/c.jpg"), IdPath(4, "/other/d.jpg"))
        assertEquals(listOf(2L), staleIds(rows, setOf("/in/a.jpg"), listOf("/in")))
        assertEquals(listOf(2L, 3L), staleIds(rows, setOf("/in/a.jpg"), listOf("/in", "/sd")))
        assertTrue(staleIds(rows, emptySet(), emptyList()).isEmpty())
    }
}
