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

import android.content.ContentProvider
import android.content.ContentResolver
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import app.cash.turbine.test
import com.google.common.truth.Truth
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestCoroutineDispatcher
import kotlinx.coroutines.test.runBlockingTest
import java.util.ArrayList
import java.util.LinkedHashMap
import androidx.test.rule.provider.ProviderTestRule
import org.junit.Before
import org.junit.Rule
import org.junit.Test


class KiteContentResolverTest {

    private val logs: MutableList<String?> = ArrayList()
    private val dispatcher = TestCoroutineDispatcher()
    private val killSwitch = MutableStateFlow(false)
    private lateinit var db: KiteContentResolver

    @get:Rule
    lateinit var providerRule: ProviderTestRule

    private val contentResolver: ContentResolver
        get() = providerRule.resolver

    @Before
    fun setUp() {
        providerRule = ProviderTestRule
            .Builder(TestContentProvider::class.java, AUTHORITY.authority!!)
            .build()

        val logger: SqlKite.Logger = SqlKite.Logger {
            logs.add(it)
        }

        db = KiteContentResolver(contentResolver, logger, dispatcher) { upstream ->
            upstream.takeWhile { !killSwitch.value }
        }
    }

    @Test
    fun testLoggerEnabled() = runBlocking {
        db.setLoggingEnabled(true)
        db.createQuery(TABLE).drop(1).test {
            contentResolver.insert(TABLE, values("key1", "value1"))
            awaitItemAndRunQuery()
                .hasRow("key1", "value1")
                .isExhausted()
        }

        Truth.assertThat(logs).isNotEmpty()
    }

    @Test
    fun testLoggerDisabled() {
        db.setLoggingEnabled(false)
        contentResolver.insert(TABLE, values("key1", "value1"))
        Truth.assertThat(logs).isEmpty()
    }

    @Test
    fun testCreateQueryObservesInsert() = runBlocking {
        db.createQuery(TABLE).drop(1).test {
            contentResolver.insert(TABLE, values("key1", "val1"))
            awaitItemAndRunQuery()
                .hasRow("key1", "val1")
                .isExhausted()
        }
    }

    @Test
    fun testCreateQueryObservesUpdate() = runBlocking {
        contentResolver.insert(TABLE, values("key1", "val1"))
        db.createQuery(TABLE).test {
            awaitItemAndRunQuery().hasRow("key1", "val1").isExhausted()
            contentResolver.update(TABLE, values("key1", "val2"), null, null)
            awaitItemAndRunQuery().hasRow("key1", "val2").isExhausted()
        }
    }

    @Test
    fun testCreateQueryObservesDelete() = runBlocking {
        contentResolver.insert(TABLE, values("key1", "val1"))
        db.createQuery(TABLE).test {
            awaitItemAndRunQuery().hasRow("key1", "val1").isExhausted()
            contentResolver.delete(TABLE, null, null)
            awaitItemAndRunQuery().isExhausted()
        }
    }

    @Test
    fun testUnsubscribeDoesNotTrigger() = runBlockingTest {
        val queryCollector = launch {
            db.createQuery(TABLE).test {
                awaitItemAndRunQuery().isExhausted()
            }
        }
        queryCollector.cancel()
        contentResolver.insert(TABLE, values("key1", "val1"))

        Truth.assertThat(logs).isEmpty()
    }

    @Test
    fun testQueryNotNotifiedWhenQueryTransformerDisposed() = runBlocking {
        db.createQuery(TABLE).test {
            awaitItemAndRunQuery().isExhausted()
            killSwitch.emit(true)
            contentResolver.insert(TABLE, values("key1", "val1"))
        }
    }

    private fun values(key: String, value: String): ContentValues {
        val result = ContentValues()
        result.put(KEY, key)
        result.put(VALUE, value)
        return result
    }

    class TestContentProvider: ContentProvider() {

        private val storage: MutableMap<String, String> = LinkedHashMap()

        override fun onCreate(): Boolean = true

        override fun getType(uri: Uri): String {
            return TABLE.toString()
        }

        override fun insert(uri: Uri, values: ContentValues?): Uri? {
            values ?: return null
            storage[values.getAsString(KEY)] = values.getAsString(VALUE)
            requireContext().contentResolver.notifyChange(uri, null)
            return Uri.parse(AUTHORITY.toString() + "/" + values.getAsString(KEY))
        }

        override fun update(
            uri: Uri,
            values: ContentValues?,
            selection: String?,
            selectionArgs: Array<String>?
        ): Int {
            values ?: return storage.size
            for (key in storage.keys) {
                storage[key] = values.getAsString(VALUE)
            }
            requireContext().contentResolver.notifyChange(uri, null)
            return storage.size
        }

        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int {
            val result = storage.size
            storage.clear()
            requireContext().contentResolver.notifyChange(uri, null)
            return result
        }

        override fun query(
            uri: Uri,
            projection: Array<String>?,
            selection: String?,
            selectionArgs: Array<String>?,
            sortOrder: String?
        ): Cursor {
            val result = MatrixCursor(arrayOf(KEY, VALUE))
            for ((key, value) in storage) {
                result.addRow(arrayOf<Any>(key, value))
            }
            return result
        }
    }

    companion object {

        private val AUTHORITY: Uri = Uri.parse("content://test_authority")
        private val TABLE: Uri = AUTHORITY.buildUpon().appendPath("test_table").build()
        private const val KEY = "test_key"
        private const val VALUE = "test_value"
    }
}