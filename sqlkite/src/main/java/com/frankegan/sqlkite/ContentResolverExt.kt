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
import kotlinx.coroutines.flow.*
import java.util.*

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
fun ContentResolver.observeQuery(
    uri: Uri,
    projection: Array<String>? = null,
    selection: String? = null,
    selectionArgs: Array<String>? = null,
    sortOrder: String? = null,
    notifyForDescendants: Boolean = true
): Flow<SqlKite.Query> {
    val query: SqlKite.Query = object : SqlKite.Query() {
        override suspend fun runQuery(): Cursor? {
            return query(uri, projection, selection, selectionArgs, sortOrder)
        }
    }

    return callbackFlow {
        val observer: ContentObserver = object : ContentObserver(mainThread) {
            override fun onChange(selfChange: Boolean) {
                trySend(query)
            }
        }

        registerContentObserver(uri, notifyForDescendants, observer)

        awaitClose {
            unregisterContentObserver(observer)
        }
    }.onStart {
        emit(query)
    }
}

private val mainThread = Handler(Looper.getMainLooper())
