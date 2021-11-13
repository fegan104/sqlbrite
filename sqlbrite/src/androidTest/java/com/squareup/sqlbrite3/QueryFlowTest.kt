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
package com.squareup.sqlbrite3

import android.database.Cursor
import android.database.MatrixCursor
import app.cash.turbine.test
import com.google.common.truth.Truth
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runBlockingTest
import org.junit.Test

class QueryFlowTest {

    @Test
    fun mapToListThrowsFromQueryRun() = runBlockingTest {
        val error = IllegalStateException("test exception")

        val query: SqlBrite.Query = object : SqlBrite.Query() {
            override suspend fun runQuery(): Cursor {
                throw error
            }
        }
        flowOf(query)
            .mapToList { throw AssertionError("Must not be called") }
            .test {
                Truth.assertThat(awaitError()).hasMessageThat().isEqualTo(error.message)
            }
    }

    @Test
    fun mapToListThrowsFromMapFunction() = runBlockingTest {
        val query: SqlBrite.Query = object : SqlBrite.Query() {
            override suspend fun runQuery(): Cursor {
                val cursor = MatrixCursor(arrayOf("col1"))
                cursor.addRow(arrayOf("value1"))
                return cursor
            }
        }
        val error = IllegalStateException("test exception")
        flowOf(query)
            .mapToList { throw error }
            .test {
                Truth.assertThat(awaitError()).hasMessageThat().isEqualTo(error.message)
            }
    }
}