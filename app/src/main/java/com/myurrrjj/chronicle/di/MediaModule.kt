package com.myurrrjj.chronicle.di

import android.content.ContentResolver
import android.content.Context
import com.myurrrjj.chronicle.core.util.FaceCropLocator
import com.myurrrjj.chronicle.data.local.dao.FaceDao
import com.myurrrjj.chronicle.data.local.dao.FavouriteDao
import com.myurrrjj.chronicle.data.local.dao.LocationDao
import com.myurrrjj.chronicle.data.repository.MediaStoreRepositoryImpl
import com.myurrrjj.chronicle.domain.repository.MediaRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object MediaModule {

    @Provides
    @Singleton
    fun provideContentResolver(
        @ApplicationContext context: Context
    ): ContentResolver = context.contentResolver

    @Provides
    @Singleton
    fun provideMediaRepository(
        @ApplicationContext context: Context,
        contentResolver: ContentResolver,
        favouriteDao: FavouriteDao,
        locationDao: LocationDao,
        faceDao: FaceDao,
        faceCropLocator: FaceCropLocator
    ): MediaRepository = MediaStoreRepositoryImpl(
        appContext = context,
        contentResolver = contentResolver,
        favouriteDao = favouriteDao,
        locationDao = locationDao,
        faceDao = faceDao,
        faceCropLocator = faceCropLocator
    )
}