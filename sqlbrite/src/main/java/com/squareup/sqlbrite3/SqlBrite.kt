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
package com.squareup.sqlbrite3

import android.arch.persistence.db.SupportSQLiteOpenHelper
import android.content.ContentResolver
import android.database.Cursor
import android.support.annotation.CheckResult
import android.support.annotation.WorkerThread
import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.util.concurrent.Executor

/**
 * A lightweight wrapper around [SupportSQLiteOpenHelper] which allows for continuously
 * observing the result of a query.
 */
class SqlBrite internal constructor(
    private val logger: Logger,
    private val queryTransformer: (Flow<Query>) -> Flow<Query>
) {

    class Builder {

        private var logger = DEFAULT_LOGGER
        private var queryTransformer = DEFAULT_TRANSFORMER

        @CheckResult
        fun logger(logger: Logger): Builder {
            this.logger = logger
            return this
        }

        @CheckResult
        fun queryTransformer(queryTransformer: (Flow<Query>) -> Flow<Query>): Builder {
            this.queryTransformer = queryTransformer
            return this
        }

        @CheckResult
        fun build(): SqlBrite {
            return SqlBrite(logger, queryTransformer)
        }
    }

    /**
     * Wrap a [SupportSQLiteOpenHelper] for observable queries.
     *
     *
     * While not strictly required, instances of this class assume that they will be the only ones
     * interacting with the underlying [SupportSQLiteOpenHelper] and it is required for
     * automatic notifications of table changes to work. See [the][BriteDatabase.createQuery] for more information on that behavior.
     *
     * @param dispatcher The [CoroutineDispatcher] on which items from [BriteDatabase.createQuery]
     * will be emitted.
     */
    @CheckResult
    fun wrapDatabaseHelper(
        helper: SupportSQLiteOpenHelper,
        dispatcher: CoroutineDispatcher,
        transactionExecutor: Executor? = null
    ): BriteDatabase {
        return if (transactionExecutor == null) {
            BriteDatabase(helper, logger, dispatcher, queryTransformer = queryTransformer)
        } else {
            BriteDatabase(helper, logger, dispatcher, transactionExecutor, queryTransformer)
        }
    }

    /**
     * Wrap a [ContentResolver] for observable queries.
     *
     * @param dispatcher The [CoroutineDispatcher] on which items from
     * [BriteContentResolver.createQuery] will be emitted.
     */
    @CheckResult
    fun wrapContentProvider(
        contentResolver: ContentResolver,
        dispatcher: CoroutineDispatcher
    ): BriteContentResolver {
        return BriteContentResolver(contentResolver, logger, dispatcher, queryTransformer)
    }

    /** An executable query.  */
    abstract class Query {

        /**
         * Execute the query on the underlying database and return the resulting cursor.
         *
         * @return A [Cursor] with query results, or `null` when the query could not be
         * executed due to a problem with the underlying store. Unfortunately it is not well documented
         * when `null` is returned. It usually involves a problem in communicating with the
         * underlying store and should either be treated as failure or ignored for retry at a later
         * time.
         */
        @CheckResult
        @WorkerThread
        abstract suspend fun runQuery(): Cursor?

        /**
         * Execute the query on the underlying database and return an Observable of each row mapped to
         * `T` by `mapper`.
         *
         *
         * Standard usage of this operation is in `flatMap`:
         * <pre>`flatMap(q -> q.asRows(Item.MAPPER).toList())
        `</pre> *
         * However, the above is a more-verbose but identical operation as
         * [mapToList]. This `asRows` method should be used when you need
         * to limit or filter the items separate from the actual query.
         * <pre>`flatMap(q -> q.asRows(Item.MAPPER).take(5).toList())
         * // or...
         * flatMap(q -> q.asRows(Item.MAPPER).filter(i -> i.isActive).toList())
        `</pre> *
         *
         *
         * Note: Limiting results or filtering will almost always be faster in the database as part of
         * a query and should be preferred, where possible.
         *
         *
         * The resulting observable will be empty if `null` is returned from [.run].
         */
        @CheckResult
        fun <T> asRows(mapper: (Cursor) -> T): Flow<T> {
            return flow {
                val cursor = runQuery() ?: return@flow
                while (cursor.moveToNext()) {
                    emit(mapper(cursor))
                }
            }
        }
    }

    /** A simple indirection for logging debug messages.  */
    fun interface Logger {

        fun log(message: String)
    }

    companion object {

        val DEFAULT_LOGGER: Logger = Logger { message -> Log.d("SqlBrite", message) }
        val DEFAULT_TRANSFORMER: (Flow<Query>) -> Flow<Query> = { queryObservable -> queryObservable }
    }
}