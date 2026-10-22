package com.photocleanse.review

import com.photocleanse.data.Photo

const val BATCH_SIZE = 30 // default
const val MIN_BATCH_SIZE = 10
const val MAX_BATCH_SIZE = 100

fun clampBatchSize(v: Int): Int = v.coerceIn(MIN_BATCH_SIZE, MAX_BATCH_SIZE)

enum class Decision { APPROVE, DELETE }

/** What the user may still change on the confirmation screen. Nothing is deleted until [resolve] is acted upon. */
data class ConfirmPlan(val toDelete: List<Photo>, val approved: List<Photo>) {
    /**
     * [deselected] = ids the user un-ticked on the confirm screen. Only photos that were explicitly marked
     * for deletion can ever end up in [Resolution.delete]; unknown ids are ignored.
     */
    fun resolve(deselected: Set<Long>): Resolution {
        val delete = toDelete.filter { it.id !in deselected }
        val kept = toDelete.filter { it.id in deselected }
        return Resolution(delete = delete, markDone = approved + kept)
    }
}

data class Resolution(val delete: List<Photo>, val markDone: List<Photo>)

/**
 * Pure state machine for one batch review. The user may jump to any photo and change a decision; after each
 * decision the session moves on to the next photo that has no decision yet, and finishes when none are left.
 * Photos that get no decision (skipped) are never touched.
 */
class ReviewSession(val photos: List<Photo>) {
    private val decisions = HashMap<Long, Decision>()

    var index: Int = 0
        private set
    var finished: Boolean = photos.isEmpty()
        private set

    val size get() = photos.size
    val current: Photo? get() = if (finished) null else photos[index]
    val canUndo get() = !finished && index > 0

    fun decisionOf(id: Long): Decision? = decisions[id]
    fun decisionsSnapshot(): Map<Long, Decision> = HashMap(decisions)

    fun decide(decision: Decision) {
        val p = current ?: return
        decisions[p.id] = decision
        val next = (1..size).map { (index + it) % size }.firstOrNull { photos[it].id !in decisions }
        if (next == null) finished = true else index = next
    }

    fun goTo(i: Int) {
        if (!finished && i in photos.indices) index = i
    }

    fun skipRest() {
        finished = true
    }

    /** Leaves the finished state (e.g. "back" from the confirmation screen) without losing any decision. */
    fun reopen() {
        if (photos.isNotEmpty()) finished = false
    }

    /** Steps back one photo and forgets its decision. */
    fun undo() {
        if (!canUndo) return
        index--
        decisions.remove(photos[index].id)
    }

    fun plan(): ConfirmPlan = ConfirmPlan(
        toDelete = photos.filter { decisions[it.id] == Decision.DELETE },
        approved = photos.filter { decisions[it.id] == Decision.APPROVE },
    )
}
