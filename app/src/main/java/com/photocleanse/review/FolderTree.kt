package com.photocleanse.review

import java.io.File
import java.nio.file.Files

/**
 * In-memory copy of the folder structure under the photos folder, read from disk once when filing starts and
 * kept current by [add] when the user creates a folder. Browsing the tree afterwards never touches the disk.
 * Dot-folders are always scanned; [children] filters them at read time so the hidden-folders toggle needs no rescan.
 */
class FolderTree private constructor(val root: String, private val kids: HashMap<String, MutableList<String>>) {

    /** Direct sub-folders of [path] (absolute path) sorted by name; empty for unknown paths. */
    fun children(path: String, showHidden: Boolean): List<FolderEntry> =
        (kids[path] ?: emptyList<String>())
            .filter { showHidden || !File(it).name.startsWith(".") }
            .map { FolderEntry(it, File(it).name, hasVisibleChild(it, showHidden)) }

    private fun hasVisibleChild(path: String, showHidden: Boolean) =
        kids[path]?.any { showHidden || !File(it).name.startsWith(".") } == true

    /** Records a folder that was just created on disk. */
    fun add(parent: String, name: String) {
        val path = File(parent, name).path
        val list = kids.getOrPut(parent) { ArrayList() }
        if (path !in list) {
            list += path
            list.sortBy { File(it).name.lowercase() }
        }
        kids.getOrPut(path) { ArrayList() }
    }

    companion object {
        private const val MAX_DEPTH = 16

        /** Walks [root] once (call off the main thread). Symlinks are not followed, so loops cannot hang the scan. */
        fun scan(root: String): FolderTree {
            val kids = HashMap<String, MutableList<String>>()
            val stack = ArrayDeque<Pair<File, Int>>()
            stack.addLast(File(root) to 0)
            while (stack.isNotEmpty()) {
                val (dir, depth) = stack.removeLast()
                val subs = (dir.listFiles { f -> f.isDirectory && !isLink(f) } ?: emptyArray())
                    .sortedBy { it.name.lowercase() }
                kids[dir.path] = subs.mapTo(ArrayList()) { it.path }
                if (depth < MAX_DEPTH) subs.forEach { stack.addLast(it to depth + 1) }
            }
            return FolderTree(root, kids)
        }

        private fun isLink(f: File) = try { Files.isSymbolicLink(f.toPath()) } catch (e: Exception) { false }
    }
}
