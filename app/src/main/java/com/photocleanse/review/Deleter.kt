package com.photocleanse.review

import com.photocleanse.data.Photo
import java.io.File

interface FileOps {
    fun exists(path: String): Boolean
    fun delete(path: String): Boolean
}

object RealFileOps : FileOps {
    override fun exists(path: String) = File(path).exists()
    override fun delete(path: String) = File(path).delete()
}

data class DeleteOutcome(val deleted: List<Photo>, val failed: List<Photo>)

/**
 * Deletes exactly the given photos, one by one. A photo counts as deleted only if the file is gone afterwards;
 * an exception or a refused delete puts it in [DeleteOutcome.failed] and processing continues.
 */
fun deletePhotos(photos: List<Photo>, ops: FileOps = RealFileOps): DeleteOutcome {
    val deleted = ArrayList<Photo>()
    val failed = ArrayList<Photo>()
    for (p in photos) {
        val gone = try {
            !ops.exists(p.path) || (ops.delete(p.path) && !ops.exists(p.path))
        } catch (e: Exception) {
            false
        }
        if (gone) deleted += p else failed += p
    }
    return DeleteOutcome(deleted, failed)
}
