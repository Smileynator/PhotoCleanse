package com.photocleanse.dupes

import kotlin.math.PI
import kotlin.math.cos

/**
 * Pure image fingerprinting. Input is a 128x128 ARGB thumbnail (aspect ratio squashed), so it is JVM-testable
 * and independent of Bitmap. The EXIF [orientation] is applied first so a rotated copy hashes like the upright one.
 */
object Perceptual {
    const val SRC = 128
    private const val DCT = 32
    private const val KEEP = 8

    class Result(val phash: Long, val colorSig: Long, val sharpness: Float)

    fun hamming(a: Long, b: Long): Int = java.lang.Long.bitCount(a xor b)

    /** Rearranges a square image according to EXIF orientation 1..8 (see ExifInterface.ORIENTATION_*). */
    fun orient(src: IntArray, n: Int, orientation: Int): IntArray {
        if (orientation !in 2..8) return src
        val dst = IntArray(n * n)
        val m = n - 1
        for (y in 0 until n) for (x in 0 until n) {
            val sx = when (orientation) {
                2, 3 -> m - x
                5, 6 -> y
                7, 8 -> m - y
                else -> x
            }
            val sy = when (orientation) {
                3, 4 -> m - y
                5, 8 -> x
                6 -> m - x
                7 -> m - x
                else -> y
            }
            dst[y * n + x] = src[sy * n + sx]
        }
        return dst
    }

    fun compute(argb: IntArray, orientation: Int = 1): Result {
        require(argb.size == SRC * SRC) { "expected ${SRC}x$SRC pixels" }
        val px = orient(argb, SRC, orientation)

        val gray = FloatArray(SRC * SRC)
        for (i in px.indices) gray[i] = luma(px[i])
        val sharpness = laplacianVariance(gray, SRC)

        val f = SRC / DCT
        val g = FloatArray(DCT * DCT)
        val r = FloatArray(DCT * DCT)
        val gg = FloatArray(DCT * DCT)
        val b = FloatArray(DCT * DCT)
        val area = (f * f).toFloat()
        for (y in 0 until DCT) for (x in 0 until DCT) {
            var sg = 0f; var sr = 0f; var sgg = 0f; var sb = 0f
            for (dy in 0 until f) for (dx in 0 until f) {
                val p = px[(y * f + dy) * SRC + x * f + dx]
                sg += gray[(y * f + dy) * SRC + x * f + dx]
                sr += (p shr 16) and 0xFF; sgg += (p shr 8) and 0xFF; sb += p and 0xFF
            }
            val i = y * DCT + x
            g[i] = sg / area; r[i] = sr / area; gg[i] = sgg / area; b[i] = sb / area
        }
        return Result(phash(g), colorSignature(r, gg, b), sharpness)
    }

    private fun luma(p: Int): Float =
        0.299f * ((p shr 16) and 0xFF) + 0.587f * ((p shr 8) and 0xFF) + 0.114f * (p and 0xFF)

    private val cosTable = Array(KEEP) { u -> FloatArray(DCT) { x -> cos((2 * x + 1) * u * PI / (2 * DCT)).toFloat() } }

    /** 64-bit DCT hash: low-frequency 8x8 block, each coefficient (except DC) compared to the median. */
    private fun phash(g: FloatArray): Long {
        val tmp = FloatArray(DCT * KEEP)
        for (y in 0 until DCT) for (u in 0 until KEEP) {
            var s = 0f
            for (x in 0 until DCT) s += g[y * DCT + x] * cosTable[u][x]
            tmp[y * KEEP + u] = s
        }
        val coef = FloatArray(KEEP * KEEP)
        for (v in 0 until KEEP) for (u in 0 until KEEP) {
            var s = 0f
            for (y in 0 until DCT) s += tmp[y * KEEP + u] * cosTable[v][y]
            coef[v * KEEP + u] = s
        }
        val sorted = coef.copyOfRange(1, coef.size).also { it.sort() }
        val median = sorted[sorted.size / 2]
        var hash = 0L
        for (i in 1 until coef.size) if (coef[i] > median) hash = hash or (1L shl i)
        return hash
    }

    /** 4x4 grid x 3 channels: bit set when the cell is brighter than that channel's average (48 bits). */
    private fun colorSignature(r: FloatArray, g: FloatArray, b: FloatArray): Long {
        val cell = DCT / 4
        val means = Array(3) { FloatArray(16) }
        val src = arrayOf(r, g, b)
        for (c in 0 until 3) for (cy in 0 until 4) for (cx in 0 until 4) {
            var s = 0f
            for (y in 0 until cell) for (x in 0 until cell) s += src[c][(cy * cell + y) * DCT + cx * cell + x]
            means[c][cy * 4 + cx] = s / (cell * cell)
        }
        var sig = 0L
        for (c in 0 until 3) {
            val avg = means[c].average().toFloat()
            for (i in 0 until 16) if (means[c][i] > avg) sig = sig or (1L shl (i * 3 + c))
        }
        return sig
    }

    /** Variance of the Laplacian: high for crisp images, low for blurry ones. */
    fun laplacianVariance(gray: FloatArray, n: Int): Float {
        var sum = 0.0
        var sumSq = 0.0
        var count = 0
        for (y in 1 until n - 1) for (x in 1 until n - 1) {
            val i = y * n + x
            val lap = 4 * gray[i] - gray[i - 1] - gray[i + 1] - gray[i - n] - gray[i + n]
            sum += lap; sumSq += lap * lap; count++
        }
        val mean = sum / count
        return (sumSq / count - mean * mean).toFloat()
    }
}
