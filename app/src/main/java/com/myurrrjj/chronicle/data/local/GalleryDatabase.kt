package com.myurrrjj.chronicle.data.local

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.myurrrjj.chronicle.data.local.dao.FaceDao
import com.myurrrjj.chronicle.data.local.dao.FavouriteDao
import com.myurrrjj.chronicle.data.local.dao.LocationDao
import com.myurrrjj.chronicle.data.local.dao.SearchDao
import com.myurrrjj.chronicle.data.local.entity.FaceClusterEntity
import com.myurrrjj.chronicle.data.local.entity.FaceEmbeddingEntity
import com.myurrrjj.chronicle.data.local.entity.FavouriteMediaEntity
import com.myurrrjj.chronicle.data.local.entity.MediaLocationEntity
import com.myurrrjj.chronicle.data.local.entity.MediaPersonEntity
import com.myurrrjj.chronicle.data.local.entity.MediaProcessingStatusEntity
import com.myurrrjj.chronicle.data.local.entity.PlaceCacheEntity
import com.myurrrjj.chronicle.data.local.entity.SearchIndexEntity
import com.myurrrjj.chronicle.data.local.entity.SearchIndexFingerprintEntity

@Database(
    entities = [
        FavouriteMediaEntity::class,
        FaceEmbeddingEntity::class,
        FaceClusterEntity::class,
        MediaPersonEntity::class,
        MediaProcessingStatusEntity::class,
        SearchIndexEntity::class,
        MediaLocationEntity::class,
        PlaceCacheEntity::class,
        SearchIndexFingerprintEntity::class
    ],
    version = 9,
    exportSchema = true,
    autoMigrations = [
        AutoMigration(from = 5, to = 6),
        AutoMigration(from = 6, to = 7),
        AutoMigration(from = 7, to = 8),
        AutoMigration(from = 8, to = 9)
    ]
)
abstract class GalleryDatabase : RoomDatabase() {
    abstract val favouriteDao: FavouriteDao
    abstract val faceDao: FaceDao
    abstract val searchDao: SearchDao
    abstract val locationDao: LocationDao

    companion object {
        @Volatile
        private var INSTANCE: GalleryDatabase? = null

        fun getInstance(context: Context): GalleryDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    GalleryDatabase::class.java,
                    "gallery_db"
                ).build().also { INSTANCE = it }
            }
        }
    }
}
