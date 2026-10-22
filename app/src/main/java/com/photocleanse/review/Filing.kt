package com.photocleanse.review

import com.photocleanse.data.Photo
import java.io.File

/** A photo and the folder (absolute path) the user chose for it. */
data class Move(val photo: Photo, val destDir: String)

/**
 * Pure state machine for one filing batch, same feel as [ReviewSession]: the user may jump to any photo and
 * re-assign it; after each assignment the session moves on to the next photo without a destination and finishes
 * when none are left. Photos without a destination (skipped) are never touched.
 */
class FilingSession(val photos: List<Photo>) {
    private val destinations = HashMap<Long, String>()
    private val history = ArrayList<Long>() // photo ids in the order they were filed, newest last

    var index: Int = 0
        private set
    var finished: Boolean = photos.isEmpty()
        private set

    val size get() = photos.size
    val current: Photo? get() = if (finished) null else photos[index]
    val canUndo get() = !finished && destinations.isNotEmpty()

    fun destinationsSnapshot(): Map<Long, String> = HashMap(destinations)

    fun assign(destDir: String) {
        val p = current ?: return
        destinations[p.id] = destDir
        history.remove(p.id)
        history += p.id
        val next = (1..size).map { (index + it) % size }.firstOrNull { photos[it].id !in destinations }
        if (next == null) finished = true else index = next
    }

    fun goTo(i: Int) {
        if (!finished && i in photos.indices) index = i
    }

    fun skipRest() {
        finished = true
    }

    fun reopen() {
        if (photos.isNotEmpty()) finished = false
    }

    /** Same rule as [ReviewSession.undo]: the current photo's own assignment if it has one, else the latest one. */
    fun undo() {
        if (!canUndo) return
        val id = current?.id?.takeIf { it in destinations } ?: history.last()
        destinations.remove(id)
        history.remove(id)
        index = photos.indexOfFirst { it.id == id }
    }

    fun plan(): List<Move> = photos.mapNotNull { p -> destinations[p.id]?.let { Move(p, it) } }
}

/** `IMG.jpg` -> `IMG-1.jpg`, `IMG-2.jpg`, ... like Android does for name clashes. Returns [name] if it is free. */
fun uniqueName(name: String, taken: (String) -> Boolean): String {
    if (!taken(name)) return name
    val dot = name.lastIndexOf('.')
    val (stem, ext) = if (dot > 0) name.substring(0, dot) to name.substring(dot) else name to ""
    var n = 1
    while (taken("$stem-$n$ext")) n++
    return "$stem-$n$ext"
}

interface MoveOps {
    fun exists(path: String): Boolean

    /** Moves a file; must leave [dst] complete and [src] gone on success, and no partial [dst] on failure. */
    fun move(src: String, dst: String): Boolean
}

object RealMoveOps : MoveOps {
    override fun exists(path: String) = File(path).exists()

    override fun move(src: String, dst: String): Boolean {
        val s = File(src)
        val d = File(dst)
        d.parentFile?.mkdirs()
        if (s.renameTo(d)) return true
        // Different volume (internal storage <-> SD card): copy, verify, then remove the original.
        return try {
            s.copyTo(d, overwrite = false)
            if (d.length() == s.length()) {
                d.setLastModified(s.lastModified())
                s.delete() || run { d.delete(); false }
            } else {
                d.delete()
                false
            }
        } catch (e: Exception) {
            d.delete()
            false
        }
    }
}

data class MovedPhoto(val photo: Photo, val newPath: String)
data class MoveOutcome(val moved: List<MovedPhoto>, val failed: List<Photo>)

/**
 * Moves every photo into its destination folder, one by one. Name clashes (also between photos of this very
 * batch) get `-1`, `-2`, ... A failing move lands in [MoveOutcome.failed] and processing continues.
 */
fun movePhotos(moves: List<Move>, ops: MoveOps = RealMoveOps): MoveOutcome {
    val moved = ArrayList<MovedPhoto>()
    val failed = ArrayList<Photo>()
    for ((photo, dir) in moves) {
        val ok = try {
            val name = uniqueName(File(photo.path).name) { ops.exists(File(dir, it).path) }
            val target = File(dir, name).path
            if (target == photo.path) {
                moved += MovedPhoto(photo, target)
                true
            } else if (ops.exists(photo.path) && ops.move(photo.path, target)) {
                moved += MovedPhoto(photo, target)
                true
            } else {
                false
            }
        } catch (e: Exception) {
            false
        }
        if (!ok) failed += photo
    }
    return MoveOutcome(moved, failed)
}

/**
 * Sub-folders of [dir], sorted by name. Dot-folders are only listed with [showHidden]. [hasChildren] tells the UI
 * whether to offer drill-down; looking it up costs one extra directory read per folder, so callers that do not
 * need it (the folder picker) pass [checkChildren] = false.
 */
data class FolderEntry(val path: String, val name: String, val hasChildren: Boolean)

fun listFolders(dir: File, showHidden: Boolean = false, checkChildren: Boolean = true): List<FolderEntry> {
    fun visible(f: File) = f.isDirectory && (showHidden || !f.name.startsWith("."))
    return (dir.listFiles { f -> visible(f) } ?: emptyArray())
        .sortedBy { it.name.lowercase() }
        .map { f -> FolderEntry(f.path, f.name, checkChildren && f.listFiles { c -> visible(c) }?.isNotEmpty() == true) }
}

/** Folder names may not be empty, contain path separators, or be `.`/`..`. */
fun validFolderName(name: String): Boolean {
    val n = name.trim()
    return n.isNotEmpty() && n != "." && n != ".." && n.none { it == '/' || it == '\\' }
}

/**
 * Stable hue (0..359) for a folder, derived from its name only, so "Family" keeps its colour across sessions and
 * whatever folders come and go around it. Case-insensitive; String.hashCode is specified, so it never changes.
 */
fun folderHue(name: String): Int = ((name.trim().lowercase().hashCode() * -1640531535) ushr 8) % 360
