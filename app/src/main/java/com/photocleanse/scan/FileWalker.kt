package com.photocleanse.scan

import java.io.File
import java.nio.file.Files

val IMAGE_EXTENSIONS = setOf(
    "jpg", "jpeg", "jpe", "jfif", "png", "gif", "webp", "bmp", "heic", "heif", "avif",
    "tif", "tiff", "dng", "ico", "wbmp",
)

fun isImageFile(name: String): Boolean {
    val dot = name.lastIndexOf('.')
    if (dot < 0 || dot == name.length - 1) return false
    return name.substring(dot + 1).lowercase() in IMAGE_EXTENSIONS
}

/** System-generated cache folders full of preview copies that are not the user's photos (and often not deletable). */
val EXCLUDED_DIR_NAMES = setOf(".thumbnails")

/**
 * Folders the scanner never enters: thumbnail caches anywhere, and Android/data + Android/obb under [root]
 * (not readable even with all-files access).
 */
fun shouldSkipDir(dir: File, root: File): Boolean {
    if (dir.name.lowercase() in EXCLUDED_DIR_NAMES) return true
    val androidDir = File(root, "Android")
    return dir.parentFile == androidDir && (dir.name == "data" || dir.name == "obb")
}

data class FoundImage(val path: String, val modified: Long, val size: Long, val hidden: Boolean)

object FileWalker {
    /**
     * Lazily walks [root] and yields every image file. An image is "hidden" when the file or any
     * folder below [root] has a name starting with '.'. Hidden folders are walked, not skipped.
     * Symlinked directories are not followed (avoids loops); unreadable folders are skipped.
     * Directories for which [skipDir] returns true are not entered.
     * [onEntry] is called for every file/dir entry seen (for progress / cancellation).
     */
    fun walk(
        root: File,
        skipDir: (File) -> Boolean = { false },
        onEntry: () -> Unit = {},
    ): Sequence<FoundImage> = sequence {
        val stack = ArrayDeque<Pair<File, Boolean>>()
        stack.addLast(root to false)
        while (stack.isNotEmpty()) {
            val (dir, dirHidden) = stack.removeLast()
            val children = try {
                dir.listFiles()
            } catch (e: SecurityException) {
                null
            } ?: continue
            for (child in children) {
                onEntry()
                val hidden = dirHidden || child.name.startsWith(".")
                if (child.isDirectory) {
                    if (Files.isSymbolicLink(child.toPath()) || skipDir(child)) continue
                    stack.addLast(child to hidden)
                } else if (isImageFile(child.name)) {
                    yield(FoundImage(child.path, child.lastModified(), child.length(), hidden))
                }
            }
        }
    }
}

/** True when [path] lies strictly below [root] (separator-agnostic). */
fun isUnder(path: String, root: String): Boolean =
    path.replace('\\', '/').startsWith(root.replace('\\', '/').trimEnd('/') + "/")

/**
 * Rows whose file was not seen in a completed scan AND that live on a volume that was really scanned
 * ([scannedRoots]). Rows of any other (absent) volume are never returned. Only used to prune the index,
 * never to delete files.
 */
fun staleIds(rows: List<com.photocleanse.data.IdPath>, seen: Set<String>, scannedRoots: List<String>): List<Long> =
    rows.filter { it.path !in seen && scannedRoots.any { r -> isUnder(it.path, r) } }.map { it.id }
