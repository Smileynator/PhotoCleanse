package com.photocleanse.ui

/** What the duplicates card should tell the user. */
enum class DupStatus { WaitingForScan, Analysing, NoneFound, Ready }

fun dupStatus(scanRunning: Boolean, analysisRunning: Boolean, groups: Int): DupStatus = when {
    scanRunning -> DupStatus.WaitingForScan
    analysisRunning -> DupStatus.Analysing
    groups == 0 -> DupStatus.NoneFound
    else -> DupStatus.Ready
}
