package com.photocleanse.dupes

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.photocleanse.data.AppDatabase
import com.photocleanse.data.Photo
import java.io.File
import java.util.Random
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** End to end: real JPEG files on disk -> BitmapFactory decode -> fingerprints -> groups in the database. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AnalyzerTest {
    private lateinit var db: AppDatabase
    private lateinit var dir: File

    @Before fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).allowMainThreadQueries().build()
        dir = File(ctx.cacheDir, "analyzer-test").also { it.deleteRecursively(); it.mkdirs() }
    }

    @After fun tearDown() { db.close(); dir.deleteRecursively() }

    private fun scene(seed: Long, w: Int, h: Int): Bitmap {
        val rnd = Random(seed)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(android.graphics.Color.rgb(rnd.nextInt(256), rnd.nextInt(256), rnd.nextInt(256)))
        val p = Paint()
        repeat(12) {
            p.color = android.graphics.Color.rgb(rnd.nextInt(256), rnd.nextInt(256), rnd.nextInt(256))
            val sw = w / 5 + rnd.nextInt(w / 3); val sh = h / 5 + rnd.nextInt(h / 3)
            val x = rnd.nextInt(w - sw).toFloat(); val y = rnd.nextInt(h - sh).toFloat()
            if (rnd.nextBoolean()) c.drawOval(x, y, x + sw, y + sh, p) else c.drawRect(x, y, x + sw, y + sh, p)
        }
        return bmp
    }

    private fun save(bmp: Bitmap, name: String, quality: Int): File {
        val f = File(dir, name)
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, quality, it) }
        f.setLastModified(1_700_000_000_000L)
        return f
    }

    private suspend fun add(vararg files: File) {
        db.dao().insertAll(files.map { Photo(path = it.path, modified = it.lastModified(), size = it.length(), hidden = false) })
    }

    @Test fun `original and a smaller recompressed copy end up in one group, unrelated photo does not`() = runBlocking {
        val original = scene(7, 1600, 1200)
        val a = save(original, "IMG_0001.jpg", 95)
        val b = save(Bitmap.createScaledBitmap(original, 640, 480, true), "IMG-20240101-WA0001.jpg", 60)
        val other = save(scene(99, 1600, 1200), "IMG_0002.jpg", 95)
        add(a, b, other)

        Analyzer(db.dao()).run()

        val rows = db.dao().dupRows()
        assertEquals(2, rows.size)
        assertEquals(setOf(a.path, b.path), rows.map { it.photo.path }.toSet())
        assertEquals(a.path, rows.first().photo.path) // the large original is the best copy
        assertTrue(rows.first().isBest)
        assertEquals(1600, rows.first().width)
        assertEquals(1, rows.map { it.groupId }.toSet().size)
    }

    @Test fun `unreadable file is recorded as invalid and does not break the run`() = runBlocking {
        val bad = File(dir, "broken.jpg").also { it.writeText("not an image") }
        val good1 = save(scene(1, 800, 600), "a.jpg", 90)
        val good2 = save(scene(1, 800, 600), "b.jpg", 85)
        add(bad, good1, good2)

        val analyzer = Analyzer(db.dao())
        analyzer.run()

        assertEquals(0, db.dao().needingFeatures().size) // broken file is not retried forever
        assertEquals(2, db.dao().candidates().size)
        assertEquals(2, db.dao().dupRows().size)
        assertEquals(false, analyzer.state.value.running)
    }

    @Test fun `second run does no work and keeps groups`() = runBlocking {
        val x = scene(5, 800, 600)
        add(save(x, "a.jpg", 90), save(x, "b.jpg", 80))
        val analyzer = Analyzer(db.dao())
        analyzer.run()
        analyzer.run()
        assertEquals(0, analyzer.state.value.total)
        assertEquals(2, db.dao().dupRows().size)
    }

    @Test fun `identical files are grouped as EXACT`() = runBlocking {
        val x = scene(3, 800, 600)
        val a = save(x, "a.jpg", 90)
        val b = File(dir, "copy.jpg").also { a.copyTo(it); it.setLastModified(1_700_000_000_000L) }
        add(a, b)
        Analyzer(db.dao()).run()
        assertEquals(DupKind.EXACT.ordinal, db.dao().dupRows().first().kind)
    }
}
