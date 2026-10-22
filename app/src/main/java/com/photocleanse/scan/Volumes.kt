package com.photocleanse.scan

import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import androidx.core.content.ContextCompat
import java.io.File

object Volumes {
    /** Internal storage plus every currently mounted SD card / USB drive, without nested duplicates. */
    fun mountedRoots(ctx: Context): List<File> {
        val found = ArrayList<File>()
        found += Environment.getExternalStorageDirectory()
        if (Build.VERSION.SDK_INT >= 30) {
            val sm = ctx.getSystemService(Context.STORAGE_SERVICE) as StorageManager
            sm.storageVolumes.filter { isReadableState(it.state) }.mapNotNullTo(found) { it.directory }
        } else {
            ContextCompat.getExternalFilesDirs(ctx, null).filterNotNull()
                .mapNotNullTo(found) { volumeRootFromAppDir(it.path)?.let(::File) }
        }
        return dedupeRoots(found.filter { it.isDirectory })
    }

    fun isReadableState(state: String) =
        state == Environment.MEDIA_MOUNTED || state == Environment.MEDIA_MOUNTED_READ_ONLY

    /** `/storage/ABCD-1234/Android/data/com.x/files` -> `/storage/ABCD-1234` (pre-Android 11 volume lookup). */
    fun volumeRootFromAppDir(path: String): String? {
        val p = path.replace('\\', '/')
        val i = p.indexOf("/Android/data")
        return if (i > 0) p.substring(0, i) else null
    }

    /** Drops duplicates and roots nested inside another root (they would be scanned twice). Order is kept. */
    fun dedupeRoots(roots: List<File>): List<File> {
        val unique = roots.distinctBy { it.path.trimEnd('/', '\\') }
        return unique.filter { r ->
            unique.none { other -> other !== r && isUnder(r.path, other.path) }
        }
    }
}
