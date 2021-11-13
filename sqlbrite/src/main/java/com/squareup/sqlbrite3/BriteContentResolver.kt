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

import android.content.ContentResolver
import android.database.ContentObserver
import android.database.Cursor
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.support.annotation.CheckResult
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.sendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import java.util.Arrays
import java.util.concurrent.TimeUnit

/**
 * A lightweight wrapper around [ContentResolver] which allows for continuously observing
 * the result of a query. Create using a [SqlBrite] instance.
 */
class BriteContentResolver internal constructor(
    private val contentResolver: ContentResolver,
    private val logger: SqlBrite.Logger,
    private val dispatcher: CoroutineDispatcher,
    private val queryTransformer: (Flow<SqlBrite.Query>) -> Flow<SqlBrite.Query>
) {

    val contentObserverHandler: Handler = Handler(Looper.getMainLooper())

    @Volatile
    var logging = false

    /** Control whether debug logging is enabled.  */
    fun setLoggingEnabled(enabled: Boolean) {
        logging = enabled
    }

    /**
     * Create an observable which will notify subscribers with a [query][Query] for
     * execution. Subscribers are responsible for **always** closing [Cursor] instance
     * returned from the [Query].
     *
     *
     * Subscribers will receive an immediate notification for initial data as well as subsequent
     * notifications for when the supplied `uri`'s data changes. Unsubscribe when you no longer
     * want updates to a query.
     *
     *
     * Since content resolver triggers are inherently asynchronous, items emitted from the returned
     * observable use the [Scheduler] supplied to [SqlBrite.wrapContentProvider]. For
     * consistency, the immediate notification sent on subscribe also uses this scheduler. As such,
     * calling [subscribeOn][Observable.subscribeOn] on the returned observable has no effect.
     *
     *
     * Note: To skip the immediate notification and only receive subsequent notifications when data
     * has changed call `skip(1)` on the returned observable.
     *
     *
     * **Warning:** this method does not perform the query! Only by subscribing to the returned
     * [Observable] will the operation occur.
     *
     * @see ContentResolver.query
     * @see ContentResolver.registerContentObserver
     */
    @CheckResult
    fun createQuery(
        uri: Uri,
        projection: Array<String?>?,
        selection: String?,
        selectionArgs: Array<String?>?,
        sortOrder: String?,
        notifyForDescendents: Boolean
    ): Flow<SqlBrite.Query> {
        val query: SqlBrite.Query = object : SqlBrite.Query() {
            override suspend fun runQuery(): Cursor {
                val startNanos = System.nanoTime()
                val cursor: Cursor = contentResolver.query(uri, projection, selection, selectionArgs, sortOrder)
                if (logging) {
                    val tookMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos)
                    log(
                        """QUERY (%sms)
                        uri: %s
                        projection: %s
                        selection: %s
                        selectionArgs: %s
                        sortOrder: %s
                        notifyForDescendents: %s
                        """.trimIndent(), tookMillis, uri,
                        Arrays.toString(projection), selection, Arrays.toString(selectionArgs), sortOrder,
                        notifyForDescendents
                    )
                }
                return cursor
            }
        }

        val queries: Flow<SqlBrite.Query> = callbackFlow {
            val observer: ContentObserver = object : ContentObserver(contentObserverHandler) {
                override fun onChange(selfChange: Boolean) {
                    sendBlocking(query)
                }
            }
            contentResolver.registerContentObserver(uri, notifyForDescendents, observer)
            awaitClose {
                contentResolver.unregisterContentObserver(observer)
            }
        }

        return queries //
            .flowOn(dispatcher) //
            .let(queryTransformer) // Apply the user's query transformer.
    }

    fun log(message: String?, vararg args: Any?) {
        var message = message
        if (args.size > 0) message = String.format(message!!, *args)
        logger.log(message)
    }
}