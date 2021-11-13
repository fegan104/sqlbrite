///*
// * Copyright (C) 2015 Square, Inc.
// *
// * Licensed under the Apache License, Version 2.0 (the "License");
// * you may not use this file except in compliance with the License.
// * You may obtain a copy of the License at
// *
// *      http://www.apache.org/licenses/LICENSE-2.0
// *
// * Unless required by applicable law or agreed to in writing, software
// * distributed under the License is distributed on an "AS IS" BASIS,
// * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// * See the License for the specific language governing permissions and
// * limitations under the License.
// */
//package com.squareup.sqlbrite3
//
//import android.content.ContentResolver
//import android.content.ContentValues
//import android.database.Cursor
//import android.database.MatrixCursor
//import android.net.Uri
//import android.test.ProviderTestCase2
//import android.test.mock.MockContentProvider
//import com.google.common.truth.Truth
//import kotlinx.coroutines.Dispatchers
//import kotlinx.coroutines.flow.MutableSharedFlow
//import kotlinx.coroutines.flow.MutableStateFlow
//import kotlinx.coroutines.flow.takeWhile
//import kotlinx.coroutines.runBlocking
//import java.lang.Exception
//import java.util.ArrayList
//import java.util.LinkedHashMap
//import java.util.Observable
//
//class BriteContentResolverTest : ProviderTestCase2<BriteContentResolverTest.TestContentProvider>(TestContentProvider::class.java, AUTHORITY.authority) {
//
//    private val logs: MutableList<String?> = ArrayList()
//    private val refactorObserver: RecordingObserver = BlockingRecordingObserver()
//    private val dispatcher = Dispatchers.Default
//    private val killSwitch = MutableStateFlow<Boolean>(true)
//    private var contentResolver: ContentResolver? = null
//    private var db: BriteContentResolver? = null
//
//    @Throws(Exception::class)
//    override fun setUp() {
//        super.setUp()
//        contentResolver = mockContentResolver
//        val logger: SqlBrite.Logger = object : SqlBrite.Logger {
//            override fun log(message: String?) {
//                logs.add(message)
//            }
//        }
//
//        db = BriteContentResolver(contentResolver!!, logger, dispatcher) { upstream ->
//            upstream.takeWhile { killSwitch.value }
//        }
//        provider.init(context.contentResolver)
//    }
//
//
//    fun testLoggerEnabled() {
//        db!!.setLoggingEnabled(true)
//        db!!.createQuery(TABLE, null, null, null, null, false).subscribe(refactorObserver)
//        refactorObserver.assertCursor().isExhausted()
//        contentResolver.insert(TABLE, values("key1", "value1"))
//        refactorObserver.assertCursor().hasRow("key1", "value1").isExhausted()
//        Truth.assertThat(logs).isNotEmpty()
//    }
//
//    fun testLoggerDisabled() {
//        db!!.setLoggingEnabled(false)
//        contentResolver.insert(TABLE, values("key1", "value1"))
//        Truth.assertThat(logs).isEmpty()
//    }
//
//    fun testCreateQueryObservesInsert() {
//        db!!.createQuery(TABLE, null, null, null, null, false).subscribe(refactorObserver)
//        refactorObserver.assertCursor().isExhausted()
//        contentResolver.insert(TABLE, values("key1", "val1"))
//        refactorObserver.assertCursor().hasRow("key1", "val1").isExhausted()
//    }
//
//    fun testCreateQueryObservesUpdate() {
//        contentResolver.insert(TABLE, values("key1", "val1"))
//        db!!.createQuery(TABLE, null, null, null, null, false).subscribe(refactorObserver)
//        refactorObserver.assertCursor().hasRow("key1", "val1").isExhausted()
//        contentResolver.update(TABLE, values("key1", "val2"), null, null)
//        refactorObserver.assertCursor().hasRow("key1", "val2").isExhausted()
//    }
//
//    fun testCreateQueryObservesDelete() {
//        contentResolver.insert(TABLE, values("key1", "val1"))
//        db!!.createQuery(TABLE, null, null, null, null, false).subscribe(refactorObserver)
//        refactorObserver.assertCursor().hasRow("key1", "val1").isExhausted()
//        contentResolver.delete(TABLE, null, null)
//        refactorObserver.assertCursor().isExhausted()
//    }
//
//    fun testUnsubscribeDoesNotTrigger() {
//        db!!.createQuery(TABLE, null, null, null, null, false).subscribe(refactorObserver)
//        refactorObserver.assertCursor().isExhausted()
//        refactorObserver.dispose()
//        contentResolver.insert(TABLE, values("key1", "val1"))
//        refactorObserver.assertNoMoreEvents()
//        Truth.assertThat(logs).isEmpty()
//    }
//
//    fun testQueryNotNotifiedWhenQueryTransformerDisposed() = runBlocking{
//        db!!.createQuery(TABLE, null, null, null, null, false).subscribe(refactorObserver)
//        refactorObserver.assertCursor().isExhausted()
//        killSwitch.emit(false)
//        refactorObserver.assertIsCompleted()
//        contentResolver.insert(TABLE, values("key1", "val1"))
//        refactorObserver.assertNoMoreEvents()
//    }
//
//    fun testInitialValueAndTriggerUsesScheduler() {
//        scheduler.runTasksImmediately(false)
//        db!!.createQuery(TABLE, null, null, null, null, false).subscribe(refactorObserver)
//        refactorObserver.assertNoMoreEvents()
//        scheduler.triggerActions()
//        refactorObserver.assertCursor().isExhausted()
//        contentResolver.insert(TABLE, values("key1", "val1"))
//        refactorObserver.assertNoMoreEvents()
//        scheduler.triggerActions()
//        refactorObserver.assertCursor().hasRow("key1", "val1").isExhausted()
//    }
//
//    private fun values(key: String, value: String): ContentValues {
//        val result = ContentValues()
//        result.put(KEY, key)
//        result.put(VALUE, value)
//        return result
//    }
//
//    class TestContentProvider : MockContentProvider() {
//
//        private val storage: MutableMap<String, String> = LinkedHashMap()
//        private var contentResolver: ContentResolver? = null
//        fun init(contentResolver: ContentResolver?) {
//            this.contentResolver = contentResolver
//        }
//
//        override fun insert(uri: Uri, values: ContentValues): Uri {
//            storage[values.getAsString(KEY)] = values.getAsString(VALUE)
//            contentResolver.notifyChange(uri, null)
//            return Uri.parse(AUTHORITY.toString() + "/" + values.getAsString(KEY))
//        }
//
//        override fun update(
//            uri: Uri, values: ContentValues, selection: String,
//            selectionArgs: Array<String>
//        ): Int {
//            for (key in storage.keys) {
//                storage[key] = values.getAsString(VALUE)
//            }
//            contentResolver.notifyChange(uri, null)
//            return storage.size
//        }
//
//        override fun delete(uri: Uri, selection: String, selectionArgs: Array<String>): Int {
//            val result = storage.size
//            storage.clear()
//            contentResolver.notifyChange(uri, null)
//            return result
//        }
//
//        override fun query(
//            uri: Uri, projection: Array<String>, selection: String,
//            selectionArgs: Array<String>, sortOrder: String
//        ): Cursor {
//            val result = MatrixCursor(arrayOf(KEY, VALUE))
//            for ((key, value) in storage) {
//                result.addRow(arrayOf<Any>(key, value))
//            }
//            return result
//        }
//    }
//
//    companion object {
//
//        private val AUTHORITY = Uri.parse("content://test_authority")
//        private val TABLE = AUTHORITY.buildUpon().appendPath("test_table").build()
//        private const val KEY = "test_key"
//        private const val VALUE = "test_value"
//    }
//}