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
package com.frankegan.sqlkite

import android.content.ContentResolver
import android.database.ContentObserver
import android.database.Cursor
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.annotation.CheckResult
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.onStart
import java.util.Arrays
import java.util.concurrent.TimeUnit

/**
 * A lightweight wrapper around [ContentResolver] which allows for continuously observing
 * the result of a query. Create using a [SqlKite] instance.
 */
class KiteContentResolver internal constructor(
    private val contentResolver: ContentResolver,
    private val logger: SqlKite.Logger,
    private val dispatcher: CoroutineDispatcher,
    private val queryTransformer: (Flow<SqlKite.Query>) -> Flow<SqlKite.Query>
) {

    private val contentObserverHandler: Handler = Handler(Looper.getMainLooper())

    @Volatile
    var logging = false

    /** Control whether debug logging is enabled.  */
    fun setLoggingEnabled(enabled: Boolean) {
        logging = enabled
    }

    /**
     * Create an observable which will notify subscribers with a [SqlKite.Query] for
     * execution. Subscribers are responsible for **always** closing [Cursor] instance
     * returned from the [SqlKite.Query].
     *
     *
     * Subscribers will receive an immediate notification for initial data as well as subsequent
     * notifications for when the supplied `uri`'s data changes. Unsubscribe when you no longer
     * want updates to a query.
     *
     *
     * Since content resolver triggers are inherently asynchronous, items emitted from the returned
     * flow use the [CoroutineDispatcher] supplied to [SqlKite.wrapContentProvider].
     *
     *
     * Note: To skip the immediate notification and only receive subsequent notifications when data
     * has changed call `drop(1)` on the returned observable.
     *
     *
     * **Warning:** this method does not perform the query! Only by collecting the returned
     * [Flow] will the operation occur.
     *
     * @see ContentResolver.query
     * @see ContentResolver.registerContentObserver
     */
    @CheckResult
    fun createQuery(
        uri: Uri,
        projection: Array<String>? = null,
        selection: String? = null,
        selectionArgs: Array<String>? = null,
        sortOrder: String? = null,
        notifyForDescendants: Boolean = false
    ): Flow<SqlKite.Query> {
        val query: SqlKite.Query = object : SqlKite.Query() {
            override suspend fun runQuery(): Cursor? {
                val startNanos = System.nanoTime()
                val cursor: Cursor? = contentResolver.query(uri, projection, selection, selectionArgs, sortOrder)
                if (logging) {
                    val tookMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos)
                    log(
                        """QUERY (%sms)
                        uri: $uri
                        projection: $projection
                        selection: $selection
                        selectionArgs: ${Arrays.toString(selectionArgs)}
                        sortOrder: $sortOrder
                        notifyForDescendants: $notifyForDescendants
                        """.trimIndent(),
                        tookMillis
                    )
                }
                return cursor
            }
        }

        val queries: Flow<SqlKite.Query> = callbackFlow {
            val observer: ContentObserver = object : ContentObserver(contentObserverHandler) {
                override fun onChange(selfChange: Boolean) {
                    trySendBlocking(query)
                }
            }
            contentResolver.registerContentObserver(uri, notifyForDescendants, observer)
            awaitClose {
                contentResolver.unregisterContentObserver(observer)
            }
        }

        return queries
            .onStart { emit(query) }
            .flowOn(dispatcher)
            .let(queryTransformer) // Apply the user's query transformer.
    }

    fun log(message: String, vararg args: Any?) {
        logger.log(
            if (args.isNotEmpty()) message.format(*args) else message
        )
    }
}