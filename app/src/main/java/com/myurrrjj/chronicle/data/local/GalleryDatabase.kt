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

@Database(
    entities = [
        FavouriteMediaEntity::class,
        FaceEmbeddingEntity::class,
        FaceClusterEntity::class,
        MediaPersonEntity::class,
        MediaProcessingStatusEntity::class,
        SearchIndexEntity::class,
        MediaLocationEntity::class,
        PlaceCacheEntity::class
    ],
    version = 6,
    exportSchema = true,
    autoMigrations = [
        AutoMigration(from = 5, to = 6)
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
                )
//                    .fallbackToDestructiveMigration(dropAllTables = true)
                    .build()
                    .also {
                        INSTANCE = it
                    }
            }
        }
    }
}