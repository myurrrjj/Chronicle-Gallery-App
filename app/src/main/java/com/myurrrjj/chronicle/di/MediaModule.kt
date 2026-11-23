package com.myurrrjj.chronicle.di

import android.content.ContentResolver
import android.content.Context
import com.myurrrjj.chronicle.data.local.dao.FavouriteDao
import com.myurrrjj.chronicle.data.local.dao.LocationDao
import com.myurrrjj.chronicle.data.repository.MediaStoreRepositoryImpl
import com.myurrrjj.chronicle.domain.repository.MediaRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import org.maplibre.compose.expressions.dsl.resolvedLocale
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object MediaModule {
    @Provides
    @Singleton
    fun provideContentResolver(@ApplicationContext content: Context): ContentResolver{
        return content.contentResolver

    }


    @Provides
    @Singleton
    fun provideMediaRepository(contentResolver: ContentResolver,favouriteDao: FavouriteDao,locationDao: LocationDao): MediaRepository {
        return MediaStoreRepositoryImpl(contentResolver, favouriteDao = favouriteDao, locationDao = locationDao)

    }
}