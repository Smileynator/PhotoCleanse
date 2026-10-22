package com.photocleanse.data

import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/** Guards the hand-written migration against the schema Room actually exports for version 2. */
class MigrationSqlTest {
    private val schema = JSONObject(File("schemas/com.photocleanse.data.AppDatabase/2.json").readText())
        .getJSONObject("database").getJSONArray("entities")

    private fun entity(name: String): JSONObject =
        (0 until schema.length()).map { schema.getJSONObject(it) }.first { it.getString("tableName") == name }

    private fun sqlOf(e: JSONObject): List<String> {
        val t = e.getString("tableName")
        val out = arrayListOf(e.getString("createSql").replace("\${TABLE_NAME}", t))
        val idx = e.getJSONArray("indices")
        for (i in 0 until idx.length()) out += idx.getJSONObject(i).getString("createSql").replace("\${TABLE_NAME}", t)
        return out
    }

    @Test fun `migration creates exactly the new tables and indices of schema v2`() {
        val expected = listOf("photo_features", "dup_groups", "dup_members", "dup_dismissed").flatMap { sqlOf(entity(it)) }
        assertEquals(expected.toSet(), MIGRATION_1_2_SQL.toSet())
        assertEquals(expected.size, MIGRATION_1_2_SQL.size)
    }

    @Test fun `photos table is unchanged from version 1 so existing done marks survive`() {
        val v1 = listOf(
            "CREATE TABLE IF NOT EXISTS `photos` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `path` TEXT NOT NULL, `modified` INTEGER NOT NULL, `size` INTEGER NOT NULL, `hidden` INTEGER NOT NULL, `status` INTEGER NOT NULL)",
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_photos_path` ON `photos` (`path`)",
            "CREATE INDEX IF NOT EXISTS `index_photos_status_hidden_modified` ON `photos` (`status`, `hidden`, `modified`)",
        )
        assertEquals(v1, sqlOf(entity("photos")))
    }

    @Test fun `schema has exactly the expected tables`() {
        val names = (0 until schema.length()).map { schema.getJSONObject(it).getString("tableName") }.toSet()
        assertEquals(setOf("photos", "photo_features", "dup_groups", "dup_members", "dup_dismissed"), names)
    }
}
