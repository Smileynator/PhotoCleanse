package com.photocleanse

import android.content.Context
import com.photocleanse.review.BATCH_SIZE
import com.photocleanse.review.clampBatchSize

/** Tiny persisted user settings. Values read back from disk are always clamped to the valid range. */
class Settings(ctx: Context) {
    private val prefs = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var batchSize: Int
        get() = clampBatchSize(prefs.getInt(KEY_BATCH, BATCH_SIZE))
        set(value) = prefs.edit().putInt(KEY_BATCH, clampBatchSize(value)).apply()

    /** Absolute path of the folder kept photos are filed into, or null while none is chosen. */
    var photosRoot: String?
        get() = prefs.getString(KEY_PHOTOS_ROOT, null)
        set(value) = prefs.edit().putString(KEY_PHOTOS_ROOT, value).apply()

    private companion object {
        const val KEY_BATCH = "batch_size"
        const val KEY_PHOTOS_ROOT = "photos_root"
    }
}
