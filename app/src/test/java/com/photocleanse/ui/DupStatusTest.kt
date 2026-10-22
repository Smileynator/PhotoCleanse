package com.photocleanse.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class DupStatusTest {
    @Test fun `file scan has priority, then analysis, then empty result`() {
        assertEquals(DupStatus.WaitingForScan, dupStatus(scanRunning = true, analysisRunning = false, groups = 3))
        assertEquals(DupStatus.WaitingForScan, dupStatus(scanRunning = true, analysisRunning = true, groups = 0))
        assertEquals(DupStatus.Analysing, dupStatus(scanRunning = false, analysisRunning = true, groups = 0))
        assertEquals(DupStatus.Analysing, dupStatus(scanRunning = false, analysisRunning = true, groups = 5))
    }

    @Test fun `finished with nothing found is distinct from not finished`() {
        assertEquals(DupStatus.NoneFound, dupStatus(scanRunning = false, analysisRunning = false, groups = 0))
        assertEquals(DupStatus.Ready, dupStatus(scanRunning = false, analysisRunning = false, groups = 1))
    }
}
