package com.myurrrjj.chronicle.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import com.myurrrjj.chronicle.data.repository.SearchRepositoryImpl
import com.myurrrjj.chronicle.di.qualifiers.SearchIndexStore
import com.myurrrjj.chronicle.domain.repository.SearchRepository
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton


@Module
@InstallIn(SingletonComponent::class)
abstract class SearchRepositoryModule {
    @Binds
    @Singleton
    abstract fun bindSearchRepository(
        searchRepositoryImpl: SearchRepositoryImpl
    ): SearchRepository

}

@Module
@InstallIn(SingletonComponent::class)
object SearchIndexStoreModule {
    @Provides
    @Singleton
    @SearchIndexStore
    fun provideSearchIndexStore(
        @ApplicationContext context: Context
    ): DataStore<Preferences> {

        return PreferenceDataStoreFactory.create(
            produceFile = { context.preferencesDataStoreFile("search_index_state") }

        )
    }
}