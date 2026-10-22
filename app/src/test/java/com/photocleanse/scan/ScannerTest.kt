package com.photocleanse.scan

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.photocleanse.data.AppDatabase
import com.photocleanse.data.Photo
import com.photocleanse.data.STATUS_DONE
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ScannerTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var db: AppDatabase

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
    }

    @After fun tearDown() = db.close()

    private val internal get() = File(tmp.root, "internal").also { it.mkdirs() }
    private val sdcard get() = File(tmp.root, "sdcard").also { it.mkdirs() }

    private fun file(root: File, rel: String) = File(root, rel).also { it.parentFile!!.mkdirs(); it.writeText("x") }
    private fun scanner(vararg roots: File) = Scanner(db.dao()) { roots.toList() }
    private fun paths() = runBlocking { db.dao().idPaths().map { it.path }.toSet() }
    private fun p(f: File, status: Int = 0) = Photo(path = f.path, modified = 1, size = 1, hidden = false, status = status)

    @Test fun `scan indexes photos, flags hidden folders and skips thumbnail caches`() = runBlocking {
        file(internal, "DCIM/a.jpg"); file(internal, ".secret/b.png")
        file(internal, "DCIM/.thumbnails/c.jpg"); file(internal, ".thumbnails/d.jpg")
        scanner(internal).scan()
        val rows = db.dao().nextBatch(1, 100)
        assertEquals(setOf("DCIM/a.jpg", ".secret/b.png"), rows.map { File(it.path).relativeTo(internal).invariantSeparatorsPath }.toSet())
        assertEquals(1, db.dao().nextBatch(0, 100).size)
    }

    @Test fun `thumbnail rows indexed by an earlier version are pruned, real photos keep their done status`() = runBlocking {
        val keep = file(internal, "DCIM/keep.jpg")
        val thumb = file(internal, "DCIM/.thumbnails/old.jpg")
        db.dao().insertAll(listOf(p(keep, STATUS_DONE), p(thumb).copy(hidden = true)))
        scanner(internal).scan()
        assertEquals(setOf(keep.path), paths())
        assertEquals(1, db.dao().counts().first().done)
    }

    @Test fun `unreadable root never wipes the index`() = runBlocking {
        val gone = File(tmp.root, "does-not-exist")
        db.dao().insertAll(listOf(Photo(path = File(gone, "a.jpg").path, modified = 1, size = 1, hidden = false, status = STATUS_DONE)))
        scanner(gone).scan()
        assertEquals(1, db.dao().idPaths().size)
    }

    @Test fun `photos deleted outside the app disappear from the index`() = runBlocking {
        val a = file(internal, "a.jpg"); file(internal, "b.jpg")
        val s = scanner(internal)
        s.scan()
        a.delete()
        s.scan()
        assertEquals(listOf("b.jpg"), db.dao().idPaths().map { File(it.path).name })
    }

    // ---- multiple volumes ----

    @Test fun `all volumes are indexed and hidden is relative to each volume root`() = runBlocking {
        file(internal, "DCIM/a.jpg"); file(sdcard, "DCIM/b.jpg"); file(sdcard, ".private/c.jpg"); file(sdcard, "DCIM/.thumbnails/t.jpg")
        scanner(internal, sdcard).scan()
        assertEquals(3, db.dao().idPaths().size)
        assertEquals(2, db.dao().nextBatch(0, 100).size) // c.jpg is hidden
    }

    @Test fun `an absent volume keeps its index rows and done marks untouched`() = runBlocking {
        val onCard = file(sdcard, "DCIM/card.jpg")
        file(internal, "a.jpg")
        scanner(internal, sdcard).scan()
        db.dao().markDone(db.dao().idPaths().filter { it.path == onCard.path }.map { it.id })

        // card removed: only internal storage is mounted now (its folder also gets deleted to be sure)
        sdcard.deleteRecursively()
        scanner(internal).scan()

        assertEquals(setOf(File(internal, "a.jpg").path, onCard.path), paths())
        assertEquals(1, db.dao().counts().first().done)

        // card re-inserted with the same photo: still done, not re-queued
        file(sdcard, "DCIM/card.jpg")
        scanner(internal, sdcard).scan()
        assertEquals(1, db.dao().counts().first().done)
        assertEquals(2, db.dao().counts().first().total)
    }

    @Test fun `a present but unreadable volume is not pruned`() = runBlocking {
        val onCard = file(sdcard, "DCIM/card.jpg")
        scanner(internal, sdcard).scan() // internal is empty, card has one photo
        sdcard.deleteRecursively(); sdcard.mkdirs() // mounted but nothing readable inside (empty listing)
        scanner(internal, sdcard).scan()
        assertEquals(setOf(onCard.path), paths())
    }

    @Test fun `vanished files are pruned on a present volume without touching the other volume`() = runBlocking {
        val a = file(sdcard, "a.jpg"); file(sdcard, "b.jpg"); val i = file(internal, "i.jpg")
        scanner(internal, sdcard).scan()
        a.delete()
        scanner(internal, sdcard).scan()
        assertEquals(setOf(File(sdcard, "b.jpg").path, i.path), paths())
    }

    @Test fun `rows outside every known volume are never pruned`() = runBlocking {
        db.dao().insertAll(listOf(Photo(path = "/legacy/odd/path.jpg", modified = 1, size = 1, hidden = false, status = STATUS_DONE)))
        file(internal, "a.jpg")
        scanner(internal, sdcard).scan()
        assertEquals(2, db.dao().idPaths().size)
    }

    @Test fun `same file name on two volumes are two photos`() = runBlocking {
        file(internal, "IMG_1.jpg"); file(sdcard, "IMG_1.jpg")
        scanner(internal, sdcard).scan()
        assertEquals(2, db.dao().idPaths().size)
    }

    @Test fun `no volumes at all changes nothing`() = runBlocking {
        db.dao().insertAll(listOf(p(file(internal, "a.jpg"), STATUS_DONE)))
        scanner().scan()
        assertEquals(1, db.dao().counts().first().done)
    }
}
