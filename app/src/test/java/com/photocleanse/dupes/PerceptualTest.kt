package com.photocleanse.dupes

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PerceptualTest {
    private val cfg = DupConfig()
    private val base = TestImages.scene(1)
    private val baseF = TestImages.features(base)

    private fun hd(f: Perceptual.Result) = Perceptual.hamming(baseF.phash, f.phash)
    private fun cd(f: Perceptual.Result) = Perceptual.hamming(baseF.colorSig, f.colorSig)

    @Test fun `same image gives identical fingerprint`() {
        val f = TestImages.features(TestImages.scene(1))
        assertEquals(baseF.phash, f.phash)
        assertEquals(baseF.colorSig, f.colorSig)
    }

    @Test fun `downscaled and recompressed copy (messenger style) is a near duplicate`() {
        val copy = TestImages.jpegLike(TestImages.scale(base, 640, 480), 55)
        val f = TestImages.features(copy)
        println("messenger copy: hash=${hd(f)} color=${cd(f)}")
        assertTrue(hd(f) <= cfg.nearMaxHamming)
        assertTrue(cd(f) <= cfg.nearMaxColorDiff)
    }

    @Test fun `heavily recompressed copy is still near`() {
        val f = TestImages.features(TestImages.jpegLike(base, 20))
        println("q20 jpeg: hash=${hd(f)} color=${cd(f)}")
        assertTrue(hd(f) <= cfg.nearMaxHamming)
    }

    @Test fun `exposure change keeps the hash close`() {
        for (delta in listOf(-25, 25)) {
            val f = TestImages.features(TestImages.brighten(base, delta))
            println("brightness $delta: hash=${hd(f)} color=${cd(f)}")
            assertTrue(hd(f) <= cfg.nearMaxHamming)
        }
    }

    @Test fun `small camera movement between burst shots stays within the similar threshold`() {
        for ((dx, dy) in listOf(6 to 4, 12 to 8)) {
            val f = TestImages.features(TestImages.shift(base, dx, dy))
            println("shift $dx,$dy: hash=${hd(f)} color=${cd(f)}")
            assertTrue(hd(f) <= cfg.similarMaxHamming)
            assertTrue(cd(f) <= cfg.similarMaxColorDiff)
        }
    }

    @Test fun `unrelated photos are far apart`() {
        for (seed in 2L..25L) {
            val f = TestImages.features(TestImages.scene(seed))
            assertTrue("seed $seed hash distance ${hd(f)}", hd(f) > cfg.similarMaxHamming)
        }
    }

    @Test fun `blurred copy has lower sharpness than the original`() {
        val blurred = TestImages.features(TestImages.blur(base, 3, 2))
        assertTrue("${blurred.sharpness} < ${baseF.sharpness}", blurred.sharpness < baseF.sharpness * 0.5f)
    }

    @Test fun `blurred copy is still recognised as the same photo`() {
        val f = TestImages.features(TestImages.blur(base, 2))
        assertTrue(hd(f) <= cfg.similarMaxHamming)
    }

    @Test fun `orient is undone by the inverse orientation`() {
        val n = 4
        val a = IntArray(n * n) { it }
        // 2,4,5,7 are their own inverse; 3 too; 6 and 8 are inverse of each other
        for (o in listOf(2, 3, 4, 5, 7)) assertArrayEquals("orientation $o", a, Perceptual.orient(Perceptual.orient(a, n, o), n, o))
        assertArrayEquals(a, Perceptual.orient(Perceptual.orient(a, n, 6), n, 8))
        assertArrayEquals(a, Perceptual.orient(Perceptual.orient(a, n, 8), n, 6))
    }

    @Test fun `orient 6 rotates clockwise`() {
        // 2x2: [0 1; 2 3] rotated clockwise -> [2 0; 3 1]
        assertArrayEquals(intArrayOf(2, 0, 3, 1), Perceptual.orient(intArrayOf(0, 1, 2, 3), 2, 6))
        // and counter-clockwise for 8 -> [1 3; 0 2]
        assertArrayEquals(intArrayOf(1, 3, 0, 2), Perceptual.orient(intArrayOf(0, 1, 2, 3), 2, 8))
    }

    @Test fun `orient 1 and invalid values leave pixels alone`() {
        val a = intArrayOf(1, 2, 3, 4)
        assertArrayEquals(a, Perceptual.orient(a, 2, 1))
        assertArrayEquals(a, Perceptual.orient(a, 2, 0))
        assertArrayEquals(a, Perceptual.orient(a, 2, 9))
    }

    @Test fun `a photo stored sideways with EXIF orientation hashes like the upright photo`() {
        val upright = TestImages.thumb(base)
        // what a camera stores for EXIF orientation 6: upright image rotated counter-clockwise
        val stored = Perceptual.orient(upright, Perceptual.SRC, 8)
        val f = Perceptual.compute(stored, 6)
        assertEquals(baseF.phash, f.phash)
        assertEquals(baseF.colorSig, f.colorSig)
        // without applying the orientation the hash would differ
        val naive = Perceptual.compute(stored, 1)
        assertTrue(Perceptual.hamming(baseF.phash, naive.phash) > cfg.similarMaxHamming)
    }

    @Test fun `compute rejects wrongly sized input`() {
        var thrown = false
        try { Perceptual.compute(IntArray(10)) } catch (e: IllegalArgumentException) { thrown = true }
        assertTrue(thrown)
    }

    @Test fun `flat image does not crash and has zero sharpness`() {
        val f = Perceptual.compute(IntArray(Perceptual.SRC * Perceptual.SRC) { 0xFF808080.toInt() })
        assertEquals(0f, f.sharpness, 0.0001f)
    }
}

