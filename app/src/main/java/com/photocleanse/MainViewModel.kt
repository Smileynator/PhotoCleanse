package com.photocleanse

import android.app.Application
import android.media.MediaScannerConnection
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.photocleanse.data.Counts
import com.photocleanse.data.Photo
import com.photocleanse.dupes.Analyzer
import com.photocleanse.dupes.DupKind
import com.photocleanse.dupes.Quality
import com.photocleanse.dupes.buildDupBatch
import com.photocleanse.dupes.fullyDeletedGroups
import com.photocleanse.dupes.toCandidate
import com.photocleanse.review.clampBatchSize
import com.photocleanse.review.ConfirmPlan
import com.photocleanse.review.Decision
import com.photocleanse.review.ReviewSession
import com.photocleanse.review.deletePhotos
import com.photocleanse.scan.Scanner
import com.photocleanse.scan.Volumes
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class Screen { Start, Review, Confirm }

/** Extra info shown in duplicate reviews. */
data class DupMeta(
    val groupNo: Int,
    val groupCount: Int,
    val groupSize: Int,
    val kind: DupKind,
    val isBest: Boolean,
    val badges: List<String>,
    val width: Int,
    val height: Int,
    val sharpness: Float,
)

data class ReviewItem(val photo: Photo, val meta: DupMeta?)

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val dao = (app as PhotoApp).db.dao()
    private val scanner = Scanner(dao) { Volumes.mountedRoots(app) }
    private val analyzer = Analyzer(dao)
    private var scanJob: Job? = null

    val scan = scanner.state
    val analysis = analyzer.state
    val counts: StateFlow<Counts> =
        dao.counts().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), Counts(0, 0, 0, 0))

    private val settings = Settings(app)

    /** Photos per review batch (10..100), remembered between sessions. */
    var batchLimit by mutableIntStateOf(settings.batchSize)
        private set

    fun setBatchLimit(value: Int, persist: Boolean = false) {
        batchLimit = clampBatchSize(value)
        if (persist) settings.batchSize = batchLimit
    }

    var includeHidden by mutableStateOf(false)
    val dupGroups: StateFlow<Int> = snapshotFlow { includeHidden }
        .flatMapLatest { dao.dupGroupCount(if (it) 1 else 0) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    var screen by mutableStateOf(Screen.Start); private set
    var message by mutableStateOf<String?>(null)
    var busy by mutableStateOf(false); private set
    var duplicateMode by mutableStateOf(false); private set

    // Review state. ReviewSession is a plain object, so we mirror what the UI needs in snapshot state.
    private var session: ReviewSession? = null
    var items by mutableStateOf<List<ReviewItem>>(emptyList()); private set
    val decisions = mutableStateMapOf<Long, Decision>()
    var currentIndex by mutableIntStateOf(0); private set
    var currentPath by mutableStateOf<String?>(null); private set
    var canUndo by mutableStateOf(false); private set
    var upcomingPaths by mutableStateOf<List<String>>(emptyList()); private set
    val batchSize get() = items.size
    val currentItem get() = items.getOrNull(currentIndex)

    // Confirm state
    private var plan: ConfirmPlan = ConfirmPlan(emptyList(), emptyList())
    private var groupOf: Map<Long, Long> = emptyMap()
    val toDelete get() = plan.toDelete
    val approvedCount get() = plan.approved.size
    val deselected = mutableStateListOf<Long>()

    /** Number of duplicate groups for which no copy would survive with the current selection. */
    val groupsFullyDeleted: Int
        get() {
            if (!duplicateMode) return 0
            val del = plan.toDelete.map { it.id }.filter { it !in deselected }.toSet()
            return fullyDeletedGroups(groupOf, del).size
        }

    fun startScan() {
        if (scanJob?.isActive == true) return
        scanJob = viewModelScope.launch {
            try {
                scanner.scan()
                analyzer.run()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // A background indexing problem must never crash the app; the next scan simply retries.
                message = "Background scan stopped: ${e.message ?: e.javaClass.simpleName}"
            }
        }
    }

    fun startBatch() {
        if (busy) return
        viewModelScope.launch {
            val batch = dao.nextBatch(if (includeHidden) 1 else 0, batchLimit)
            if (batch.isEmpty()) {
                message = "No unreviewed photos left."
                return@launch
            }
            begin(batch.map { ReviewItem(it, null) }, emptyMap(), duplicates = false)
        }
    }

    fun startDuplicateBatch() {
        if (busy) return
        viewModelScope.launch {
            val groups = buildDupBatch(dao.dupRows(), includeHidden, batchLimit)
            if (groups.isEmpty()) {
                message = "No duplicates found (yet)."
                return@launch
            }
            val list = ArrayList<ReviewItem>()
            val owners = HashMap<Long, Long>()
            groups.forEachIndexed { gi, g ->
                val cands = g.rows.map { it.toCandidate() }
                val best = cands[0]
                g.rows.forEachIndexed { ri, row ->
                    owners[row.photo.id] = g.groupId
                    val badges = if (ri == 0) listOf("Best copy") else Quality.badges(cands[ri], best)
                    list += ReviewItem(
                        row.photo,
                        DupMeta(
                            gi + 1, groups.size, g.rows.size, g.kind, ri == 0, badges,
                            row.width, row.height, row.sharpness,
                        ),
                    )
                }
            }
            begin(list, owners, duplicates = true)
        }
    }

    private fun begin(list: List<ReviewItem>, owners: Map<Long, Long>, duplicates: Boolean) {
        message = null
        duplicateMode = duplicates
        groupOf = owners
        items = list
        session = ReviewSession(list.map { it.photo })
        sync()
        screen = Screen.Review
    }

    fun decide(decision: Decision) {
        val s = session ?: return
        s.decide(decision)
        if (s.finished) finishReview() else sync()
    }

    fun goTo(index: Int) {
        session?.goTo(index)
        sync()
    }

    fun undo() {
        session?.undo()
        sync()
    }

    fun skipRest() {
        session?.skipRest()
        finishReview()
    }

    private fun sync() {
        val s = session ?: return
        currentIndex = s.index
        currentPath = s.photos.getOrNull(s.index)?.path
        canUndo = s.canUndo
        upcomingPaths = s.photos.drop(s.index + 1).take(3).map { it.path }
        decisions.clear()
        decisions.putAll(s.decisionsSnapshot())
    }

    private fun finishReview() {
        sync()
        plan = session!!.plan()
        deselected.clear()
        screen = Screen.Confirm
    }

    /** From the confirmation screen back to reviewing; every decision is kept. */
    fun backToReview() {
        if (busy) return
        session?.reopen()
        sync()
        screen = Screen.Review
    }

    fun toggleKeep(id: Long) {
        if (id in deselected) deselected.remove(id) else deselected.add(id)
    }

    /** Leave review/confirm without changing anything on disk or in the database. */
    fun cancelToStart() {
        session = null
        items = emptyList()
        decisions.clear()
        plan = ConfirmPlan(emptyList(), emptyList())
        groupOf = emptyMap()
        screen = Screen.Start
    }

    fun confirm() {
        if (busy) return
        val resolution = plan.resolve(deselected.toSet())
        val dismiss = duplicateMode
        busy = true
        viewModelScope.launch {
            val failed = try {
                withContext(Dispatchers.IO) {
                    val outcome = deletePhotos(resolution.delete)
                    // Database only changes after the files have really been dealt with.
                    dao.applyReview(outcome.deleted.map { it.id }, resolution.markDone.map { it.id }, dismiss)
                    if (outcome.deleted.isNotEmpty()) {
                        MediaScannerConnection.scanFile(
                            getApplication(), outcome.deleted.map { it.path }.toTypedArray(), null, null,
                        )
                    }
                    outcome.failed.size
                }
            } finally {
                busy = false
            }
            message = buildString {
                append("Deleted ${resolution.delete.size - failed}, kept ${resolution.markDone.size}.")
                if (failed > 0) append(" $failed could not be deleted and stay in the queue.")
            }
            cancelToStart()
        }
    }

    fun resetReviewed() {
        viewModelScope.launch {
            dao.resetStatuses()
            message = "All photos are unreviewed again."
        }
    }
}
