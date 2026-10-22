package com.photocleanse.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class PhotoDaoTest {
    private lateinit var db: AppDatabase
    private lateinit var dao: PhotoDao

    @Before fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).allowMainThreadQueries().build()
        dao = db.dao()
    }

    @After fun tearDown() = db.close()

    private fun p(path: String, modified: Long = 0, hidden: Boolean = false, status: Int = STATUS_NEW) =
        Photo(path = path, modified = modified, size = 1, hidden = hidden, status = status)

    @Test fun reinsertingKnownPathKeepsDoneStatus() = runBlocking {
        dao.insertAll(listOf(p("/a")))
        dao.markDone(dao.idPaths().map { it.id })
        dao.insertAll(listOf(p("/a"))) // rescan sees the same file
        assertEquals(1, dao.counts().first().total)
        assertEquals(1, dao.counts().first().done)
        assertEquals(0, dao.nextBatch(1, 30).size)
    }

    @Test fun nextBatchExcludesHiddenUnlessIncluded() = runBlocking {
        dao.insertAll(listOf(p("/a"), p("/.h/b", hidden = true)))
        assertEquals(listOf("/a"), dao.nextBatch(0, 30).map { it.path })
        assertEquals(2, dao.nextBatch(1, 30).size)
    }

    @Test fun nextBatchOnlyReturnsNewAndRespectsLimitAndNewestFirst() = runBlocking {
        dao.insertAll((1..50).map { p("/p$it", modified = it.toLong()) } + p("/done", modified = 999, status = STATUS_DONE))
        val batch = dao.nextBatch(1, 30)
        assertEquals(30, batch.size)
        assertEquals("/p50", batch.first().path)
        assertEquals(false, batch.any { it.path == "/done" })
    }

    @Test fun countsAreCorrect() = runBlocking {
        dao.insertAll(listOf(p("/a"), p("/.b", hidden = true), p("/c", status = STATUS_DONE)))
        val c = dao.counts().first()
        assertEquals(Counts(total = 3, done = 1, remaining = 2, hiddenRemaining = 1), c)
    }

    @Test fun emptyCounts() = runBlocking {
        assertEquals(Counts(0, 0, 0, 0), dao.counts().first())
    }

    @Test fun markDoneOnlyAffectsGivenIds() = runBlocking {
        dao.insertAll(listOf(p("/a"), p("/b")))
        val a = dao.idPaths().first { it.path == "/a" }.id
        dao.markDone(listOf(a))
        assertEquals(listOf("/b"), dao.nextBatch(1, 30).map { it.path })
    }

    @Test fun resetBringsDonePhotosBack() = runBlocking {
        dao.insertAll(listOf(p("/a", status = STATUS_DONE)))
        dao.resetStatuses()
        assertEquals(1, dao.nextBatch(1, 30).size)
    }

    @Test fun deleteByIdsRemovesRows() = runBlocking {
        dao.insertAll(listOf(p("/a"), p("/b")))
        dao.deleteByIds(listOf(dao.idPaths().first { it.path == "/a" }.id))
        assertEquals(listOf("/b"), dao.idPaths().map { it.path })
    }
}

