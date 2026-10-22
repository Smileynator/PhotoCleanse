package com.photocleanse.dupes

import java.util.Random
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Plain RGB raster (java.awt is not available to Android unit tests, so everything is done on arrays). */
class Img(val w: Int, val h: Int, val px: IntArray = IntArray(w * h)) {
    operator fun get(x: Int, y: Int) = px[y * w + x]
    operator fun set(x: Int, y: Int, v: Int) { px[y * w + x] = v }
}

fun rgb(r: Int, g: Int, b: Int) = (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)
private fun red(c: Int) = (c shr 16) and 0xFF
private fun grn(c: Int) = (c shr 8) and 0xFF
private fun blu(c: Int) = c and 0xFF

/** Synthetic "photos" (large soft shapes on a gradient, plus sensor-like noise) and typical transformations. */
object TestImages {
    fun scene(seed: Long, w: Int = 1024, h: Int = 768): Img {
        val rnd = Random(seed)
        val img = Img(w, h)
        val c0 = IntArray(3) { rnd.nextInt(256) }
        val c1 = IntArray(3) { rnd.nextInt(256) }
        for (y in 0 until h) for (x in 0 until w) {
            val t = (x.toFloat() / w + y.toFloat() / h) / 2f
            img[x, y] = rgb(
                (c0[0] + (c1[0] - c0[0]) * t).toInt(), (c0[1] + (c1[1] - c0[1]) * t).toInt(), (c0[2] + (c1[2] - c0[2]) * t).toInt(),
            )
        }
        repeat(14) {
            val col = rgb(rnd.nextInt(256), rnd.nextInt(256), rnd.nextInt(256))
            val sw = w / 6 + rnd.nextInt(w / 3)
            val sh = h / 6 + rnd.nextInt(h / 3)
            val x0 = rnd.nextInt(w - sw)
            val y0 = rnd.nextInt(h - sh)
            val oval = rnd.nextBoolean()
            for (y in y0 until y0 + sh) for (x in x0 until x0 + sw) {
                if (oval) {
                    val nx = (x - x0 - sw / 2f) / (sw / 2f)
                    val ny = (y - y0 - sh / 2f) / (sh / 2f)
                    if (nx * nx + ny * ny > 1f) continue
                }
                img[x, y] = col
            }
        }
        for (i in 0 until w * h step 3) { // light noise
            val n = rnd.nextInt(17) - 8
            val c = img.px[i]
            img.px[i] = rgb(red(c) + n, grn(c) + n, blu(c) + n)
        }
        return img
    }

    /** Area-average resampling. */
    fun scale(src: Img, w: Int, h: Int): Img {
        val out = Img(w, h)
        for (y in 0 until h) for (x in 0 until w) {
            val x0 = x * src.w / w; val x1 = maxOf(x0 + 1, (x + 1) * src.w / w)
            val y0 = y * src.h / h; val y1 = maxOf(y0 + 1, (y + 1) * src.h / h)
            var r = 0; var g = 0; var b = 0; var n = 0
            for (yy in y0 until y1) for (xx in x0 until x1) {
                val c = src[xx, yy]; r += red(c); g += grn(c); b += blu(c); n++
            }
            out[x, y] = rgb(r / n, g / n, b / n)
        }
        return out
    }

    private val lumaQuant = intArrayOf(
        16, 11, 10, 16, 24, 40, 51, 61, 12, 12, 14, 19, 26, 58, 60, 55, 14, 13, 16, 24, 40, 57, 69, 56, 14, 17, 22, 29, 51, 87, 80, 62,
        18, 22, 37, 56, 68, 109, 103, 77, 24, 35, 55, 64, 81, 104, 113, 92, 49, 64, 78, 87, 103, 121, 120, 101, 72, 92, 95, 98, 112, 100, 103, 99,
    )
    private val dct = Array(8) { u -> DoubleArray(8) { x -> (if (u == 0) sqrt(1.0 / 8) else sqrt(2.0 / 8)) * cos((2 * x + 1) * u * PI / 16) } }

    /** JPEG-style lossy compression: 8x8 block DCT with quantisation at the given quality (1..100), on YCbCr. */
    fun jpegLike(src: Img, quality: Int): Img {
        val s = if (quality < 50) 5000 / quality else 200 - 2 * quality
        val q = IntArray(64) { maxOf(1, (lumaQuant[it] * s + 50) / 100) }
        val chan = Array(3) { DoubleArray(src.w * src.h) }
        for (i in src.px.indices) {
            val c = src.px[i]; val r = red(c).toDouble(); val g = grn(c).toDouble(); val b = blu(c).toDouble()
            chan[0][i] = 0.299 * r + 0.587 * g + 0.114 * b
            chan[1][i] = -0.1687 * r - 0.3313 * g + 0.5 * b
            chan[2][i] = 0.5 * r - 0.4187 * g - 0.0813 * b
        }
        for (ch in chan) {
            for (by in 0 until src.h / 8) for (bx in 0 until src.w / 8) {
                val blk = Array(8) { y -> DoubleArray(8) { x -> ch[(by * 8 + y) * src.w + bx * 8 + x] } }
                val tmp = Array(8) { DoubleArray(8) }
                val coef = Array(8) { DoubleArray(8) }
                for (y in 0 until 8) for (u in 0 until 8) { var a = 0.0; for (x in 0 until 8) a += blk[y][x] * dct[u][x]; tmp[y][u] = a }
                for (v in 0 until 8) for (u in 0 until 8) {
                    var a = 0.0; for (y in 0 until 8) a += tmp[y][u] * dct[v][y]
                    coef[v][u] = Math.round(a / q[v * 8 + u]) * q[v * 8 + u].toDouble()
                }
                for (y in 0 until 8) for (x in 0 until 8) { var a = 0.0; for (u in 0 until 8) a += coef[y][u] * dct[u][x]; tmp[y][x] = a }
                for (y in 0 until 8) for (x in 0 until 8) {
                    var a = 0.0; for (v in 0 until 8) a += tmp[v][x] * dct[v][y]
                    ch[(by * 8 + y) * src.w + bx * 8 + x] = a
                }
            }
        }
        val out = Img(src.w, src.h)
        for (i in out.px.indices) {
            val yv = chan[0][i]; val cb = chan[1][i]; val cr = chan[2][i]
            out.px[i] = rgb((yv + 1.402 * cr).roundToInt(), (yv - 0.34414 * cb - 0.71414 * cr).roundToInt(), (yv + 1.772 * cb).roundToInt())
        }
        return out
    }

    fun brighten(src: Img, delta: Int): Img =
        Img(src.w, src.h, IntArray(src.px.size) { val c = src.px[it]; rgb(red(c) + delta, grn(c) + delta, blu(c) + delta) })

    /** Camera moved a little between two shots of the same subject. */
    fun shift(src: Img, dx: Int, dy: Int): Img {
        val out = Img(src.w, src.h)
        for (y in 0 until src.h) for (x in 0 until src.w) out[x, y] = src[(x - dx).coerceIn(0, src.w - 1), (y - dy).coerceIn(0, src.h - 1)]
        return out
    }

    fun blur(src: Img, radius: Int, passes: Int = 1): Img {
        var cur = src
        repeat(passes) {
            val out = Img(cur.w, cur.h)
            for (y in 0 until cur.h) for (x in 0 until cur.w) {
                var r = 0; var g = 0; var b = 0; var n = 0
                for (dy in -radius..radius) for (dx in -radius..radius) {
                    val c = cur[(x + dx).coerceIn(0, cur.w - 1), (y + dy).coerceIn(0, cur.h - 1)]
                    r += red(c); g += grn(c); b += blu(c); n++
                }
                out[x, y] = rgb(r / n, g / n, b / n)
            }
            cur = out
        }
        return cur
    }

    /** What the Android decoder hands to [Perceptual.compute]: 128x128, aspect squashed. */
    fun thumb(src: Img): IntArray = scale(src, Perceptual.SRC, Perceptual.SRC).px.map { 0xFF000000.toInt() or it }.toIntArray()

    fun features(src: Img, orientation: Int = 1) = Perceptual.compute(thumb(src), orientation)
}
