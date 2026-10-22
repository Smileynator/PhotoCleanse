package com.photocleanse.dupes

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.exifinterface.media.ExifInterface
import com.photocleanse.data.DupGroupEntity
import com.photocleanse.data.DupMemberEntity
import com.photocleanse.data.Photo
import com.photocleanse.data.PhotoDao
import com.photocleanse.data.PhotoFeatures
import java.io.File
import java.util.concurrent.Executors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

data class AnalyzeState(val running: Boolean = false, val done: Int = 0, val total: Int = 0)

/** Computes fingerprints for photos that have none (or changed), then rebuilds the duplicate groups. */
class Analyzer(private val dao: PhotoDao) {
    private val _state = MutableStateFlow(AnalyzeState())
    val state: StateFlow<AnalyzeState> = _state

    suspend fun run() = withContext(Dispatchers.IO) {
        try {
            val todo = dao.needingFeatures()
            _state.value = AnalyzeState(true, 0, todo.size)
            if (todo.isNotEmpty()) {
                // Low priority: analysis must never make reviewing photos feel sluggish.
                val pool = Executors.newFixedThreadPool(3) { r ->
                    Thread(r, "analyzer").apply { priority = Thread.MIN_PRIORITY; isDaemon = true }
                }.asCoroutineDispatcher()
                try {
                    var done = 0
                    for (chunk in todo.chunked(60)) {
                        val features = coroutineScope { chunk.map { p -> async(pool) { extract(p) } }.awaitAll() }
                        dao.upsertFeatures(features)
                        done += chunk.size
                        _state.value = AnalyzeState(true, done, todo.size)
                    }
                } finally {
                    pool.close()
                }
            }
            regroup()
        } finally {
            _state.value = AnalyzeState(false, _state.value.done, _state.value.total)
        }
    }

    suspend fun regroup() {
        val groups = Grouping.cluster(dao.candidates(), DupConfig()) { a, b -> filesIdentical(a.path, b.path) }
        val entities = groups.mapIndexed { i, g -> DupGroupEntity(i + 1L, g.kind.ordinal) }
        val members = groups.flatMapIndexed { i, g ->
            g.members.mapIndexed { j, m -> DupMemberEntity(m.candidate.id, i + 1L, m.score, j == 0) }
        }
        dao.replaceGroups(entities, members)
    }

    private fun extract(p: Photo): PhotoFeatures {
        val invalid = PhotoFeatures(p.id, false, 0, 0, 0, 0, p.modified, 0f, false, p.size, p.modified)
        return try {
            decode(p) ?: invalid
        } catch (e: Exception) {
            invalid
        } catch (e: OutOfMemoryError) {
            invalid
        }
    }

    private fun decode(p: Photo): PhotoFeatures? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(p.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        while (minOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= Perceptual.SRC) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bmp = BitmapFactory.decodeFile(p.path, opts) ?: return null
        val scaled = Bitmap.createScaledBitmap(bmp, Perceptual.SRC, Perceptual.SRC, true)
        if (scaled !== bmp) bmp.recycle()
        val px = IntArray(Perceptual.SRC * Perceptual.SRC)
        scaled.getPixels(px, 0, Perceptual.SRC, 0, 0, Perceptual.SRC, Perceptual.SRC)
        scaled.recycle()

        var orientation = ExifInterface.ORIENTATION_NORMAL
        var takenAt = p.modified
        var hasCamera = false
        try {
            val exif = ExifInterface(File(p.path))
            orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            exif.dateTimeOriginal?.let { takenAt = it }
            hasCamera = exif.getAttribute(ExifInterface.TAG_MAKE) != null || exif.getAttribute(ExifInterface.TAG_MODEL) != null
        } catch (e: Exception) {
            // no readable EXIF: keep defaults
        }
        val swap = orientation in 5..8
        val r = Perceptual.compute(px, orientation)
        return PhotoFeatures(
            photoId = p.id, valid = true, phash = r.phash, colorSig = r.colorSig,
            width = if (swap) bounds.outHeight else bounds.outWidth,
            height = if (swap) bounds.outWidth else bounds.outHeight,
            takenAt = takenAt, sharpness = r.sharpness, hasCameraExif = hasCamera,
            fileSize = p.size, fileModified = p.modified,
        )
    }
}
