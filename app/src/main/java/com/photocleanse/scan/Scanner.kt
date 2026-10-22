package com.photocleanse.scan

import com.photocleanse.data.Photo
import com.photocleanse.data.PhotoDao
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

data class ScanState(
    val running: Boolean = false,
    val entriesChecked: Long = 0,
    val photosFound: Int = 0,
)

/**
 * Indexes every volume returned by [roots] (internal storage, SD cards, USB drives).
 * The index of a volume is only ever pruned when that volume was mounted and readable during the scan:
 * rows belonging to a volume that is currently absent (card removed) are left completely untouched.
 */
class Scanner(private val dao: PhotoDao, private val roots: () -> List<File>) {
    private val _state = MutableStateFlow(ScanState())
    val state: StateFlow<ScanState> = _state

    suspend fun scan() = withContext(Dispatchers.IO) {
        val ctx = currentCoroutineContext()
        _state.value = ScanState(running = true)
        try {
            val seen = HashSet<String>()
            val scannedRoots = ArrayList<String>()
            val buffer = ArrayList<Photo>(BUFFER)
            var checked = 0L
            var found = 0

            for (root in roots()) {
                val images = FileWalker.walk(root, { shouldSkipDir(it, root) }) {
                    ctx.ensureActive()
                    if (++checked % 250 == 0L) _state.value = ScanState(true, checked, found)
                }
                for (img in images) {
                    seen += img.path
                    buffer += Photo(path = img.path, modified = img.modified, size = img.size, hidden = img.hidden)
                    found++
                    if (buffer.size >= BUFFER) {
                        dao.insertAll(buffer.toList())
                        buffer.clear()
                        _state.value = ScanState(true, checked, found)
                    }
                }
                // Only a volume that is still readable after its walk counts as "scanned" (a card pulled
                // mid-scan, or a failed read, must never lead to pruning that volume's index).
                if (root.listFiles()?.isNotEmpty() == true) scannedRoots += root.path
            }
            if (buffer.isNotEmpty()) dao.insertAll(buffer.toList())

            if (scannedRoots.isNotEmpty()) {
                val stale = staleIds(dao.idPaths(), seen, scannedRoots)
                stale.chunked(500).forEach { dao.deleteByIds(it) }
            }
            _state.value = ScanState(false, checked, found)
        } finally {
            _state.value = _state.value.copy(running = false)
        }
    }

    private companion object {
        const val BUFFER = 500
    }
}
