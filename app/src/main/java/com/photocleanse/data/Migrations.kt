package com.photocleanse.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * v1 -> v2: adds the duplicate-detection tables. The `photos` table (and with it every "done" mark) is untouched.
 * These statements are checked against the exported schema (app/schemas/.../2.json) by MigrationSqlTest.
 */
val MIGRATION_1_2_SQL = listOf(
    "CREATE TABLE IF NOT EXISTS `photo_features` (`photoId` INTEGER NOT NULL, `valid` INTEGER NOT NULL, `phash` INTEGER NOT NULL, `colorSig` INTEGER NOT NULL, `width` INTEGER NOT NULL, `height` INTEGER NOT NULL, `takenAt` INTEGER NOT NULL, `sharpness` REAL NOT NULL, `hasCameraExif` INTEGER NOT NULL, `fileSize` INTEGER NOT NULL, `fileModified` INTEGER NOT NULL, PRIMARY KEY(`photoId`), FOREIGN KEY(`photoId`) REFERENCES `photos`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
    "CREATE TABLE IF NOT EXISTS `dup_groups` (`id` INTEGER NOT NULL, `kind` INTEGER NOT NULL, PRIMARY KEY(`id`))",
    "CREATE TABLE IF NOT EXISTS `dup_members` (`photoId` INTEGER NOT NULL, `groupId` INTEGER NOT NULL, `score` REAL NOT NULL, `isBest` INTEGER NOT NULL, PRIMARY KEY(`photoId`), FOREIGN KEY(`photoId`) REFERENCES `photos`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , FOREIGN KEY(`groupId`) REFERENCES `dup_groups`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
    "CREATE INDEX IF NOT EXISTS `index_dup_members_groupId` ON `dup_members` (`groupId`)",
    "CREATE TABLE IF NOT EXISTS `dup_dismissed` (`photoId` INTEGER NOT NULL, PRIMARY KEY(`photoId`), FOREIGN KEY(`photoId`) REFERENCES `photos`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
)

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        MIGRATION_1_2_SQL.forEach { db.execSQL(it) }
    }
}
