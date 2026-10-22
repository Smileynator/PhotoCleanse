package com.photocleanse.dupes

import java.io.File
import java.io.InputStream

private const val BUF = 64 * 1024

/** Reads until [buf] is full or the stream ends; returns bytes read. */
private fun InputStream.fill(buf: ByteArray): Int {
    var n = 0
    while (n < buf.size) {
        val r = read(buf, n, buf.size - n)
        if (r < 0) break
        n += r
    }
    return n
}

/** Streaming byte-for-byte comparison. Any I/O problem counts as "not identical". */
fun filesIdentical(pathA: String, pathB: String): Boolean = try {
    val a = File(pathA)
    val b = File(pathB)
    if (a.length() != b.length()) {
        false
    } else {
        a.inputStream().use { x ->
            b.inputStream().use { y ->
                val bx = ByteArray(BUF)
                val by = ByteArray(BUF)
                var same = true
                while (same) {
                    val nx = x.fill(bx)
                    val ny = y.fill(by)
                    if (nx != ny) {
                        same = false
                    } else {
                        for (i in 0 until nx) if (bx[i] != by[i]) { same = false; break }
                        if (nx < BUF) break
                    }
                }
                same
            }
        }
    }
} catch (e: Exception) {
    false
}
