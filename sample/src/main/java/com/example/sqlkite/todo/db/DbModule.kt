/*
 * Copyright (C) 2015 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.example.sqlkite.todo.db

import dagger.Provides
import com.frankegan.sqlkite.SqlKite
import timber.log.Timber
import com.frankegan.sqlkite.KiteDatabase
import android.arch.persistence.db.SupportSQLiteOpenHelper
import android.arch.persistence.db.framework.FrameworkSQLiteOpenHelperFactory
import android.content.Context
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
class DbModule {

    @Provides
    @Singleton
    fun provideSqlKite(): SqlKite {
        return SqlKite.Builder()
            .logger { message -> Timber.tag("Database").v(message) }
            .build()
    }

    @Provides
    @Singleton
    fun provideDatabase(
        sqlKite: SqlKite,
        @ApplicationContext application: Context
    ): KiteDatabase {
        val configuration = SupportSQLiteOpenHelper.Configuration.builder(application)
            .name("todo.db")
            .callback(DbCallback())
            .build()
        val factory: SupportSQLiteOpenHelper.Factory = FrameworkSQLiteOpenHelperFactory()
        val helper = factory.create(configuration)
        val db = sqlKite.wrapDatabaseHelper(helper, Dispatchers.IO)
        db.setLoggingEnabled(true)
        return db
    }
}