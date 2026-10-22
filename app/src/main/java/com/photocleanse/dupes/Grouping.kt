package com.photocleanse.dupes

import androidx.room.ColumnInfo
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/** Everything needed to compare one photo. Room maps the `candidates` query straight onto this. */
data class Candidate(
    val id: Long,
    val path: String,
    val size: Long,
    val hidden: Boolean,
    val width: Int,
    val height: Int,
    val takenAt: Long,
    val phash: Long,
    val colorSig: Long,
    val sharpness: Float,
    @ColumnInfo(name = "hasCameraExif") val hasCameraExif: Boolean,
)

/** Ordered strongest to loosest; a group's kind is the loosest link that was needed to build it. */
enum class DupKind { EXACT, NEAR, SIMILAR }

data class DupConfig(
    val nearMaxHamming: Int = 6,
    val similarMaxHamming: Int = 14,
    val similarMaxMillis: Long = 30_000,
    val nearMaxColorDiff: Int = 10,
    val similarMaxColorDiff: Int = 16,
    val maxAspectDrift: Double = 0.08,
)

data class ScoredMember(val candidate: Candidate, val score: Float)
data class DupGroup(val kind: DupKind, val members: List<ScoredMember>) // members best-first

object Quality {
    /** Higher is better. Resolution dominates, then sharpness, then compression level, then provenance hints. */
    fun score(c: Candidate): Float {
        val pixels = max(1L, c.width.toLong() * c.height)
        val megapixels = pixels / 1_000_000.0
        val bytesPerPixel = c.size.toDouble() / pixels
        var s = 100 * ln(1 + megapixels) +
            20 * ln(1 + max(0f, c.sharpness).toDouble()) +
            30 * ln(1 + bytesPerPixel * 10)
        if (c.hasCameraExif) s += 15
        if (looksLikeMessengerCopy(c.path)) s -= 25
        return s.toFloat()
    }

    private val whatsappName = Regex("^(img|vid)-\\d{8}-wa\\d+", RegexOption.IGNORE_CASE)

    fun looksLikeMessengerCopy(path: String): Boolean {
        val p = path.replace('\\', '/').lowercase()
        val name = p.substringAfterLast('/')
        return "/whatsapp/" in p || "/telegram/" in p || "/messenger/" in p || whatsappName.containsMatchIn(name)
    }

    /** Human-readable reasons why [c] is worse than [best]. Empty for the best copy itself. */
    fun badges(c: Candidate, best: Candidate): List<String> {
        if (c.id == best.id) return emptyList()
        val out = ArrayList<String>()
        val px = c.width.toLong() * c.height
        val bestPx = best.width.toLong() * best.height
        val lowerRes = px < bestPx * 0.8
        if (lowerRes) out += "Lower resolution (${c.width}×${c.height})"
        if (best.sharpness > 0 && c.sharpness < best.sharpness * 0.7f) out += "Blurrier"
        if (c.size < best.size * 0.6) out += if (lowerRes) "Smaller file" else "More compressed"
        if (!c.hasCameraExif && best.hasCameraExif) out += "No camera info"
        if (looksLikeMessengerCopy(c.path) && !looksLikeMessengerCopy(best.path)) out += "Messenger copy"
        if (out.isEmpty()) out += "Slightly lower overall score"
        return out
    }
}

object Grouping {
    private fun aspectDrift(a: Candidate, b: Candidate): Double {
        if (a.width <= 0 || a.height <= 0 || b.width <= 0 || b.height <= 0) return 0.0
        return abs(ln(a.width.toDouble() / a.height) - ln(b.width.toDouble() / b.height))
    }

    /** How strongly [a] and [b] are linked, or null when they are not duplicates. [identical] confirms byte equality. */
    fun linkKind(
        a: Candidate,
        b: Candidate,
        cfg: DupConfig = DupConfig(),
        identical: (Candidate, Candidate) -> Boolean = { _, _ -> false },
    ): DupKind? {
        val d = Perceptual.hamming(a.phash, b.phash)
        if (d > cfg.similarMaxHamming) return null
        if (aspectDrift(a, b) > cfg.maxAspectDrift) return null
        val cd = Perceptual.hamming(a.colorSig, b.colorSig)
        if (a.size == b.size && d == 0 && cd == 0 && identical(a, b)) return DupKind.EXACT
        if (d <= cfg.nearMaxHamming && cd <= cfg.nearMaxColorDiff) return DupKind.NEAR
        if (abs(a.takenAt - b.takenAt) <= cfg.similarMaxMillis && cd <= cfg.similarMaxColorDiff) return DupKind.SIMILAR
        return null
    }

    private class Builder(val rep: ScoredMember) {
        val members = arrayListOf(rep)
        var kind = DupKind.EXACT
    }

    /**
     * Greedy clustering around the best-scored photo of each group. A photo only joins a group if it is linked to
     * that group's best photo, so chains (A~B, B~C, A!~C) cannot drift into one big group.
     * Groups with a single photo are dropped. Result is ordered newest-first by the group's best photo.
     */
    fun cluster(
        candidates: List<Candidate>,
        cfg: DupConfig = DupConfig(),
        identical: (Candidate, Candidate) -> Boolean = { _, _ -> false },
    ): List<DupGroup> {
        val scored = candidates.map { ScoredMember(it, Quality.score(it)) }
            .sortedWith(compareByDescending<ScoredMember> { it.score }.thenBy { it.candidate.id })
        val groups = ArrayList<Builder>()
        for (s in scored) {
            var target: Builder? = null
            var targetKind = DupKind.SIMILAR
            var targetDist = Int.MAX_VALUE
            for (g in groups) {
                val d = Perceptual.hamming(g.rep.candidate.phash, s.candidate.phash)
                if (d > cfg.similarMaxHamming) continue
                val kind = linkKind(g.rep.candidate, s.candidate, cfg, identical) ?: continue
                if (target == null || kind < targetKind || (kind == targetKind && d < targetDist)) {
                    target = g; targetKind = kind; targetDist = d
                }
            }
            if (target == null) {
                groups += Builder(s)
            } else {
                target.members += s
                if (targetKind > target.kind) target.kind = targetKind
            }
        }
        return groups.filter { it.members.size >= 2 }
            .sortedByDescending { it.rep.candidate.takenAt }
            .map { DupGroup(it.kind, it.members) }
    }
}
