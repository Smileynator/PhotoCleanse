package com.photocleanse.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Test

class ZoomMathTest {
    private val size = IntSize(1000, 2000)

    @Test fun `no zoom keeps offset at zero even with pan`() {
        assertEquals(Offset.Zero, nextOffset(Offset.Zero, 1f, 1f, Offset(100f, 100f), Offset(50f, 50f), size))
    }

    @Test fun `zooming about the centre does not move the image`() {
        assertEquals(Offset.Zero, nextOffset(Offset.Zero, 1f, 2f, Offset(500f, 1000f), Offset.Zero, size))
    }

    @Test fun `zooming into a corner keeps that point under the finger`() {
        val c = Offset(0f, 0f)
        val o = nextOffset(Offset.Zero, 1f, 2f, c, Offset.Zero, size)
        // content point under finger before: q = c; after: center + o + 2*(q-center) must equal c
        val center = Offset(500f, 1000f)
        assertEquals(c, center + o + (c - center) * 2f)
    }

    @Test fun `offset is clamped so the image edge never leaves the screen`() {
        val o = nextOffset(Offset.Zero, 2f, 2f, Offset(500f, 1000f), Offset(9999f, -9999f), size)
        assertEquals(Offset(500f, -1000f), o)
    }

    @Test fun `zooming out to 1 recentres`() {
        assertEquals(Offset.Zero, nextOffset(Offset(300f, 300f), 2f, 1f, Offset(500f, 1000f), Offset.Zero, size))
    }
}
