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

import android.arch.persistence.db.SimpleSQLiteQuery
import android.arch.persistence.db.SupportSQLiteDatabase
import android.arch.persistence.db.SupportSQLiteOpenHelper
import android.arch.persistence.db.SupportSQLiteQuery
import android.arch.persistence.db.SupportSQLiteStatement
import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteTransactionListener
import android.support.annotation.CheckResult
import android.support.annotation.IntDef
import android.support.annotation.WorkerThread
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.util.LinkedHashSet
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext

/**
 * A lightweight wrapper around [SupportSQLiteOpenHelper] which allows for continuously
 * observing the result of a query. Create using a [SqlKite] instance.
 */
class KiteDatabase internal constructor(
    private val helper: SupportSQLiteOpenHelper,
    private val logger: SqlKite.Logger,
    private val queryDispatcher: CoroutineDispatcher,
    private val transactionExecutor: Executor = defaultTransactionExecutor(),
    private val queryTransformer: (Flow<SqlKite.Query>) -> Flow<SqlKite.Query>
) : Closeable {

    // Package-private to avoid synthetic accessor method for 'transaction' instance.
    val transactions = ThreadLocal<SqliteTransaction?>()
    private val suspendingTransactionId = ThreadLocal<Int>()

    private val triggers = MutableSharedFlow<Set<String>>(extraBufferCapacity = 1)

    private val transaction: Transaction = object : Transaction {
        override fun markSuccessful() {
            if (logging) log("TXN SUCCESS %s", transactions.get())
            writableDatabase.setTransactionSuccessful()
        }

        override fun yieldIfContendedSafely(): Boolean {
            return writableDatabase.yieldIfContendedSafely()
        }

        override fun yieldIfContendedSafely(sleepAmount: Long, sleepUnit: TimeUnit): Boolean {
            return writableDatabase.yieldIfContendedSafely(sleepUnit.toMillis(sleepAmount))
        }

        override fun end() {
            val transaction: SqliteTransaction = transactions.get() ?: throw IllegalStateException("Not in transaction.")
            val newTransaction = transaction.parent
            transactions.set(newTransaction)
            if (logging) log("TXN END %s", transaction)
            writableDatabase.endTransaction()
            // Send the triggers after ending the transaction in the DB.
            if (transaction.commit) {
                sendTableTrigger(transaction)
            }
        }

        override fun close() {
            end()
        }
    }

    private val ensureNotInTransaction = {
        check(suspendingTransactionId.get() == null && transactions.get() == null) { "Cannot subscribe to observable query in a transaction." }
    }

    // Package-private to avoid synthetic accessor method for 'transaction' instance.
    @Volatile
    var logging = false

    /**
     * Control whether debug logging is enabled.
     */
    fun setLoggingEnabled(enabled: Boolean) {
        logging = enabled
    }

    /**
     * Create and/or open a database.  This will be the same object returned by
     * [SupportSQLiteOpenHelper.getWritableDatabase] unless some problem, such as a full disk,
     * requires the database to be opened read-only.  In that case, a read-only
     * database object will be returned.  If the problem is fixed, a future call
     * to [SupportSQLiteOpenHelper.getWritableDatabase] may succeed, in which case the read-only
     * database object will be closed and the read/write object will be returned
     * in the future.
     *
     *
     * Like [SupportSQLiteOpenHelper.getWritableDatabase], this method may
     * take a long time to return, so you should not call it from the
     * application main thread, including from
     * [ContentProvider.onCreate()][android.content.ContentProvider.onCreate].
     *
     * @throws android.database.sqlite.SQLiteException if the database cannot be opened
     * @return a database object valid until [SupportSQLiteOpenHelper.getWritableDatabase]
     * or [.close] is called.
     */
    @get:WorkerThread
    @get:CheckResult
    val readableDatabase: SupportSQLiteDatabase
        get() = helper.readableDatabase

    /**
     * Create and/or open a database that will be used for reading and writing.
     * The first time this is called, the database will be opened and
     * [SupportSQLiteOpenHelper.Callback.onCreate], [SupportSQLiteOpenHelper.Callback.onUpgrade] and/or [SupportSQLiteOpenHelper.Callback.onOpen] will be
     * called.
     *
     *
     * Once opened successfully, the database is cached, so you can
     * call this method every time you need to write to the database.
     * (Make sure to call [.close] when you no longer need the database.)
     * Errors such as bad permissions or a full disk may cause this method
     * to fail, but future attempts may succeed if the problem is fixed.
     *
     *
     * Database upgrade may take a long time, you
     * should not call this method from the application main thread, including
     * from [ContentProvider.onCreate()][android.content.ContentProvider.onCreate].
     *
     * @throws android.database.sqlite.SQLiteException if the database cannot be opened for writing
     * @return a read/write database object valid until [.close] is called
     */
    @get:WorkerThread
    @get:CheckResult
    val writableDatabase: SupportSQLiteDatabase
        get() = helper.writableDatabase

    fun sendTableTrigger(tables: Set<String>) {
        val transaction = transactions.get()
        if (transaction != null) {
            transaction.addAll(tables)
        } else {
            if (logging) log("TRIGGER %s", tables)
            triggers.tryEmit(tables)
        }
    }

    /**
     * Begin a transaction for this thread.
     *
     *
     * Transactions may nest. If the transaction is not in progress, then a database connection is
     * obtained and a new transaction is started. Otherwise, a nested transaction is started.
     *
     *
     * Each call to `newTransaction` must be matched exactly by a call to
     * [Transaction.end]. To mark a transaction as successful, call
     * [Transaction.markSuccessful] before calling [Transaction.end]. If the
     * transaction is not successful, or if any of its nested transactions were not successful, then
     * the entire transaction will be rolled back when the outermost transaction is ended.
     *
     *
     * Transactions queue up all query notifications until they have been applied.
     *
     *
     * Here is the standard idiom for transactions:
     *
     * <pre>`try (Transaction transaction = db.newTransaction()) {
     * ...
     * transaction.markSuccessful();
     * }
    `</pre> *
     *
     * Manually call [Transaction.end] when try-with-resources is not available:
     * <pre>`Transaction transaction = db.newTransaction();
     * try {
     * ...
     * transaction.markSuccessful();
     * } finally {
     * transaction.end();
     * }
    `</pre> *
     *
     *
     * @see SupportSQLiteDatabase.beginTransaction
     */
    @CheckResult
    fun newTransaction(): Transaction {
        val transaction = SqliteTransaction(transactions.get())
        transactions.set(transaction)
        if (logging) log("TXN BEGIN %s", transaction)
        writableDatabase.beginTransactionWithListener(transaction)
        return this.transaction
    }

    /**
     * Close the underlying [SupportSQLiteOpenHelper] and remove cached readable and writeable
     * databases. This does not prevent existing observables from retaining existing references as
     * well as attempting to create new ones for new subscriptions.
     */
    override fun close() {
        helper.close()
    }

    /**
     * Create an observable which will notify subscribers with a [SqlKite.Query] for
     * execution. Subscribers are responsible for **always** closing [Cursor] instance
     * returned from the [SqlKite.Query].
     *
     *
     * Subscribers will receive an immediate notification for initial data as well as subsequent
     * notifications for when the supplied `table`'s data changes through the `insert`,
     * `update`, and `delete` methods of this class. Unsubscribe when you no longer want
     * updates to a query.
     *
     *
     * Since database triggers are inherently asynchronous, items emitted from the returned
     * observable use the [CoroutineDispatcher] supplied to [SqlKite.wrapDatabaseHelper]. For
     * consistency, the immediate notification sent on subscribe also uses this scheduler.
     *
     *
     * Note: To skip the immediate notification and only receive subsequent notifications when data
     * has changed call `drop(1)` on the returned observable.
     *
     *
     * **Warning:** this method does not perform the query! Only by subscribing to the returned
     * [Flow] will the operation occur.
     *
     * @see SupportSQLiteDatabase.query
     */
    @CheckResult
    fun createQuery(
        table: String,
        sql: String,
        vararg args: Any
    ): Flow<SqlKite.Query> {
        return createQuery(DatabaseQuery(listOf(table), SimpleSQLiteQuery(sql, args)))
    }

    /**
     * See [.createQuery] for usage. This overload allows for
     * monitoring multiple tables for changes.
     *
     * @see SupportSQLiteDatabase.query
     */
    @CheckResult
    fun createQuery(
        tables: Iterable<String>,
        sql: String,
        vararg args: Any
    ): Flow<SqlKite.Query> {
        return createQuery(DatabaseQuery(tables, SimpleSQLiteQuery(sql, args)))
    }

    /**
     * Create an flow which will notify subscribers with a [SqlKite.Query] for
     * execution. Subscribers are responsible for **always** closing [Cursor] instance
     * returned from the [SqlKite.Query].
     *
     *
     * Subscribers will receive an immediate notification for initial data as well as subsequent
     * notifications for when the supplied `table`'s data changes through the `insert`,
     * `update`, and `delete` methods of this class. Unsubscribe when you no longer want
     * updates to a query.
     *
     *
     * Since database triggers are inherently asynchronous, items emitted from the returned
     * flows use the [CoroutineDispatcher] supplied to [SqlKite.wrapDatabaseHelper]. For
     * consistency, the immediate notification sent on subscribe also uses this dispatcher.
     *
     *
     * Note: To skip the immediate notification and only receive subsequent notifications when data
     * has changed call `drop(1)` on the returned flow.
     *
     *
     * **Warning:** this method does not perform the query! Only by subscribing to the returned
     * [Flow] will the operation occur.
     *
     * @see SupportSQLiteDatabase.query
     */
    @CheckResult
    fun createQuery(
        table: String,
        query: SupportSQLiteQuery
    ): Flow<SqlKite.Query> {
        return createQuery(DatabaseQuery(listOf(table), query))
    }

    /**
     * See [.createQuery] for usage. This overload allows for
     * monitoring multiple tables for changes.
     *
     * @see SupportSQLiteDatabase.query
     */
    @CheckResult
    fun createQuery(
        tables: Iterable<String>,
        query: SupportSQLiteQuery
    ): Flow<SqlKite.Query> {
        return createQuery(DatabaseQuery(tables, query))
    }

    @CheckResult
    private fun createQuery(query: DatabaseQuery): Flow<SqlKite.Query> {
        check(suspendingTransactionId.get() == null && transactions.get() == null) {
            """
            Cannot create observable query in transaction. 
            Use query() for a query inside a transaction.
            """.trimIndent()
        }
        return triggers
            .onSubscription { ensureNotInTransaction() }
            .filter { query.selectsFor(it) } // DatabaseQuery filters triggers to on tables we care about.
            .map { query } // DatabaseQuery maps to itself to save an allocation.
            .onStart { emit(query) }
            .flowOn(queryDispatcher)
            .let(queryTransformer) // Apply the user's query transformer.
    }

    private suspend fun <R> withQueryOrTransactionContext(block: suspend () -> R): R {
        if (writableDatabase.isOpen && writableDatabase.inTransaction() && suspendingTransactionId.get() != null) {
            return block()
        }
        // Use the transaction dispatcher if we are on a transaction coroutine, otherwise
        // use the database dispatchers.
        val context = coroutineContext[TransactionElement]?.transactionDispatcher
            ?: queryDispatcher
        return withContext(context) {
            block()
        }
    }

    /**
     * Runs the provided SQL and returns a [Cursor] over the result set.
     *
     * @see SupportSQLiteDatabase.query
     */
    @CheckResult
    @WorkerThread
    suspend fun query(sql: String, vararg args: Any): Cursor {
        val cursor: Cursor = withQueryOrTransactionContext {
            readableDatabase.query(sql, args)
        }
        if (logging) {
            log("QUERY\n  sql: %s\n  args: %s", indentSql(sql), args.contentToString())
        }
        return cursor
    }

    /**
     * Runs the provided [SupportSQLiteQuery] and returns a [Cursor] over the result set.
     *
     * @see SupportSQLiteDatabase.query
     */
    @CheckResult
    @WorkerThread
    suspend fun query(query: SupportSQLiteQuery): Cursor = withContext(queryDispatcher) {
        val cursor: Cursor = withQueryOrTransactionContext {
            readableDatabase.query(query)
        }
        if (logging) {
            log("QUERY\n  sql: %s", indentSql(query.sql))
        }
        cursor
    }

    /**
     * Insert a row into the specified `table` and notify any subscribed queries.
     * @see SupportSQLiteDatabase.insert
     */
    @WorkerThread
    suspend fun insert(
        table: String,
        @ConflictAlgorithm conflictAlgorithm: Int,
        values: ContentValues
    ): Long {
        val db: SupportSQLiteDatabase = writableDatabase
        if (logging) {
            log(
                "INSERT\n  table: %s\n  values: %s\n  conflictAlgorithm: %s", table, values,
                conflictString(conflictAlgorithm)
            )
        }
        val rowId: Long = withQueryOrTransactionContext {
            db.insert(table, conflictAlgorithm, values)
        }
        if (logging) log("INSERT id: %s", rowId)
        if (rowId != -1L) {
            // Only send a table trigger if the insert was successful.
            sendTableTrigger(setOf(table))
        }
        return rowId
    }

    /**
     * Delete rows from the specified `table` and notify any subscribed queries. This method
     * will not trigger a notification if no rows were deleted.
     *
     * @see SupportSQLiteDatabase.delete
     */
    @WorkerThread
    suspend fun delete(
        table: String,
        whereClause: String?,
        vararg whereArgs: String?
    ): Int = withContext(queryDispatcher) {
        val db: SupportSQLiteDatabase = writableDatabase
        if (logging) {
            log(
                "DELETE\n  table: %s\n  whereClause: %s\n  whereArgs: %s", table, whereClause,
                whereArgs.contentToString()
            )
        }
        val rows: Int = withQueryOrTransactionContext {
            db.delete(table, whereClause, whereArgs)
        }
        if (logging) log("DELETE affected %s %s", rows, if (rows != 1) "rows" else "row")
        if (rows > 0) {
            // Only send a table trigger if rows were affected.
            sendTableTrigger(setOf(table))
        }
        rows
    }

    /**
     * Update rows in the specified `table` and notify any subscribed queries. This method
     * will not trigger a notification if no rows were updated.
     *
     * @see SupportSQLiteDatabase.update
     */
    @WorkerThread
    suspend fun update(
        table: String, @ConflictAlgorithm conflictAlgorithm: Int,
        values: ContentValues, whereClause: String?, vararg whereArgs: String?
    ): Int = withContext(queryDispatcher) {
        val db: SupportSQLiteDatabase = writableDatabase
        if (logging) {
            log(
                "UPDATE\n  table: %s\n  values: %s\n  whereClause: %s\n  whereArgs: %s\n  conflictAlgorithm: %s",
                table, values, whereClause, whereArgs.contentToString(),
                conflictString(conflictAlgorithm)
            )
        }
        val rows: Int = withQueryOrTransactionContext {
            db.update(table, conflictAlgorithm, values, whereClause, whereArgs)
        }
        if (logging) log("UPDATE affected %s %s", rows, if (rows != 1) "rows" else "row")
        if (rows > 0) {
            // Only send a table trigger if rows were affected.
            sendTableTrigger(setOf(table))
        }
        rows
    }

    /**
     * Execute `sql` provided it is NOT a `SELECT` or any other SQL statement that
     * returns data. No data can be returned (such as the number of affected rows). Instead, use
     * [.insert], [.update], et al, when possible.
     *
     *
     * No notifications will be sent to queries if `sql` affects the data of a table.
     *
     * @see SupportSQLiteDatabase.execSQL
     */
    @WorkerThread
    suspend fun execute(sql: String) = withQueryOrTransactionContext {
        if (logging) log("EXECUTE\n  sql: %s", indentSql(sql))
        writableDatabase.execSQL(sql)
    }

    /**
     * Execute `sql` provided it is NOT a `SELECT` or any other SQL statement that
     * returns data. No data can be returned (such as the number of affected rows). Instead, use
     * [.insert], [.update], et al, when possible.
     *
     *
     * No notifications will be sent to queries if `sql` affects the data of a table.
     *
     * @see SupportSQLiteDatabase.execSQL
     */
    @WorkerThread
    suspend fun execute(sql: String, vararg args: Any?) = withQueryOrTransactionContext {
        if (logging) log("EXECUTE\n  sql: %s\n  args: %s", indentSql(sql), args.contentToString())
        writableDatabase.execSQL(sql, args)
    }

    /**
     * Execute `sql` provided it is NOT a `SELECT` or any other SQL statement that
     * returns data. No data can be returned (such as the number of affected rows). Instead, use
     * [.insert], [.update], et al, when possible.
     *
     *
     * A notification to queries for `table` will be sent after the statement is executed.
     *
     * @see SupportSQLiteDatabase.execSQL
     */
    @WorkerThread
    suspend fun executeAndTrigger(table: String, sql: String?) = withQueryOrTransactionContext {
        executeAndTrigger(setOf(table), sql!!)
    }

    /**
     * See [.executeAndTrigger] for usage. This overload allows for triggering multiple tables.
     *
     * @see KiteDatabase.executeAndTrigger
     */
    @WorkerThread
    suspend fun executeAndTrigger(tables: Set<String>, sql: String) = withQueryOrTransactionContext {
        execute(sql)
        sendTableTrigger(tables)
    }

    /**
     * Execute `sql` provided it is NOT a `SELECT` or any other SQL statement that
     * returns data. No data can be returned (such as the number of affected rows). Instead, use
     * [insert], [update], et al, when possible.
     *
     *
     * A notification to queries for `table` will be sent after the statement is executed.
     *
     * @see SupportSQLiteDatabase.execSQL
     */
    suspend fun executeAndTrigger(table: String, sql: String, vararg args: Any?) = withQueryOrTransactionContext {
        executeAndTrigger(setOf(table), sql, *args)
    }

    /**
     * See [executeAndTrigger] for usage. This overload allows for triggering multiple tables.
     *
     * @see KiteDatabase.executeAndTrigger
     */
    suspend fun executeAndTrigger(tables: Set<String>, sql: String, vararg args: Any?) = withQueryOrTransactionContext {
        execute(sql, *args)
        sendTableTrigger(tables)
    }

    /**
     * Execute `statement`, if the the number of rows affected by execution of this SQL
     * statement is of any importance to the caller - for example, UPDATE / DELETE SQL statements.
     *
     * @return the number of rows affected by this SQL statement execution.
     * @throws android.database.SQLException If the SQL string is invalid
     *
     * @see SupportSQLiteStatement.executeUpdateDelete
     */
    suspend fun executeUpdateDelete(table: String, statement: SupportSQLiteStatement): Int = withQueryOrTransactionContext {
        executeUpdateDelete(setOf(table), statement)
    }

    /**
     * See [.executeUpdateDelete] for usage. This overload
     * allows for triggering multiple tables.
     *
     * @see KiteDatabase.executeUpdateDelete
     */
    suspend fun executeUpdateDelete(tables: Set<String>, statement: SupportSQLiteStatement): Int = withQueryOrTransactionContext {
        if (logging) log("EXECUTE\n %s", statement)
        val rows: Int = statement.executeUpdateDelete()
        if (rows > 0) {
            // Only send a table trigger if rows were affected.
            sendTableTrigger(tables)
        }
        rows
    }

    /**
     * Execute `statement` and return the ID of the row inserted due to this call.
     * The SQL statement should be an INSERT for this to be a useful call.
     *
     * @return the row ID of the last row inserted, if this insert is successful. -1 otherwise.
     *
     * @throws android.database.SQLException If the SQL string is invalid
     *
     * @see SupportSQLiteStatement.executeInsert
     */
    suspend fun executeInsert(table: String, statement: SupportSQLiteStatement): Long = withQueryOrTransactionContext {
        executeInsert(setOf(table), statement)
    }

    /**
     * See [.executeInsert] for usage. This overload allows for
     * triggering multiple tables.
     *
     * @see KiteDatabase.executeInsert
     */
    suspend fun executeInsert(tables: Set<String>, statement: SupportSQLiteStatement): Long = withQueryOrTransactionContext {
        if (logging) log("EXECUTE\n %s", statement)
        val rowId: Long = statement.executeInsert()
        if (rowId != -1L) {
            // Only send a table trigger if the insert was successful.
            sendTableTrigger(tables)
        }
        rowId
    }

    //TODO triple check this logic
    fun assertNotSuspendingTransaction() {
        check(writableDatabase.inTransaction() || suspendingTransactionId.get() == null) {
            ("""
                Cannot access database on a different coroutine 
                context inherited from a suspending transaction.
                """.trimIndent())
        }
    }

    private suspend fun createTransactionContext(): CoroutineContext {
        val controlJob = Job()
        // make sure to tie the control job to this context to avoid blocking the transaction if
        // context get cancelled before we can even start using this job. Otherwise, the acquired
        // transaction thread will forever wait for the controlJob to be cancelled.
        // see b/148181325
        coroutineContext[Job]?.invokeOnCompletion {
            controlJob.cancel()
        }
        val dispatcher = transactionExecutor.acquireTransactionThread(controlJob)
        val transactionElement = TransactionElement(controlJob, dispatcher)
        val threadLocalElement =
            suspendingTransactionId.asContextElement(controlJob.hashCode())
        return dispatcher + transactionElement + threadLocalElement
    }

    suspend fun <R> withTransaction(
        block: suspend () -> R
    ): R {
        // Use inherited transaction context if available, this allows nested suspending transactions.
        val transactionContext =
            coroutineContext[TransactionElement]?.transactionDispatcher ?: createTransactionContext()
        return withContext(transactionContext) {
            val transactionElement = coroutineContext[TransactionElement]!!
            transactionElement.acquire()
            try {
                if (logging) log("TXN BEGIN %s", transaction)
                writableDatabase.beginTransaction()
                try {
                    val result = block.invoke()
                    writableDatabase.setTransactionSuccessful()
                    return@withContext result
                } finally {
                    writableDatabase.endTransaction()
                }
            } finally {
                transactionElement.release()
            }
        }
    }

    suspend fun <R> withNonExclusiveTransaction(
        block: suspend () -> R
    ): R {
        // Use inherited transaction context if available, this allows nested suspending transactions.
        val transactionContext =
            coroutineContext[TransactionElement]?.transactionDispatcher ?: createTransactionContext()
        return withContext(transactionContext) {
            val transactionElement = coroutineContext[TransactionElement]!!
            transactionElement.acquire()
            try {
                if (logging) log("TXN BEGIN %s", transaction)
                writableDatabase.beginTransactionNonExclusive()
                try {
                    val result = block.invoke()
                    writableDatabase.setTransactionSuccessful()
                    return@withContext result
                } finally {
                    writableDatabase.endTransaction()
                }
            } finally {
                transactionElement.release()
            }
        }
    }


    /** An in-progress database transaction.  */
    interface Transaction : Closeable {

        /**
         * End a transaction. See [.newTransaction] for notes about how to use this and when
         * transactions are committed and rolled back.
         *
         * @see SupportSQLiteDatabase.endTransaction
         */
        @WorkerThread
        fun end()

        /**
         * Marks the current transaction as successful. Do not do any more database work between
         * calling this and calling [.end]. Do as little non-database work as possible in that
         * situation too. If any errors are encountered between this and [.end] the transaction
         * will still be committed.
         *
         * @see SupportSQLiteDatabase.setTransactionSuccessful
         */
        @WorkerThread
        fun markSuccessful()

        /**
         * Temporarily end the transaction to let other threads run. The transaction is assumed to be
         * successful so far. Do not call [.markSuccessful] before calling this. When this
         * returns a new transaction will have been created but not marked as successful. This assumes
         * that there are no nested transactions (newTransaction has only been called once) and will
         * throw an exception if that is not the case.
         *
         * @return true if the transaction was yielded
         *
         * @see SupportSQLiteDatabase.yieldIfContendedSafely
         */
        @WorkerThread
        fun yieldIfContendedSafely(): Boolean

        /**
         * Temporarily end the transaction to let other threads run. The transaction is assumed to be
         * successful so far. Do not call [.markSuccessful] before calling this. When this
         * returns a new transaction will have been created but not marked as successful. This assumes
         * that there are no nested transactions (newTransaction has only been called once) and will
         * throw an exception if that is not the case.
         *
         * @param sleepAmount if > 0, sleep this long before starting a new transaction if
         * the lock was actually yielded. This will allow other background threads to make some
         * more progress than they would if we started the transaction immediately.
         * @return true if the transaction was yielded
         *
         * @see SupportSQLiteDatabase.yieldIfContendedSafely
         */
        @WorkerThread
        fun yieldIfContendedSafely(sleepAmount: Long, sleepUnit: TimeUnit): Boolean

        /**
         * Equivalent to calling [.end]
         */
        @WorkerThread
        override fun close()
    }

    @IntDef(
        SQLiteDatabase.CONFLICT_ABORT.toLong(),
        SQLiteDatabase.CONFLICT_FAIL.toLong(),
        SQLiteDatabase.CONFLICT_IGNORE.toLong(),
        SQLiteDatabase.CONFLICT_NONE.toLong(),
        SQLiteDatabase.CONFLICT_REPLACE.toLong(),
        SQLiteDatabase.CONFLICT_ROLLBACK.toLong()
    )
    @kotlin.annotation.Retention(AnnotationRetention.SOURCE)
    private annotation class ConflictAlgorithm

    fun log(message: String, vararg args: Any?) {
        logger.log(
            if (args.isNotEmpty()) message.format(*args) else message
        )
    }

    class SqliteTransaction(val parent: SqliteTransaction?) : LinkedHashSet<String>(), SQLiteTransactionListener {

        var commit = false
        override fun onBegin() {}
        override fun onCommit() {
            commit = true
        }

        override fun onRollback() {}
        override fun toString(): String {
            val name = String.format("%08x", System.identityHashCode(this))
            return if (parent == null) name else "$name [$parent]"
        }
    }

    internal inner class DatabaseQuery(
        private val tables: Iterable<String>,
        private val query: SupportSQLiteQuery
    ) : SqlKite.Query() {

        override suspend fun runQuery(): Cursor {
            check(transactions.get() == null) { "Cannot execute observable query in a transaction." }
            val cursor: Cursor = readableDatabase.query(query)
            if (logging) {
                log("QUERY\n  tables: %s\n  sql: %s", tables, indentSql(query.sql))
            }
            return cursor
        }

        override fun toString(): String {
            return query.sql
        }

        fun selectsFor(strings: Set<String?>): Boolean {
            for (table in tables) {
                if (strings.contains(table)) {
                    return true
                }
            }
            return false
        }
    }

    companion object {

        private fun indentSql(sql: String): String {
            return sql.replace("\n", "\n       ")
        }

        private fun conflictString(@ConflictAlgorithm conflictAlgorithm: Int): String {
            return when (conflictAlgorithm) {
                SQLiteDatabase.CONFLICT_ABORT -> "abort"
                SQLiteDatabase.CONFLICT_FAIL -> "fail"
                SQLiteDatabase.CONFLICT_IGNORE -> "ignore"
                SQLiteDatabase.CONFLICT_NONE -> "none"
                SQLiteDatabase.CONFLICT_REPLACE -> "replace"
                SQLiteDatabase.CONFLICT_ROLLBACK -> "rollback"
                else -> "unknown ($conflictAlgorithm)"
            }
        }

        private fun defaultTransactionExecutor(): ExecutorService = Executors.newFixedThreadPool(4, object : ThreadFactory {
            private val mThreadId: AtomicInteger = AtomicInteger(0)
            override fun newThread(r: Runnable?): Thread {
                return Thread(r).apply {
                    name = "disk_io_${mThreadId.getAndIncrement()}"
                }
            }
        })
    }
}