package com.photocleanse.review

import com.photocleanse.data.Photo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReviewSessionTest {
    private fun photos(n: Int) = (1..n).map { Photo(id = it.toLong(), path = "/p$it.jpg", modified = 0, size = 1, hidden = false) }
    private fun ids(l: List<Photo>) = l.map { it.id }

    @Test fun `walks through photos in order`() {
        val s = ReviewSession(photos(3))
        assertEquals(1L, s.current!!.id)
        s.decide(Decision.APPROVE)
        assertEquals(2L, s.current!!.id)
        s.decide(Decision.DELETE)
        s.decide(Decision.APPROVE)
        assertTrue(s.finished)
        assertNull(s.current)
    }

    @Test fun `plan splits delete and approve`() {
        val s = ReviewSession(photos(4))
        s.decide(Decision.DELETE); s.decide(Decision.APPROVE); s.decide(Decision.DELETE); s.decide(Decision.APPROVE)
        val p = s.plan()
        assertEquals(listOf(1L, 3L), ids(p.toDelete))
        assertEquals(listOf(2L, 4L), ids(p.approved))
    }

    @Test fun `skip rest leaves undecided photos out of the plan entirely`() {
        val s = ReviewSession(photos(5))
        s.decide(Decision.DELETE); s.decide(Decision.APPROVE)
        s.skipRest()
        assertTrue(s.finished)
        val p = s.plan()
        assertEquals(listOf(1L), ids(p.toDelete))
        assertEquals(listOf(2L), ids(p.approved))
        val r = p.resolve(emptySet())
        assertEquals(listOf(1L), ids(r.delete))
        assertEquals(listOf(2L), ids(r.markDone)) // 3..5 are neither deleted nor marked done
    }

    @Test fun `skip with no decisions yields empty plan`() {
        val s = ReviewSession(photos(3))
        s.skipRest()
        val r = s.plan().resolve(emptySet())
        assertTrue(r.delete.isEmpty()); assertTrue(r.markDone.isEmpty())
    }

    @Test fun `decide after finish is ignored`() {
        val s = ReviewSession(photos(1))
        s.decide(Decision.APPROVE)
        s.decide(Decision.DELETE)
        assertTrue(s.plan().toDelete.isEmpty())
    }

    @Test fun `empty batch is finished immediately`() {
        val s = ReviewSession(emptyList())
        assertTrue(s.finished)
        assertNull(s.current)
    }

    @Test fun `undo steps back and forgets decision`() {
        val s = ReviewSession(photos(3))
        s.decide(Decision.DELETE)
        assertTrue(s.canUndo)
        s.undo()
        assertEquals(1L, s.current!!.id)
        assertFalse(s.canUndo)
        s.decide(Decision.APPROVE)
        assertTrue(s.plan().toDelete.isEmpty())
        assertEquals(listOf(1L), ids(s.plan().approved))
    }

    @Test fun `undo at start or after finish is a no-op`() {
        val s = ReviewSession(photos(1))
        s.undo()
        assertEquals(0, s.index)
        s.decide(Decision.DELETE)
        s.undo()
        assertEquals(listOf(1L), ids(s.plan().toDelete))
    }

    @Test fun `deselected photos are kept and marked done`() {
        val s = ReviewSession(photos(4))
        s.decide(Decision.DELETE); s.decide(Decision.DELETE); s.decide(Decision.APPROVE); s.decide(Decision.DELETE)
        val r = s.plan().resolve(setOf(2L))
        assertEquals(listOf(1L, 4L), ids(r.delete))
        assertEquals(setOf(2L, 3L), ids(r.markDone).toSet())
    }

    @Test fun `deselecting everything deletes nothing`() {
        val s = ReviewSession(photos(2))
        s.decide(Decision.DELETE); s.decide(Decision.DELETE)
        val r = s.plan().resolve(setOf(1L, 2L))
        assertTrue(r.delete.isEmpty())
        assertEquals(2, r.markDone.size)
    }

    @Test fun `approved photos can never be deleted, even if their id is in deselected or unknown ids are passed`() {
        val s = ReviewSession(photos(3))
        s.decide(Decision.APPROVE); s.decide(Decision.DELETE); s.decide(Decision.APPROVE)
        val r = s.plan().resolve(setOf(1L, 99L))
        assertEquals(listOf(2L), ids(r.delete))
        assertEquals(setOf(1L, 3L), ids(r.markDone).toSet())
    }

    @Test fun `goTo jumps to any photo and out of range is ignored`() {
        val s = ReviewSession(photos(5))
        s.goTo(3)
        assertEquals(4L, s.current!!.id)
        s.goTo(-1); s.goTo(5)
        assertEquals(3, s.index)
    }

    @Test fun `deciding after a jump continues at the next undecided photo`() {
        val s = ReviewSession(photos(5))
        s.decide(Decision.APPROVE); s.decide(Decision.APPROVE) // 1,2 decided, now at 3
        s.goTo(0)
        s.decide(Decision.DELETE) // re-decide photo 1
        assertEquals(2, s.index)  // next undecided is photo 3 (index 2), not photo 2
        assertEquals(listOf(1L), ids(s.plan().toDelete))
        assertEquals(listOf(2L), ids(s.plan().approved))
    }

    @Test fun `next undecided search wraps around`() {
        val s = ReviewSession(photos(4))
        s.goTo(3); s.decide(Decision.APPROVE)
        assertEquals(0, s.index)
    }

    @Test fun `session finishes only when every photo has a decision`() {
        val s = ReviewSession(photos(3))
        s.goTo(2); s.decide(Decision.APPROVE); s.decide(Decision.APPROVE)
        assertFalse(s.finished)
        s.decide(Decision.DELETE)
        assertTrue(s.finished)
    }

    @Test fun `changing a decision flips it between delete and approve`() {
        val s = ReviewSession(photos(2))
        s.decide(Decision.DELETE); s.goTo(0); s.decide(Decision.APPROVE)
        assertEquals(Decision.APPROVE, s.decisionOf(1L))
        assertTrue(s.plan().toDelete.isEmpty())
        assertEquals(listOf(1L), ids(s.plan().approved))
    }

    @Test fun `reopen after finishing keeps all decisions and allows changing them`() {
        val s = ReviewSession(photos(2))
        s.decide(Decision.DELETE); s.decide(Decision.APPROVE)
        assertTrue(s.finished)
        s.reopen()
        assertFalse(s.finished)
        s.goTo(0); s.decide(Decision.APPROVE)
        assertTrue(s.finished)
        assertTrue(s.plan().toDelete.isEmpty())
        assertEquals(2, s.plan().approved.size)
    }

    @Test fun `reopen after skip keeps only the decided photos`() {
        val s = ReviewSession(photos(3))
        s.decide(Decision.DELETE); s.skipRest(); s.reopen()
        assertFalse(s.finished)
        assertEquals(listOf(1L), ids(s.plan().toDelete))
        assertEquals(1, s.decisionsSnapshot().size)
    }

    @Test fun `goTo is ignored once finished`() {
        val s = ReviewSession(photos(3))
        s.skipRest(); s.goTo(2)
        assertEquals(0, s.index)
    }

    @Test fun `reopen of an empty session stays finished`() {
        val s = ReviewSession(emptyList()); s.reopen()
        assertTrue(s.finished)
    }

    @Test fun `every decided photo ends in exactly one of delete or markDone`() {
        val s = ReviewSession(photos(30))
        (1..30).forEach { s.decide(if (it % 3 == 0) Decision.DELETE else Decision.APPROVE) }
        val r = s.plan().resolve(setOf(3L, 6L))
        val all = ids(r.delete) + ids(r.markDone)
        assertEquals(30, all.size)
        assertEquals(30, all.toSet().size)
    }
}
