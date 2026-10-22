package com.photocleanse.dupes

import java.util.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupingTest {
    private val H = 0x5A5A_1234_ABCD_9876L
    private val T0 = 1_700_000_000_000L

    private fun flip(h: Long, vararg bits: Int) = bits.fold(h) { acc, b -> acc xor (1L shl b) }

    private fun cand(
        id: Long,
        phash: Long = H,
        color: Long = 0x0F0F0F0F0F0FL,
        w: Int = 4000,
        h: Int = 3000,
        size: Long = 4_000_000,
        takenAt: Long = T0,
        sharp: Float = 300f,
        exif: Boolean = true,
        path: String = "/DCIM/Camera/IMG_$id.jpg",
    ) = Candidate(id, path, size, false, w, h, takenAt, phash, color, sharp, exif)

    private fun ids(g: DupGroup) = g.members.map { it.candidate.id }

    // ---- link tiers ----

    @Test fun `identical hash and bytes is EXACT only when bytes really match`() {
        val a = cand(1); val b = cand(2)
        assertEquals(DupKind.EXACT, Grouping.linkKind(a, b) { _, _ -> true })
        assertEquals(DupKind.NEAR, Grouping.linkKind(a, b) { _, _ -> false })
    }

    @Test fun `EXACT requires equal file size`() {
        assertEquals(DupKind.NEAR, Grouping.linkKind(cand(1), cand(2, size = 3_999_999)) { _, _ -> true })
    }

    @Test fun `near threshold boundary`() {
        val a = cand(1)
        assertEquals(DupKind.NEAR, Grouping.linkKind(a, cand(2, phash = flip(H, 1, 2, 3, 4, 5, 6)))) // 6 bits
        // 7 bits is not near, and with a distant timestamp it is not similar either
        assertNull(Grouping.linkKind(a, cand(2, phash = flip(H, 1, 2, 3, 4, 5, 6, 7), takenAt = T0 + 3_600_000)))
    }

    @Test fun `similar needs both a close hash and close timestamps`() {
        val a = cand(1)
        val burst = flip(H, 0, 1, 2, 3, 4, 5, 6, 7, 8, 9)
        assertEquals(DupKind.SIMILAR, Grouping.linkKind(a, cand(2, phash = burst, takenAt = T0 + 5_000)))
        assertNull(Grouping.linkKind(a, cand(2, phash = burst, takenAt = T0 + 31_000)))
        assertNull(Grouping.linkKind(a, cand(2, phash = flip(H, *IntArray(15) { it }), takenAt = T0 + 1_000))) // 15 bits
    }

    @Test fun `different aspect ratio is never a duplicate`() {
        assertNull(Grouping.linkKind(cand(1), cand(2, w = 4000, h = 2250)))
    }

    @Test fun `rotated dimensions are compared after orientation, so portrait vs landscape differ`() {
        assertNull(Grouping.linkKind(cand(1, w = 4000, h = 3000), cand(2, w = 3000, h = 4000)))
    }

    @Test fun `very different colours block a near match`() {
        assertNull(Grouping.linkKind(cand(1), cand(2, color = 0x0F0F0F0F0FL.inv() and 0xFFFFFFFFFFFFL), DupConfig().copy(similarMaxMillis = 0)))
    }

    // ---- clustering ----

    @Test fun `messenger copy of an original forms a near group with the original as best`() {
        val original = cand(1)
        val copy = cand(2, phash = flip(H, 3, 40), w = 1600, h = 1200, size = 250_000, sharp = 120f, exif = false,
            path = "/Pictures/WhatsApp/Media/WhatsApp Images/IMG-20240101-WA0001.jpg", takenAt = T0 + 9_000_000)
        val groups = Grouping.cluster(listOf(copy, original)) // input order must not matter
        assertEquals(1, groups.size)
        assertEquals(listOf(1L, 2L), ids(groups[0]))
        assertEquals(DupKind.NEAR, groups[0].kind)
    }

    @Test fun `burst shots group as SIMILAR and the sharpest one is best`() {
        val shots = (1L..5L).map {
            cand(it, phash = flip(H, (it * 2).toInt(), (it * 2 + 1).toInt()), takenAt = T0 + it * 1_500, sharp = if (it == 3L) 900f else 200f + it)
        }
        val groups = Grouping.cluster(shots)
        assertEquals(1, groups.size)
        assertEquals(5, groups[0].members.size)
        assertEquals(3L, groups[0].members[0].candidate.id)
        assertEquals(DupKind.NEAR, groups[0].kind) // all within 4 bits: tightest tier needed
    }

    @Test fun `group kind is the loosest link used`() {
        val a = cand(1, sharp = 500f)
        val b = cand(2, phash = flip(H, 0, 1, 2, 3, 4, 5, 6, 7, 8, 9), takenAt = T0 + 2_000)
        val groups = Grouping.cluster(listOf(a, b))
        assertEquals(DupKind.SIMILAR, groups.single().kind)
    }

    @Test fun `unrelated photos do not group and singletons are dropped`() {
        val groups = Grouping.cluster(listOf(cand(1), cand(2, phash = H.inv()), cand(3, phash = 0x1111_2222_3333_4444L)))
        assertTrue(groups.isEmpty())
    }

    @Test fun `empty input gives no groups`() = assertTrue(Grouping.cluster(emptyList()).isEmpty())

    @Test fun `chains do not drift into one group`() {
        // A~B (6 bits), B~C (6 other bits), but A and C are 12 bits apart and an hour apart
        val a = cand(1, sharp = 500f)
        val b = cand(2, phash = flip(H, 0, 1, 2, 3, 4, 5), sharp = 300f)
        val c = cand(3, phash = flip(H, 0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11), takenAt = T0 + 3_600_000, sharp = 100f)
        val groups = Grouping.cluster(listOf(a, b, c))
        assertEquals(1, groups.size)
        assertEquals(listOf(1L, 2L), ids(groups[0]))
    }

    @Test fun `identical files are reported EXACT via the byte check`() {
        val groups = Grouping.cluster(listOf(cand(1), cand(2))) { _, _ -> true }
        assertEquals(DupKind.EXACT, groups.single().kind)
    }

    @Test fun `each photo is in at most one group (randomised)`() {
        val rnd = Random(42)
        val bases = List(40) { rnd.nextLong() }
        val cands = (1L..600L).map {
            val base = bases[rnd.nextInt(bases.size)]
            val flips = IntArray(rnd.nextInt(16)) { rnd.nextInt(64) }
            cand(it, phash = flip(base, *flips), takenAt = T0 + rnd.nextInt(120_000), color = rnd.nextLong() and 0xFFFFFFFFFFFFL,
                sharp = rnd.nextInt(1000).toFloat())
        }
        val groups = Grouping.cluster(cands)
        val all = groups.flatMap { ids(it) }
        assertEquals(all.size, all.toSet().size)
        assertTrue(groups.all { it.members.size >= 2 })
        assertTrue(all.all { it in 1L..600L })
        // members are best-first
        assertTrue(groups.all { g -> g.members.zipWithNext().all { (x, y) -> x.score >= y.score } })
    }

    @Test fun `groups are ordered newest first`() {
        val old = listOf(cand(1, phash = 0x1L, takenAt = T0), cand(2, phash = 0x1L, takenAt = T0))
        val new = listOf(cand(3, phash = 0x7FFF_FFFF_0000_0000L, takenAt = T0 + 99_000_000), cand(4, phash = 0x7FFF_FFFF_0000_0000L, takenAt = T0 + 99_000_000))
        val groups = Grouping.cluster(old + new)
        assertEquals(2, groups.size)
        assertTrue(3L in ids(groups[0]))
    }

    // ---- quality ----

    @Test fun `higher resolution beats lower resolution`() {
        assertTrue(Quality.score(cand(1)) > Quality.score(cand(2, w = 1600, h = 1200, size = 300_000)))
    }

    @Test fun `sharper beats blurrier at equal resolution`() {
        assertTrue(Quality.score(cand(1, sharp = 600f)) > Quality.score(cand(2, sharp = 60f)))
    }

    @Test fun `camera original beats messenger copy at equal resolution and size`() {
        val orig = cand(1)
        val copy = cand(2, exif = false, path = "/Pictures/WhatsApp/Media/IMG-20240101-WA0002.jpg")
        assertTrue(Quality.score(orig) > Quality.score(copy))
    }

    @Test fun `less compressed beats more compressed at equal resolution`() {
        assertTrue(Quality.score(cand(1, size = 5_000_000)) > Quality.score(cand(2, size = 900_000)))
    }

    @Test fun `messenger path and name detection`() {
        assertTrue(Quality.looksLikeMessengerCopy("/storage/emulated/0/WhatsApp/Media/x.jpg"))
        assertTrue(Quality.looksLikeMessengerCopy("/storage/emulated/0/Pictures/IMG-20240101-WA0001.jpg"))
        assertTrue(Quality.looksLikeMessengerCopy("C:\\Telegram\\a.jpg"))
        assertFalse(Quality.looksLikeMessengerCopy("/DCIM/Camera/IMG_20240101_120000.jpg"))
    }

    @Test fun `badges explain why a copy is worse and the best has none`() {
        val best = cand(1)
        assertTrue(Quality.badges(best, best).isEmpty())
        val worse = cand(2, w = 1600, h = 1200, size = 200_000, sharp = 50f, exif = false, path = "/WhatsApp/Media/IMG-20240101-WA0002.jpg")
        val b = Quality.badges(worse, best)
        assertTrue(b.any { it.startsWith("Lower resolution") && "1600×1200" in it })
        assertTrue("Blurrier" in b); assertTrue("Smaller file" in b)
        assertTrue("No camera info" in b); assertTrue("Messenger copy" in b)
    }

    @Test fun `same resolution but smaller file is called more compressed`() {
        assertTrue("More compressed" in Quality.badges(cand(2, size = 1_000_000), cand(1)))
    }

    @Test fun `a nearly equal copy still gets an explanation`() {
        assertEquals(listOf("Slightly lower overall score"), Quality.badges(cand(2, size = 3_900_000, sharp = 290f), cand(1)))
    }
}
