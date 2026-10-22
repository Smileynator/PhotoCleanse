package com.photocleanse.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class DupDaoTest {
    private lateinit var db: AppDatabase
    private lateinit var dao: PhotoDao

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        dao = db.dao()
    }

    @After fun tearDown() = db.close()

    private suspend fun addPhotos(vararg specs: Triple<String, Boolean, Int>): Map<String, Long> {
        dao.insertAll(specs.map { Photo(path = it.first, modified = 1, size = 10, hidden = it.second, status = it.third) })
        return dao.idPaths().associate { it.path to it.id }
    }

    private fun feat(id: Long, valid: Boolean = true, size: Long = 10, modified: Long = 1) =
        PhotoFeatures(id, valid, 1L, 2L, 100, 100, 0, 5f, true, size, modified)

    private suspend fun group(id: Long, kind: Int, vararg members: Long) {
        dao.insertGroups(listOf(DupGroupEntity(id, kind)))
        dao.insertMembers(members.mapIndexed { i, m -> DupMemberEntity(m, id, 10f - i, i == 0) })
    }

    @Test fun needingFeaturesFindsMissingAndStaleOnly() = runBlocking {
        val ids = addPhotos(Triple("/a", false, 0), Triple("/b", false, 0), Triple("/c", false, 0))
        dao.upsertFeatures(listOf(feat(ids["/a"]!!), feat(ids["/b"]!!, size = 999)))
        assertEquals(setOf("/b", "/c"), dao.needingFeatures().map { it.path }.toSet())
    }

    @Test fun candidatesExcludeInvalidStaleDoneAndDismissed() = runBlocking {
        val ids = addPhotos(
            Triple("/ok", false, 0), Triple("/invalid", false, 0), Triple("/stale", false, 0),
            Triple("/done", false, 1), Triple("/dismissed", false, 0),
        )
        dao.upsertFeatures(
            listOf(
                feat(ids["/ok"]!!), feat(ids["/invalid"]!!, valid = false), feat(ids["/stale"]!!, modified = 5),
                feat(ids["/done"]!!), feat(ids["/dismissed"]!!),
            ),
        )
        dao.dismiss(listOf(DupDismissed(ids["/dismissed"]!!)))
        assertEquals(listOf("/ok"), dao.candidates().map { it.path })
    }

    @Test fun replaceGroupsRebuildsEverything() = runBlocking {
        val ids = addPhotos(Triple("/a", false, 0), Triple("/b", false, 0), Triple("/c", false, 0))
        ids.values.forEach { dao.upsertFeatures(listOf(feat(it))) }
        dao.replaceGroups(listOf(DupGroupEntity(1, 1)), listOf(DupMemberEntity(ids["/a"]!!, 1, 5f, true), DupMemberEntity(ids["/b"]!!, 1, 4f, false)))
        dao.replaceGroups(listOf(DupGroupEntity(1, 0)), listOf(DupMemberEntity(ids["/b"]!!, 1, 5f, true), DupMemberEntity(ids["/c"]!!, 1, 4f, false)))
        val rows = dao.dupRows()
        assertEquals(listOf("/b", "/c"), rows.map { it.photo.path })
        assertEquals(0, rows[0].kind)
        assertTrue(rows[0].isBest)
    }

    @Test fun dupRowsAreBestFirstAndHideDonePhotos() = runBlocking {
        val ids = addPhotos(Triple("/a", false, 0), Triple("/b", false, 0), Triple("/c", false, 1))
        ids.values.forEach { dao.upsertFeatures(listOf(feat(it))) }
        group(1, 1, ids["/b"]!!, ids["/a"]!!, ids["/c"]!!)
        assertEquals(listOf("/b", "/a"), dao.dupRows().map { it.photo.path })
    }

    @Test fun groupCountRespectsHiddenAndNeedsTwoOpenPhotos() = runBlocking {
        val ids = addPhotos(
            Triple("/a", false, 0), Triple("/b", false, 0), // visible group
            Triple("/c", false, 0), Triple("/.d", true, 0), // group with hidden member
            Triple("/e", false, 0), Triple("/f", false, 1), // second member already done
        )
        ids.values.forEach { dao.upsertFeatures(listOf(feat(it))) }
        group(1, 1, ids["/a"]!!, ids["/b"]!!)
        group(2, 1, ids["/c"]!!, ids["/.d"]!!)
        group(3, 1, ids["/e"]!!, ids["/f"]!!)
        assertEquals(1, dao.dupGroupCount(0).first())
        assertEquals(2, dao.dupGroupCount(1).first())
    }

    @Test fun deletingAPhotoCascadesAndPruneDropsSingleMemberGroups() = runBlocking {
        val ids = addPhotos(Triple("/a", false, 0), Triple("/b", false, 0), Triple("/c", false, 0))
        ids.values.forEach { dao.upsertFeatures(listOf(feat(it))) }
        group(1, 1, ids["/a"]!!, ids["/b"]!!)
        dao.applyReview(deletedIds = listOf(ids["/b"]!!), doneIds = emptyList(), dismissDuplicates = false)
        assertEquals(0, dao.dupGroupCount(1).first())
        assertTrue(dao.dupRows().isEmpty())
        assertEquals(setOf("/a", "/c"), dao.idPaths().map { it.path }.toSet())
        assertEquals(2, dao.counts().first().total)
    }

    @Test fun duplicateReviewMarksKeptDoneAndDismissesThemForever() = runBlocking {
        val ids = addPhotos(Triple("/a", false, 0), Triple("/b", false, 0), Triple("/c", false, 0))
        ids.values.forEach { dao.upsertFeatures(listOf(feat(it))) }
        group(1, 1, ids["/a"]!!, ids["/b"]!!, ids["/c"]!!)
        dao.applyReview(listOf(ids["/b"]!!), listOf(ids["/a"]!!), dismissDuplicates = true)
        assertEquals(listOf("/c"), dao.nextBatch(1, 30).map { it.path })
        assertEquals(setOf("/c"), dao.candidates().map { it.path }.toSet()) // /a is dismissed and done
        assertEquals(0, dao.dupGroupCount(1).first())
        assertEquals(1, dao.counts().first().done)
    }

    @Test fun normalReviewDoesNotDismiss() = runBlocking {
        val ids = addPhotos(Triple("/a", false, 0))
        dao.upsertFeatures(listOf(feat(ids["/a"]!!)))
        dao.applyReview(emptyList(), listOf(ids["/a"]!!), dismissDuplicates = false)
        dao.resetStatuses()
        assertEquals(listOf("/a"), dao.candidates().map { it.path }) // after a reset it can be grouped again
    }

    @Test fun applyReviewWithEmptyListsIsHarmless() = runBlocking {
        addPhotos(Triple("/a", false, 0))
        dao.applyReview(emptyList(), emptyList(), true)
        assertEquals(1, dao.counts().first().remaining)
    }

    // ---- analysis running while the user deletes photos ----

    @Test fun foreignKeysAreEnforced_soUnguardedInsertForADeletedPhotoWouldCrash() = runBlocking {
        val ids = addPhotos(Triple("/a", false, 0))
        dao.deleteByIds(listOf(ids["/a"]!!))
        var threw = false
        try { dao.insertMembers(listOf(DupMemberEntity(ids["/a"]!!, 1, 1f, true))) } catch (e: Exception) { threw = true }
        assertTrue(threw)
    }

    @Test fun savingFeaturesForAPhotoDeletedMeanwhileIsSkippedWithoutFailingTheRest() = runBlocking {
        val ids = addPhotos(Triple("/a", false, 0), Triple("/b", false, 0), Triple("/c", false, 0))
        dao.deleteByIds(listOf(ids["/b"]!!)) // user deleted it while the chunk was being analysed
        dao.upsertFeatures(listOf(feat(ids["/a"]!!), feat(ids["/b"]!!), feat(ids["/c"]!!)))
        assertEquals(setOf("/a", "/c"), dao.candidates().map { it.path }.toSet())
    }

    @Test fun rebuildingGroupsSkipsPhotosDeletedMeanwhile() = runBlocking {
        val ids = addPhotos(Triple("/a", false, 0), Triple("/b", false, 0), Triple("/c", false, 0), Triple("/d", false, 0))
        ids.values.forEach { dao.upsertFeatures(listOf(feat(it))) }
        val members = listOf("/a", "/b", "/c", "/d").mapIndexed { i, p -> DupMemberEntity(ids[p]!!, if (i < 2) 1 else 2, 10f - i, i % 2 == 0) }
        dao.deleteByIds(listOf(ids["/b"]!!, ids["/c"]!!, ids["/d"]!!)) // snapshot was taken before these deletions
        dao.replaceGroups(listOf(DupGroupEntity(1, 1), DupGroupEntity(2, 1)), members)
        // both groups dropped to a single survivor and are pruned: nothing dangling, nothing crashed
        assertEquals(0, dao.dupGroupCount(1).first())
        assertTrue(dao.dupRows().isEmpty())
    }

    @Test fun groupsStayIntactWhenOnlyOneMemberVanishedOfThree() = runBlocking {
        val ids = addPhotos(Triple("/a", false, 0), Triple("/b", false, 0), Triple("/c", false, 0))
        ids.values.forEach { dao.upsertFeatures(listOf(feat(it))) }
        dao.deleteByIds(listOf(ids["/c"]!!))
        dao.replaceGroups(
            listOf(DupGroupEntity(1, 1)),
            listOf("/a", "/b", "/c").mapIndexed { i, p -> DupMemberEntity(ids[p]!!, 1, 10f - i, i == 0) },
        )
        assertEquals(listOf("/a", "/b"), dao.dupRows().map { it.photo.path })
    }

    @Test fun reinsertingAFeatureReplacesIt() = runBlocking {
        val ids = addPhotos(Triple("/a", false, 0))
        dao.upsertFeatures(listOf(feat(ids["/a"]!!, valid = false)))
        dao.upsertFeatures(listOf(feat(ids["/a"]!!, valid = true)))
        assertEquals(1, dao.candidates().size)
    }
}

