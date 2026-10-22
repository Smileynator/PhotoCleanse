package com.photocleanse.dupes

import com.photocleanse.data.DupRow

fun DupRow.toCandidate() = Candidate(
    id = photo.id, path = photo.path, size = photo.size, hidden = photo.hidden, width = width, height = height,
    takenAt = photo.modified, phash = 0, colorSig = 0, sharpness = sharpness, hasCameraExif = hasCameraExif,
)

data class BatchGroup(val groupId: Long, val kind: DupKind, val rows: List<DupRow>) // rows best-first

/**
 * Turns the flat, group-ordered rows of the `dupRows` query into one review batch: whole groups are added until
 * [maxPhotos] would be exceeded (the first group is always taken, however large). Groups with a hidden-folder
 * member are skipped entirely unless [includeHidden]; groups with fewer than two photos left are skipped.
 */
fun buildDupBatch(rows: List<DupRow>, includeHidden: Boolean, maxPhotos: Int = 30): List<BatchGroup> {
    val groups = rows.groupBy { it.groupId }.values
        .filter { it.size >= 2 && (includeHidden || it.none { r -> r.photo.hidden }) }
    val out = ArrayList<BatchGroup>()
    var total = 0
    for (g in groups) {
        if (out.isNotEmpty() && total + g.size > maxPhotos) break
        out += BatchGroup(g[0].groupId, DupKind.entries[g[0].kind], g)
        total += g.size
    }
    return out
}

/** Group ids for which every member listed in [groupOf] is in [deleteIds] (no copy would survive). */
fun fullyDeletedGroups(groupOf: Map<Long, Long>, deleteIds: Set<Long>): List<Long> =
    groupOf.entries.groupBy({ it.value }, { it.key })
        .filterValues { members -> members.all { it in deleteIds } }
        .keys.toList()
