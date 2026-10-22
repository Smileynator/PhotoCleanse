package com.photocleanse.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class ProgressTest {
    @Test fun `empty library is zero, not NaN`() {
        assertEquals(0f, progressFraction(0, 0), 0f)
        assertEquals(0, progressPercent(0, 0))
    }

    @Test fun `fraction is done over total`() {
        assertEquals(0.25f, progressFraction(25, 75), 0.0001f)
        assertEquals(1f, progressFraction(10, 0), 0f)
        assertEquals(0f, progressFraction(0, 10), 0f)
    }

    @Test fun `percent never rounds up to 100 while photos remain`() {
        assertEquals(99, progressPercent(9999, 1))
        assertEquals(100, progressPercent(10, 0))
        assertEquals(0, progressPercent(1, 999))
        assertEquals(50, progressPercent(5, 5))
    }

    @Test fun `large counts do not overflow`() {
        assertEquals(50, progressPercent(Int.MAX_VALUE / 2, Int.MAX_VALUE / 2))
    }
}
