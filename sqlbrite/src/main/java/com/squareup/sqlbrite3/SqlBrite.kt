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

import android.app.DownloadManager
import android.arch.persistence.db.SupportSQLiteOpenHelper
import android.content.ContentResolver
import android.database.Cursor
import android.support.annotation.CheckResult
import android.support.annotation.WorkerThread
import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

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
        dispatcher: CoroutineDispatcher
    ): BriteDatabase {
        return BriteDatabase(helper, logger, dispatcher, queryTransformer)
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
        abstract suspend fun runQuery(): Cursor

        /**
         * Execute the query on the underlying database and return an Observable of each row mapped to
         * `T` by `mapper`.
         *
         *
         * Standard usage of this operation is in `flatMap`:
         * <pre>`flatMap(q -> q.asRows(Item.MAPPER).toList())
        `</pre> *
         * However, the above is a more-verbose but identical operation as
         * [QueryObservable.mapToList]. This `asRows` method should be used when you need
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

        companion object {

            /**
             * Creates an [operator][ObservableOperator] which transforms a query returning a
             * single row to a `T` using `mapper`. Use with [Observable.lift].
             *
             *
             * It is an error for a query to pass through this operator with more than 1 row in its result
             * set. Use `LIMIT 1` on the underlying SQL query to prevent this. Result sets with 0 rows
             * do not emit an item.
             *
             *
             * This operator ignores `null` cursors returned from [.run].
             *
             * @param mapper Maps the current [Cursor] row to `T`. May not return null.
             */
//            @JvmStatic
//            @CheckResult  //
//            fun <T> mapToOne(mapper: (Cursor?) -> T): (Query) -> T {
//                return QueryToOneOperator(mapper, null)
//            }

//            suspend fun <T> Flow<Query>.toOneOperator(foo: (Cursor?) -> T, defaultValue: T): Flow<T> {
//                val item: T?
//                val cursor = this.runQuery() ?: return em
//                if (cursor.moveToNext()) {
//                    item = foo(cursor)
//                    if (item == null) {
//                        downstream.onError(NullPointerException("QueryToOne mapper returned null"))
//                        return
//                    }
//                    check(!cursor.moveToNext()) { "Cursor returned more than 1 row" }
//                }
//
//                if (item != null) {
//                    emit(item)
//                } else {
//                    emit(defaultValue)
//                }
//            }

            /**
             * Creates an [operator][ObservableOperator] which transforms a query returning a
             * single row to a `T` using `mapper`. Use with [Observable.lift].
             *
             *
             * It is an error for a query to pass through this operator with more than 1 row in its result
             * set. Use `LIMIT 1` on the underlying SQL query to prevent this. Result sets with 0 rows
             * emit `defaultValue`.
             *
             *
             * This operator emits `defaultValue` if `null` is returned from [.run].
             *
             * @param mapper Maps the current [Cursor] row to `T`. May not return null.
             * @param defaultValue Value returned if result set is empty
             */
            // Public API contract.
//            @JvmStatic
//            @CheckResult
//            fun <T> mapToOneOrDefault(
//                mapper: (Cursor?) -> T,
//                defaultValue: T
//            ): Flow<T> {
//                return mapper(this) ?: defaultValue
//            }


            /**
             * Creates an [operator][ObservableOperator] which transforms a query to a
             * `List<T>` using `mapper`. Use with [Observable.lift].
             *
             *
             * Be careful using this operator as it will always consume the entire cursor and create objects
             * for each row, every time this observable emits a new query. On tables whose queries update
             * frequently or very large result sets this can result in the creation of many objects.
             *
             *
             * This operator ignores `null` cursors returned from [.run].
             *
             * @param mapper Maps the current [Cursor] row to `T`. May not return null.
             */
//            @JvmStatic
//            @CheckResult
//            fun <T> mapToList(
//                mapper: (Cursor?) -> List<T>
//            ): List<T> {
//                return QueryToListOperator<>(mapper);
//            }
        }
    }

    /** A simple indirection for logging debug messages.  */
    interface Logger {

        fun log(message: String?)
    }

    companion object {

        val DEFAULT_LOGGER: Logger = object : Logger {
            override fun log(message: String?) {
                Log.d("SqlBrite", message)
            }
        }
        val DEFAULT_TRANSFORMER: (Flow<Query>) -> Flow<Query> = { queryObservable -> queryObservable }
    }
}