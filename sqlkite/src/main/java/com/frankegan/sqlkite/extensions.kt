/*
 * Copyright (C) 2017 Square, Inc.
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
@file:Suppress("NOTHING_TO_INLINE") // Extensions provided for intentional convenience.

package com.frankegan.sqlkite

import android.database.Cursor
import com.frankegan.sqlkite.SqlKite.Query
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.resume

typealias Mapper<T> = (Cursor) -> T

/**
 * Transforms an observable of single-row [Query] to an observable of `T` using `mapper`.
 *
 * It is an error for a query to pass through this operator with more than 1 row in its result set.
 * Use `LIMIT 1` on the underlying SQL query to prevent this. Result sets with 0 rows do not emit
 * an item.
 *
 * This operator ignores null cursors returned from [Query.run].
 *
 * @param mapper Maps the current [Cursor] row to `T`. May not return null.
 */
inline fun <T> Flow<Query>.mapToOne(noinline mapper: (Cursor) -> T): Flow<T> {
    return transform { query ->
        var item: T? = null

        query.runQuery().use { cursor ->
            if (cursor?.moveToNext() == true) {
                item = mapper(cursor)
                check(!cursor.moveToNext()) { "Cursor returned more than 1 row" }
            }
        }

        item?.let {
            emit(it)
        }
    }
}


/**
 * Transforms an observable of single-row [Query] to an observable of `T` using `mapper`
 *
 * It is an error for a query to pass through this operator with more than 1 row in its result set.
 * Use `LIMIT 1` on the underlying SQL query to prevent this. Result sets with 0 rows emit
 * `default`.
 *
 * This operator emits `defaultValue` if null is returned from [Query.run].
 *
 * @param mapper Maps the current [Cursor] row to `T`. May not return null.
 * @param defaultValue Value returned if result set is empty
 */
inline fun <T> Flow<Query>.mapToOneOrDefault(defaultValue: T, noinline mapper: (Cursor) -> T): Flow<T> {
    return transform { query ->
        var item: T? = null

        query.runQuery().use { cursor ->
            if (cursor?.moveToNext() == true) {
                item = mapper(cursor)
                check(!cursor.moveToNext()) { "Cursor returned more than 1 row" }
            }
        }

        emit(item ?: defaultValue)
    }
}

/**
 * Transforms an observable of [Query] to `List<T>` using `mapper` for each row.
 *
 * Be careful using this operator as it will always consume the entire cursor and create objects
 * for each row, every time this observable emits a new query. On tables whose queries update
 * frequently or very large result sets this can result in the creation of many objects.
 *
 * This operator ignores null cursors returned from [Query.run].
 *
 * @param mapper Maps the current [Cursor] row to `T`. May not return null.
 */
inline fun <T> Flow<Query>.mapToList(noinline mapper: Mapper<T>): Flow<List<T>> {
    return transform { query ->
        val items = mutableListOf<T>()

        query.runQuery().use { cursor ->
            while (cursor?.moveToNext() == true) {
                items += mapper(cursor)
            }
        }

        emit(items)
    }
}

internal class TransactionElement(
    private val transactionThreadControlJob: Job,
    internal val transactionDispatcher: ContinuationInterceptor
) : CoroutineContext.Element {

    // Singleton key used to retrieve this context element
    companion object Key : CoroutineContext.Key<TransactionElement>

    override val key: CoroutineContext.Key<TransactionElement>
        get() = TransactionElement

    /**
     * Number of transactions (including nested ones) started with this element.
     * Call [acquire] to increase the count and [release] to decrease it. If the
     * count reaches zero when [release] is invoked then the transaction job is
     * cancelled and the transaction thread is released.
     */
    private val referenceCount = AtomicInteger(0)

    fun acquire() {
        referenceCount.incrementAndGet()
    }

    fun release() {
        val count = referenceCount.decrementAndGet()
        if (count < 0) {
            throw IllegalStateException(
                "Transaction was never started or was already released.")
        } else if (count == 0) {
            // Cancel the job that controls the transaction thread, causing it
            // to be released.
            transactionThreadControlJob.cancel()
        }
    }
}



/**
 * Prepares and returns a [ContinuationInterceptor] to dispatch coroutines to
 * an acquired thread used to perform transaction work. The [controlJob] is used
 * to control the release of the thread by cancelling the job.
 */
internal suspend fun Executor.acquireTransactionThread(
    controlJob: Job
): ContinuationInterceptor = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation {
        // We got cancelled while waiting to acquire a thread, we can't stop our
        // attempt to acquire a thread, but we can cancel the controlling job so
        // once it gets acquired it is quickly released.
        controlJob.cancel()
    }
    try {
        execute {
            // runBlocking creates an event loop that executes coroutine blocks.
            runBlocking {
                // Thread acquired, resume suspendCancellableCoroutine by returning
                // the interceptor created by runBlocking. The interceptor will be
                // used to intercept and dispatch continuation blocks into the
                // acquired thread.
                continuation.resume(coroutineContext[ContinuationInterceptor]!!)

                // Suspend this runBlocking coroutine until control job is
                // completed. This prevents runBlocking from immediately completing
                // since the body of this coroutine is empty.
                controlJob.join()
            }
        }
    } catch (ex: RejectedExecutionException) {
        // Couldn't acquire a thread, cancel coroutine.
        continuation.cancel(
            IllegalStateException(
                "Unable to acquire a thread to perform the transaction.", ex)
        )
    }
}

