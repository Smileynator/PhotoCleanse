package com.photocleanse

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.photocleanse.review.BATCH_SIZE
import com.photocleanse.review.MAX_BATCH_SIZE
import com.photocleanse.review.MIN_BATCH_SIZE
import com.photocleanse.review.clampBatchSize
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class SettingsTest {
    private val ctx get() = ApplicationProvider.getApplicationContext<Context>()

    @Before fun clear() {
        ctx.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun `default batch size is 30 and limits are 10 to 100`() {
        assertEquals(30, Settings(ctx).batchSize)
        assertEquals(30, BATCH_SIZE)
        assertEquals(10, MIN_BATCH_SIZE)
        assertEquals(100, MAX_BATCH_SIZE)
    }

    @Test fun `batch size is remembered by a new Settings instance`() {
        Settings(ctx).batchSize = 55
        assertEquals(55, Settings(ctx).batchSize)
    }

    @Test fun `out of range values are clamped when saved`() {
        Settings(ctx).batchSize = 5
        assertEquals(10, Settings(ctx).batchSize)
        Settings(ctx).batchSize = 5000
        assertEquals(100, Settings(ctx).batchSize)
        Settings(ctx).batchSize = -3
        assertEquals(10, Settings(ctx).batchSize)
    }

    @Test fun `corrupt stored values are clamped when read`() {
        ctx.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().putInt("batch_size", 99999).commit()
        assertEquals(100, Settings(ctx).batchSize)
        ctx.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().putInt("batch_size", 0).commit()
        assertEquals(10, Settings(ctx).batchSize)
    }

    @Test fun `clamp boundaries`() {
        assertEquals(10, clampBatchSize(10)); assertEquals(100, clampBatchSize(100))
        assertEquals(10, clampBatchSize(9)); assertEquals(100, clampBatchSize(101))
        assertEquals(Int.MAX_VALUE.coerceAtMost(100), clampBatchSize(Int.MAX_VALUE))
        assertEquals(10, clampBatchSize(Int.MIN_VALUE))
    }
}
