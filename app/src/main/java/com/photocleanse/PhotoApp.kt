package com.photocleanse

import android.app.Application
import android.os.Build
import androidx.room.Room
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import com.photocleanse.data.AppDatabase
import com.photocleanse.data.MIGRATION_1_2

class PhotoApp : Application(), ImageLoaderFactory {
    val db: AppDatabase by lazy {
        Room.databaseBuilder(this, AppDatabase::class.java, "photocleanse.db")
            .addMigrations(MIGRATION_1_2)
            .build()
    }

    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .components {
            if (Build.VERSION.SDK_INT >= 28) add(ImageDecoderDecoder.Factory()) else add(GifDecoder.Factory())
        }
        .crossfade(false)
        .build()
}
