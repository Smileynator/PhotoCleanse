package com.photocleanse.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Opens a real version-1 database file with the production migration and checks nothing the user did is lost. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class MigrationTest {
    @Test fun `v1 database upgrades to v2 and keeps photos and their done status`() = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val name = "migration-test.db"
        ctx.deleteDatabase(name)
        val file = ctx.getDatabasePath(name).also { it.parentFile!!.mkdirs() }
        SQLiteDatabase.openOrCreateDatabase(file, null).use { v1 ->
            v1.execSQL("CREATE TABLE IF NOT EXISTS `photos` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `path` TEXT NOT NULL, `modified` INTEGER NOT NULL, `size` INTEGER NOT NULL, `hidden` INTEGER NOT NULL, `status` INTEGER NOT NULL)")
            v1.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_photos_path` ON `photos` (`path`)")
            v1.execSQL("CREATE INDEX IF NOT EXISTS `index_photos_status_hidden_modified` ON `photos` (`status`, `hidden`, `modified`)")
            v1.execSQL("INSERT INTO photos (path, modified, size, hidden, status) VALUES ('/a.jpg', 1, 10, 0, 1), ('/.h/b.jpg', 2, 20, 1, 0), ('/c.jpg', 3, 30, 0, 0)")
            v1.version = 1
        }

        val db = Room.databaseBuilder(ctx, AppDatabase::class.java, name).addMigrations(MIGRATION_1_2).allowMainThreadQueries().build()
        val dao = db.dao()
        val c = dao.counts().first()
        assertEquals(Counts(total = 3, done = 1, remaining = 2, hiddenRemaining = 1), c)
        assertEquals(listOf("/c.jpg"), dao.nextBatch(0, 30).map { it.path })
        // new tables are usable
        val id = dao.idPaths().first { it.path == "/c.jpg" }.id
        dao.upsertFeatures(listOf(PhotoFeatures(id, true, 1, 2, 3, 4, 5, 6f, true, 30, 3)))
        assertEquals(1, dao.candidates().size)
        // cascade works after migration: deleting the photo removes its features
        dao.deleteByIds(listOf(id))
        assertEquals(0, dao.candidates().size)
        db.close()
    }
}
