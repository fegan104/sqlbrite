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

import android.annotation.TargetApi
import android.arch.persistence.db.SimpleSQLiteQuery
import android.arch.persistence.db.SupportSQLiteDatabase
import android.arch.persistence.db.SupportSQLiteOpenHelper
import android.arch.persistence.db.SupportSQLiteStatement
import android.arch.persistence.db.framework.FrameworkSQLiteOpenHelperFactory
import android.content.ContentValues
import android.database.SQLException
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.os.Build
import android.support.test.InstrumentationRegistry
import android.support.test.filters.SdkSuppress
import android.support.test.runner.AndroidJUnit4
import app.cash.turbine.test
import com.google.common.truth.Truth
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestCoroutineDispatcher
import kotlinx.coroutines.test.runBlockingTest
import org.junit.Assert
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.io.Closeable
import java.io.IOException
import java.lang.Thread.sleep
import java.util.ArrayList
import java.util.Arrays
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit


@RunWith(AndroidJUnit4::class) //
class BriteDatabaseTest {

    private val testDb = TestDb()
    private val logs: MutableList<String?> = ArrayList()
    private val dispatcher = TestCoroutineDispatcher()
    private var killSwitch: Boolean = true

    @get:Rule
    val dbFolder: TemporaryFolder = TemporaryFolder()
    private lateinit var real: SupportSQLiteDatabase
    private lateinit var db: BriteDatabase

    @Before
    @Throws(IOException::class)
    fun setUp() {
        killSwitch = true
        val configuration = SupportSQLiteOpenHelper.Configuration
            .builder(InstrumentationRegistry.getContext())
            .callback(testDb)
            .name(dbFolder.newFile().path)
            .build()
        val factory: SupportSQLiteOpenHelper.Factory = FrameworkSQLiteOpenHelperFactory()
        val helper: SupportSQLiteOpenHelper = factory.create(configuration)
        real = helper.writableDatabase
        val logger: SqlBrite.Logger = object : SqlBrite.Logger {
            override fun log(message: String?) {
                logs.add(message)
            }
        }


        db = BriteDatabase(helper, logger, dispatcher, Executors.newSingleThreadExecutor()) { upstream ->
            upstream.takeWhile { killSwitch }
        }
    }

    @Test
    fun loggerEnabled() {
        db.setLoggingEnabled(true)
        db.insert(TestDb.TABLE_EMPLOYEE, SQLiteDatabase.CONFLICT_NONE, TestDb.employee("john", "John Johnson"))
        Truth.assertThat(logs).isNotEmpty()
    }

    @Test
    fun loggerDisabled() {
        db.setLoggingEnabled(false)
        db.insert(TestDb.TABLE_EMPLOYEE, SQLiteDatabase.CONFLICT_NONE, TestDb.employee("john", "John Johnson"))
        Truth.assertThat(logs).isEmpty()
    }

    @Test
    fun loggerIndentsSqlForCreateQuery() = runBlockingTest {
        db.setLoggingEnabled(true)
        db.createQuery(TestDb.TABLE_EMPLOYEE, "SELECT\n1").test {
            awaitItem().runQuery().close()
            Truth.assertThat(logs).containsExactly(
                """QUERY
  tables: [employee]
  sql: SELECT
       1"""
            )
        }
    }

    @Test
    fun loggerIndentsSqlForQuery() {
        db.setLoggingEnabled(true)
        db.query("SELECT\n1").close()
        Truth.assertThat(logs).containsExactly(
            """QUERY
  sql: SELECT
       1
  args: []"""
        )
    }

    @Test
    fun loggerIndentsSqlForExecute() {
        db.setLoggingEnabled(true)
        db.execute("PRAGMA\ncompile_options")
        Truth.assertThat(logs).containsExactly(
            """EXECUTE
  sql: PRAGMA
       compile_options"""
        )
    }

    @Test
    fun loggerIndentsSqlForExecuteWithArgs() {
        db.setLoggingEnabled(true)
        db.execute("PRAGMA\ncompile_options", *arrayOfNulls<Any>(0))
        Truth.assertThat(logs).containsExactly(
            """EXECUTE
  sql: PRAGMA
       compile_options
  args: []"""
        )
    }

    @Test
    fun closePropagates() {
        db.close()
        Truth.assertThat(real?.isOpen).isFalse()
    }

    @Test
    fun query() = runBlockingTest {
        db.createQuery(TestDb.TABLE_EMPLOYEE, TestDb.SELECT_EMPLOYEES).test {
            awaitItem().runQuery()
                .hasRow("alice", "Alice Allison")
                .hasRow("bob", "Bob Bobberson")
                .hasRow("eve", "Eve Evenson")
                .isExhausted()
        }
    }

    @Test
    fun queryWithQueryObject() = runBlockingTest {
        db.createQuery(TestDb.TABLE_EMPLOYEE, SimpleSQLiteQuery(TestDb.SELECT_EMPLOYEES)).test {
            awaitItem().runQuery()
                .hasRow("alice", "Alice Allison")
                .hasRow("bob", "Bob Bobberson")
                .hasRow("eve", "Eve Evenson")
                .isExhausted()
        }
    }

    @Test
    fun queryMapToList() = runBlockingTest {
        val employees: List<TestDb.Employee?> = db.createQuery(TestDb.TABLE_EMPLOYEE, TestDb.SELECT_EMPLOYEES)
            .mapToList(TestDb.Employee.MAPPER)
            .first()
        Truth.assertThat(employees).containsExactly( //
            TestDb.Employee("alice", "Alice Allison"),  //
            TestDb.Employee("bob", "Bob Bobberson"),  //
            TestDb.Employee("eve", "Eve Evenson")
        )
    }

    @Test
    fun queryMapToOne() = runBlockingTest {
        val employees: TestDb.Employee = db.createQuery(TestDb.TABLE_EMPLOYEE, TestDb.SELECT_EMPLOYEES + " LIMIT 1")
            .mapToOne(TestDb.Employee.MAPPER)
            .first()
        Truth.assertThat(employees).isEqualTo(TestDb.Employee("alice", "Alice Allison"))
    }

    @Test
    fun queryMapToOneOrDefault() = runBlockingTest {
        val employees: TestDb.Employee = db.createQuery(TestDb.TABLE_EMPLOYEE, TestDb.SELECT_EMPLOYEES + " LIMIT 1")
            .mapToOneOrDefault(TestDb.Employee("wrong", "Wrong Person"), TestDb.Employee.MAPPER)
            .first()
        Truth.assertThat(employees).isEqualTo(TestDb.Employee("alice", "Alice Allison"))
    }

    @Test
    fun badQueryCallsError() = runBlockingTest {
        db.createQuery(TestDb.TABLE_EMPLOYEE, "SELECT * FROM missing")
            .map { query -> query.runQuery() }
            .test {
                Truth.assertThat(awaitError()).hasMessageThat().contains("no such table: missing")
            }
    }

    @Test
    fun queryWithArgs() = runBlockingTest {
        db.createQuery(
            TestDb.TABLE_EMPLOYEE, TestDb.SELECT_EMPLOYEES + " WHERE " + TestDb.EmployeeTable.USERNAME + " = ?", "bob"
        ).test {
            awaitItem().runQuery()
                .hasRow("bob", "Bob Bobberson")
                .isExhausted()
        }
    }

    @Test
    fun queryObservesInsert() = runBlockingTest {
        db.createQuery(TestDb.TABLE_EMPLOYEE, TestDb.SELECT_EMPLOYEES).test {
            awaitItem().runQuery()
                .hasRow("alice", "Alice Allison")
                .hasRow("bob", "Bob Bobberson")
                .hasRow("eve", "Eve Evenson")
                .isExhausted()
            db.insert(TestDb.TABLE_EMPLOYEE, SQLiteDatabase.CONFLICT_NONE, TestDb.employee("john", "John Johnson"))
            awaitItem().runQuery()
                .hasRow("alice", "Alice Allison")
                .hasRow("bob", "Bob Bobberson")
                .hasRow("eve", "Eve Evenson")
                .hasRow("john", "John Johnson")
                .isExhausted()
        }

    }

    @Test
    fun queryInitialValueAndTriggerUsesScheduler() = runBlockingTest {
        db.createQuery(TestDb.TABLE_EMPLOYEE, TestDb.SELECT_EMPLOYEES).test {
            awaitItem().runQuery()
                .hasRow("alice", "Alice Allison")
                .hasRow("bob", "Bob Bobberson")
                .hasRow("eve", "Eve Evenson")
                .isExhausted()
            db.insert(TestDb.TABLE_EMPLOYEE, SQLiteDatabase.CONFLICT_NONE, TestDb.employee("john", "John Johnson"))

            awaitItem().runQuery()
                .hasRow("alice", "Alice Allison")
                .hasRow("bob", "Bob Bobberson")
                .hasRow("eve", "Eve Evenson")
                .hasRow("john", "John Johnson")
                .isExhausted()
        }
    }

    @Test
    fun queryNotNotifiedWhenInsertFails() = runBlockingTest {
        db.createQuery(TestDb.TABLE_EMPLOYEE, TestDb.SELECT_EMPLOYEES).test {
            awaitItem().runQuery()
                .hasRow("alice", "Alice Allison")
                .hasRow("bob", "Bob Bobberson")
                .hasRow("eve", "Eve Evenson")
                .isExhausted()
            db.insert(TestDb.TABLE_EMPLOYEE, SQLiteDatabase.CONFLICT_IGNORE, TestDb.employee("bob", "Bob Bobberson"))
        }
    }

    @Test
    fun queryNotNotifiedWhenQueryTransformerUnsubscribes() = runBlockingTest {
        db.createQuery(TestDb.TABLE_EMPLOYEE, TestDb.SELECT_EMPLOYEES).test {
            awaitItem().runQuery()
                .hasRow("alice", "Alice Allison")
                .hasRow("bob", "Bob Bobberson")
                .hasRow("eve", "Eve Evenson")
                .isExhausted()
            killSwitch = false
            db.insert(TestDb.TABLE_EMPLOYEE, SQLiteDatabase.CONFLICT_NONE, TestDb.employee("john", "John Johnson"))
            awaitComplete()
        }
    }

    @Test
    fun queryObservesUpdate() = runBlockingTest {
        db.createQuery(TestDb.TABLE_EMPLOYEE, TestDb.SELECT_EMPLOYEES).test {
            awaitItem().runQuery()
                .hasRow("alice", "Alice Allison")
                .hasRow("bob", "Bob Bobberson")
                .hasRow("eve", "Eve Evenson")
                .isExhausted()
            val values = ContentValues()
            values.put(TestDb.EmployeeTable.NAME, "Robert Bobberson")
            db.update(TestDb.TABLE_EMPLOYEE, SQLiteDatabase.CONFLICT_NONE, values, TestDb.EmployeeTable.USERNAME + " = 'bob'")
            awaitItem().runQuery()
                .hasRow("alice", "Alice Allison")
                .hasRow("bob", "Robert Bobberson")
                .hasRow("eve", "Eve Evenson")
                .isExhausted()
        }
    }

    @Test
    fun queryNotNotifiedWhenUpdateAffectsZeroRows() = runBlockingTest {
        db.createQuery(TestDb.TABLE_EMPLOYEE, TestDb.SELECT_EMPLOYEES).test {
            awaitItem().runQuery()
                .hasRow("alice", "Alice Allison")
                .hasRow("bob", "Bob Bobberson")
                .hasRow("eve", "Eve Evenson")
                .isExhausted()
            val values = ContentValues()
            values.put(TestDb.EmployeeTable.NAME, "John Johnson")
            db.update(TestDb.TABLE_EMPLOYEE, SQLiteDatabase.CONFLICT_NONE, values, TestDb.EmployeeTable.USERNAME + " = 'john'")
        }
    }

    @Test
    fun queryObservesDelete() = runBlockingTest {
        db.createQuery(TestDb.TABLE_EMPLOYEE, TestDb.SELECT_EMPLOYEES).test {
            awaitItem().runQuery()
                .hasRow("alice", "Alice Allison")
                .hasRow("bob", "Bob Bobberson")
                .hasRow("eve", "Eve Evenson")
                .isExhausted()
            db.delete(TestDb.TABLE_EMPLOYEE, TestDb.EmployeeTable.USERNAME + " = 'bob'")
            awaitItem().runQuery()
                .hasRow("alice", "Alice Allison")
                .hasRow("eve", "Eve Evenson")
                .isExhausted()
        }
    }

    @Test
    fun queryNotNotifiedWhenDeleteAffectsZeroRows() = runBlockingTest {
        db.createQuery(TestDb.TABLE_EMPLOYEE, TestDb.SELECT_EMPLOYEES).test {
            awaitItem().runQuery()
                .hasRow("alice", "Alice Allison")
                .hasRow("bob", "Bob Bobberson")
                .hasRow("eve", "Eve Evenson")
                .isExhausted()
            db.delete(TestDb.TABLE_EMPLOYEE, TestDb.EmployeeTable.USERNAME + " = 'john'")
        }
    }

    @Test
    fun queryMultipleTables() = runBlockingTest {
        db.createQuery(TestDb.BOTH_TABLES, TestDb.SELECT_MANAGER_LIST).test {
            awaitItem().runQuery()
                .hasRow("Eve Evenson", "Alice Allison")
                .isExhausted()
        }
    }

    @Test
    fun queryMultipleTablesWithQueryObject() = runBlockingTest {
        db.createQuery(TestDb.BOTH_TABLES, SimpleSQLiteQuery(TestDb.SELECT_MANAGER_LIST)).test {
            awaitItem().runQuery()
                .hasRow("Eve Evenson", "Alice Allison")
                .isExhausted()
        }
    }

    @Test
    fun queryMultipleTablesObservesChanges() = runBlockingTest {
        db.createQuery(TestDb.BOTH_TABLES, TestDb.SELECT_MANAGER_LIST).test {
            awaitItem().runQuery()
                .hasRow("Eve Evenson", "Alice Allison")
                .isExhausted()

            // A new employee triggers, despite the fact that it's not in our result set.
            db.insert(TestDb.TABLE_EMPLOYEE, SQLiteDatabase.CONFLICT_NONE, TestDb.employee("john", "John Johnson"))
            awaitItem().runQuery()
                .hasRow("Eve Evenson", "Alice Allison")
                .isExhausted()

            // A new manager also triggers and it is in our result set.
            db.insert(TestDb.TABLE_MANAGER, SQLiteDatabase.CONFLICT_NONE, TestDb.manager(testDb.bobId, testDb.eveId))
            awaitItem().runQuery()
                .hasRow("Eve Evenson", "Alice Allison")
                .hasRow("Bob Bobberson", "Eve Evenson")
                .isExhausted()
        }
    }

    @Test
    fun queryMultipleTablesObservesChangesOnlyOnce() = runBlockingTest {
        // Employee table is in this list twice. We should still only be notified once for a change.
        val tables = Arrays.asList(TestDb.TABLE_EMPLOYEE, TestDb.TABLE_MANAGER, TestDb.TABLE_EMPLOYEE)
        db.createQuery(tables, TestDb.SELECT_MANAGER_LIST).test {
            awaitItem().runQuery()
                .hasRow("Eve Evenson", "Alice Allison")
                .isExhausted()

            val values = ContentValues()
            values.put(TestDb.EmployeeTable.NAME, "Even Evenson")
            db.update(TestDb.TABLE_EMPLOYEE, SQLiteDatabase.CONFLICT_NONE, values, TestDb.EmployeeTable.USERNAME + " = 'eve'")
            awaitItem().runQuery()
                .hasRow("Even Evenson", "Alice Allison")
                .isExhausted()
        }
    }

    //    @Test
    //    fun queryNotNotifiedAfterDispose() = runBlockingTest {
    //        coroutineScope {
    //            var queryFlow: Flow<SqlBrite.Query>? = null
    //            val job = launch {
    //                queryFlow = db.createQuery(TestDb.TABLE_EMPLOYEE, TestDb.SELECT_EMPLOYEES)
    //                queryFlow!!.test {
    //                    awaitItem().runQuery()
    //                        .hasRow("alice", "Alice Allison")
    //                        .hasRow("bob", "Bob Bobberson")
    //                        .hasRow("eve", "Eve Evenson")
    //                        .isExhausted()
    //                }
    //            }
    //            job.cancel()
    //            db.insert(TestDb.TABLE_EMPLOYEE, SQLiteDatabase.CONFLICT_NONE, TestDb.employee("john", "John Johnson"))
    //            queryFlow!!.test {
    //                awaitComplete()
    //            }
    //        }
    //    }
    //
    //        @Test
    //        fun queryOnlyNotifiedAfterSubscribe() = runBlockingTest {
    //            val query: Flow<SqlBrite.Query> = db.createQuery(TestDb.TABLE_EMPLOYEE, TestDb.SELECT_EMPLOYEES)
    //            query.test { }
    //            db.insert(TestDb.TABLE_EMPLOYEE, SQLiteDatabase.CONFLICT_NONE, TestDb.employee("john", "John Johnson"))
    //            query.test { }
    //            query.test {
    //                awaitItem().runQuery()
    //                    .hasRow("alice", "Alice Allison")
    //                    .hasRow("bob", "Bob Bobberson")
    //                    .hasRow("eve", "Eve Evenson")
    //                    .hasRow("john", "John Johnson")
    //                    .isExhausted()
    //            }
    //        }

    @Test
    fun executeSqlNoTrigger() = runBlockingTest {
        db.createQuery(TestDb.TABLE_EMPLOYEE, TestDb.SELECT_EMPLOYEES)
            .drop(1) // Skip initial
            .test {
                db.execute("UPDATE " + TestDb.TABLE_EMPLOYEE + " SET " + TestDb.EmployeeTable.NAME + " = 'Zach'")
            }
    }

    @Test
    fun executeSqlWithArgsNoTrigger() = runBlockingTest {
        db.createQuery(TestDb.TABLE_EMPLOYEE, TestDb.SELECT_EMPLOYEES)
            .drop(1) // Skip initial
            .test {
                db.execute("UPDATE " + TestDb.TABLE_EMPLOYEE + " SET " + TestDb.EmployeeTable.NAME + " = ?", "Zach")
            }
    }

    @Test
    fun executeSqlAndTrigger() = runBlockingTest {
        db.createQuery(TestDb.TABLE_EMPLOYEE, TestDb.SELECT_EMPLOYEES).test {
            awaitItem().runQuery()
                .hasRow("alice", "Alice Allison")
                .hasRow("bob", "Bob Bobberson")
                .hasRow("eve", "Eve Evenson")
                .isExhausted()
            db.executeAndTrigger(
                TestDb.TABLE_EMPLOYEE,
                "UPDATE " + TestDb.TABLE_EMPLOYEE + " SET " + TestDb.EmployeeTable.NAME + " = 'Zach'"
            )
            awaitItem().runQuery()
                .hasRow("alice", "Zach")
                .hasRow("bob", "Zach")
                .hasRow("eve", "Zach")
                .isExhausted()
        }
    }

    @Test
    fun executeSqlAndTriggerMultipleTables() = runBlockingTest {
        coroutineScope {
            var managerFlow: Flow<SqlBrite.Query>? = null
            var employeeFlow: Flow<SqlBrite.Query>? = null
            launch {
                managerFlow = db.createQuery(TestDb.TABLE_MANAGER, TestDb.SELECT_MANAGER_LIST)
                managerFlow!!.test {
                    awaitItem().runQuery()
                        .hasRow("Eve Evenson", "Alice Allison")
                        .isExhausted()
                }
            }
            launch {
                employeeFlow = db.createQuery(TestDb.TABLE_EMPLOYEE, TestDb.SELECT_EMPLOYEES)
                employeeFlow!!.test {
                    awaitItem().runQuery()
                        .hasRow("alice", "Alice Allison")
                        .hasRow("bob", "Bob Bobberson")
                        .hasRow("eve", "Eve Evenson")
                        .isExhausted()
                }
            }

            db.executeAndTrigger(
                TestDb.BOTH_TABLES.toSet(),
                "UPDATE " + TestDb.TABLE_EMPLOYEE + " SET " + TestDb.EmployeeTable.NAME + " = 'Zach'"
            )
            managerFlow!!.test {
                awaitItem().runQuery()
                    .hasRow("Zach", "Zach")
                    .isExhausted()
            }
            employeeFlow!!.test {
                awaitItem().runQuery()
                    .hasRow("alice", "Zach")
                    .hasRow("bob", "Zach")
                    .hasRow("eve", "Zach")
                    .isExhausted()
            }
        }
    }

    @Test
    fun executeSqlAndTriggerWithNoTables() = runBlockingTest {
        db.createQuery(TestDb.TABLE_MANAGER, TestDb.SELECT_MANAGER_LIST).test {
            awaitItem().runQuery()
                .hasRow("Eve Evenson", "Alice Allison")
                .isExhausted()
            db.executeAndTrigger(
                emptySet(),
                "UPDATE " + TestDb.TABLE_EMPLOYEE + " SET " + TestDb.EmployeeTable.NAME + " = 'Zach'"
            )
        }
    }

    @Test
    fun executeSqlThrowsAndDoesNotTrigger() = runBlockingTest {
        db.createQuery(TestDb.TABLE_EMPLOYEE, TestDb.SELECT_EMPLOYEES)
            .drop(1) // Skip initial
            .test { }
        try {
            db.executeAndTrigger(
                TestDb.TABLE_EMPLOYEE,
                "UPDATE not_a_table SET " + TestDb.EmployeeTable.NAME + " = 'Zach'"
            )
            Assert.fail()
        } catch (ignored: SQLException) {
        }
    }

    @Test
    fun executeSqlWithArgsAndTrigger() = runBlockingTest {
        db.createQuery(TestDb.TABLE_EMPLOYEE, TestDb.SELECT_EMPLOYEES).test {
            awaitItem().runQuery()
                .hasRow("alice", "Alice Allison")
                .hasRow("bob", "Bob Bobberson")
                .hasRow("eve", "Eve Evenson")
                .isExhausted()
            db.executeAndTrigger(
                TestDb.TABLE_EMPLOYEE,
                "UPDATE " + TestDb.TABLE_EMPLOYEE + " SET " + TestDb.EmployeeTable.NAME + " = ?", "Zach"
            )
            awaitItem().runQuery()
                .hasRow("alice", "Zach")
                .hasRow("bob", "Zach")
                .hasRow("eve", "Zach")
                .isExhausted()
        }
    }

    @Test
    fun executeSqlWithArgsThrowsAndDoesNotTrigger() = runBlockingTest {
        db.createQuery(TestDb.TABLE_EMPLOYEE, TestDb.SELECT_EMPLOYEES)
            .drop(1) // Skip initial
            .test { }
        try {
            db.executeAndTrigger(
                TestDb.TABLE_EMPLOYEE,
                "UPDATE not_a_table SET " + TestDb.EmployeeTable.NAME + " = ?", "Zach"
            )
            Assert.fail()
        } catch (ignored: SQLException) {
        }
    }

    @Test
    fun executeSqlWithArgsAndTriggerWithMultipleTables() = runBlockingTest {
        val managerFlow = db.createQuery(TestDb.TABLE_MANAGER, TestDb.SELECT_MANAGER_LIST)
        managerFlow.test {
            awaitItem().runQuery()
                .hasRow("Eve Evenson", "Alice Allison")
                .isExhausted()
        }

        val employeeFlow = db.createQuery(TestDb.TABLE_EMPLOYEE, TestDb.SELECT_EMPLOYEES)
        employeeFlow.test {
            awaitItem().runQuery()
                .hasRow("alice", "Alice Allison")
                .hasRow("bob", "Bob Bobberson")
                .hasRow("eve", "Eve Evenson")
                .isExhausted()
        }

        db.executeAndTrigger(
            TestDb.BOTH_TABLES.toSet(),
            "UPDATE " + TestDb.TABLE_EMPLOYEE + " SET " + TestDb.EmployeeTable.NAME + " = ?", "Zach"
        )
        managerFlow.test {
            awaitItem().runQuery()
                .hasRow("Zach", "Zach")
                .isExhausted()
        }
        employeeFlow.test {
            awaitItem().runQuery()
                .hasRow("alice", "Zach")
                .hasRow("bob", "Zach")
                .hasRow("eve", "Zach")
                .isExhausted()
        }
    }


    @Test
    fun executeSqlWithArgsAndTriggerWithNoTables() = runBlockingTest {
        db.createQuery(TestDb.BOTH_TABLES, TestDb.SELECT_MANAGER_LIST).test {
            awaitItem().runQuery()
                .hasRow("Eve Evenson", "Alice Allison")
                .isExhausted()
            db.executeAndTrigger(
                emptySet(),
                "UPDATE " + TestDb.TABLE_EMPLOYEE + " SET " + TestDb.EmployeeTable.NAME + " = ?", "Zach"
            )
        }
    }

    @Test
    fun executeInsertAndTrigger() = runBlockingTest {
        val statement: SupportSQLiteStatement = real.compileStatement(
            "INSERT INTO "
                + TestDb.TABLE_EMPLOYEE + " (" + TestDb.EmployeeTable.NAME + ", " + TestDb.EmployeeTable.USERNAME + ") "
                + "VALUES ('Chad Chadson', 'chad')"
        )
        db.createQuery(TestDb.TABLE_EMPLOYEE, TestDb.SELECT_EMPLOYEES).test {
            awaitItem().runQuery()
                .hasRow("alice", "Alice Allison")
                .hasRow("bob", "Bob Bobberson")
                .hasRow("eve", "Eve Evenson")
                .isExhausted()
            db.executeInsert(TestDb.TABLE_EMPLOYEE, statement)
            awaitItem().runQuery()
                .hasRow("alice", "Alice Allison")
                .hasRow("bob", "Bob Bobberson")
                .hasRow("eve", "Eve Evenson")
                .hasRow("chad", "Chad Chadson")
                .isExhausted()
        }

    }

    @Test
    fun executeInsertAndDontTrigger() = runBlockingTest {
        val statement: SupportSQLiteStatement = real.compileStatement(
            "INSERT OR IGNORE INTO "
                + TestDb.TABLE_EMPLOYEE + " (" + TestDb.EmployeeTable.NAME + ", " + TestDb.EmployeeTable.USERNAME + ") "
                + "VALUES ('Alice Allison', 'alice')"
        )
        db.createQuery(TestDb.TABLE_EMPLOYEE, TestDb.SELECT_EMPLOYEES).test {
            awaitItem().runQuery()
                .hasRow("alice", "Alice Allison")
                .hasRow("bob", "Bob Bobberson")
                .hasRow("eve", "Eve Evenson")
                .isExhausted()
            db.executeInsert(TestDb.TABLE_EMPLOYEE, statement)
        }
    }

    @Test
    fun executeInsertAndTriggerMultipleTables() = runBlockingTest {
        val statement: SupportSQLiteStatement = real.compileStatement(
            "INSERT INTO "
                + TestDb.TABLE_EMPLOYEE + " (" + TestDb.EmployeeTable.NAME + ", " + TestDb.EmployeeTable.USERNAME + ") "
                + "VALUES ('Chad Chadson', 'chad')"
        )
        val managerObserver = db.createQuery(TestDb.TABLE_MANAGER, TestDb.SELECT_MANAGER_LIST)
        managerObserver.test {
            awaitItem().runQuery()
                .hasRow("Eve Evenson", "Alice Allison")
                .isExhausted()
        }
        val employeeFlow = db.createQuery(TestDb.TABLE_EMPLOYEE, TestDb.SELECT_EMPLOYEES)
        employeeFlow.test {
            awaitItem().runQuery()
                .hasRow("alice", "Alice Allison")
                .hasRow("bob", "Bob Bobberson")
                .hasRow("eve", "Eve Evenson")
                .isExhausted()
        }
        val employeeAndManagerTables = Collections.unmodifiableSet(
            HashSet(
                TestDb.BOTH_TABLES
            )
        )
        db.executeInsert(employeeAndManagerTables, statement)
        managerObserver.test {
            awaitItem().runQuery()
                .hasRow("Eve Evenson", "Alice Allison")
                .isExhausted()
        }
        employeeFlow.test {
            awaitItem().runQuery()
                .hasRow("alice", "Alice Allison")
                .hasRow("bob", "Bob Bobberson")
                .hasRow("eve", "Eve Evenson")
                .hasRow("chad", "Chad Chadson")
                .isExhausted()
        }
    }

    @Test
    fun executeInsertAndTriggerNoTables() = runBlockingTest {
        val statement: SupportSQLiteStatement = real.compileStatement(
            "INSERT INTO "
                + TestDb.TABLE_EMPLOYEE + " (" + TestDb.EmployeeTable.NAME + ", " + TestDb.EmployeeTable.USERNAME + ") "
                + "VALUES ('Chad Chadson', 'chad')"
        )
        db.createQuery(TestDb.TABLE_EMPLOYEE, TestDb.SELECT_EMPLOYEES).test {
            awaitItem().runQuery()
                .hasRow("alice", "Alice Allison")
                .hasRow("bob", "Bob Bobberson")
                .hasRow("eve", "Eve Evenson")
                .isExhausted()
            db.executeInsert(emptySet(), statement)
        }
    }

    @Test
    fun executeInsertThrowsAndDoesNotTrigger() = runBlockingTest {
        val statement: SupportSQLiteStatement = real.compileStatement(
            "INSERT INTO "
                + TestDb.TABLE_EMPLOYEE + " (" + TestDb.EmployeeTable.NAME + ", " + TestDb.EmployeeTable.USERNAME + ") "
                + "VALUES ('Alice Allison', 'alice')"
        )
        db.createQuery(TestDb.TABLE_EMPLOYEE, TestDb.SELECT_EMPLOYEES)
            .drop(1) // Skip initial
            .test {
                try {
                    db.executeInsert(TestDb.TABLE_EMPLOYEE, statement)
                    Assert.fail()
                } catch (ignored: SQLException) {
                }
            }
    }

    @Test
    fun executeInsertWithArgsAndTrigger() = runBlockingTest {
        val statement: SupportSQLiteStatement = real.compileStatement(
            "INSERT INTO "
                + TestDb.TABLE_EMPLOYEE + " (" + TestDb.EmployeeTable.NAME + ", " + TestDb.EmployeeTable.USERNAME + ") VALUES (?, ?)"
        )
        statement.bindString(1, "Chad Chadson")
        statement.bindString(2, "chad")
        db.createQuery(TestDb.TABLE_EMPLOYEE, TestDb.SELECT_EMPLOYEES).test {
            awaitItem().runQuery()
                .hasRow("alice", "Alice Allison")
                .hasRow("bob", "Bob Bobberson")
                .hasRow("eve", "Eve Evenson")
                .isExhausted()
            db.executeInsert(TestDb.TABLE_EMPLOYEE, statement)
            awaitItem().runQuery()
                .hasRow("alice", "Alice Allison")
                .hasRow("bob", "Bob Bobberson")
                .hasRow("eve", "Eve Evenson")
                .hasRow("chad", "Chad Chadson")
                .isExhausted()
        }
    }

    @Test
    fun executeInsertWithArgsThrowsAndDoesNotTrigger() = runBlockingTest {
        val statement: SupportSQLiteStatement = real.compileStatement(
            "INSERT INTO "
                + TestDb.TABLE_EMPLOYEE + " (" + TestDb.EmployeeTable.NAME + ", " + TestDb.EmployeeTable.USERNAME + ") VALUES (?, ?)"
        )
        statement.bindString(1, "Alice Aliison")
        statement.bindString(2, "alice")
        db.createQuery(TestDb.TABLE_EMPLOYEE, TestDb.SELECT_EMPLOYEES)
            .drop(1) // Skip initial
            .test {

                try {
                    db.executeInsert(TestDb.TABLE_EMPLOYEE, statement)
                    Assert.fail()
                } catch (ignored: SQLException) {
                }
            }
    }

    @TargetApi(Build.VERSION_CODES.HONEYCOMB)
    @SdkSuppress(minSdkVersion = Build.VERSION_CODES.HONEYCOMB)
    @Test
    fun executeUpdateDeleteAndTrigger() = runBlockingTest {
        val statement: SupportSQLiteStatement = real.compileStatement(
            "UPDATE " + TestDb.TABLE_EMPLOYEE + " SET " + TestDb.EmployeeTable.NAME + " = 'Zach'"
        )
        db.createQuery(TestDb.TABLE_EMPLOYEE, TestDb.SELECT_EMPLOYEES).test {
            awaitItem().runQuery()
                .hasRow("alice", "Alice Allison")
                .hasRow("bob", "Bob Bobberson")
                .hasRow("eve", "Eve Evenson")
                .isExhausted()
            db.executeUpdateDelete(TestDb.TABLE_EMPLOYEE, statement)
            awaitItem().runQuery()
                .hasRow("alice", "Zach")
                .hasRow("bob", "Zach")
                .hasRow("eve", "Zach")
                .isExhausted()
        }
    }

    @TargetApi(Build.VERSION_CODES.HONEYCOMB)
    @SdkSuppress(minSdkVersion = Build.VERSION_CODES.HONEYCOMB)
    @Test
    fun executeUpdateDeleteAndDontTrigger() = runBlockingTest {
        val statement: SupportSQLiteStatement = real.compileStatement(
            ""
                + "UPDATE " + TestDb.TABLE_EMPLOYEE
                + " SET " + TestDb.EmployeeTable.NAME + " = 'Zach'"
                + " WHERE " + TestDb.EmployeeTable.NAME + " = 'Rob'"
        )
        db.createQuery(TestDb.TABLE_EMPLOYEE, TestDb.SELECT_EMPLOYEES).test {
            awaitItem().runQuery()
                .hasRow("alice", "Alice Allison")
                .hasRow("bob", "Bob Bobberson")
                .hasRow("eve", "Eve Evenson")
                .isExhausted()
            db.executeUpdateDelete(TestDb.TABLE_EMPLOYEE, statement)
        }
    }

    @TargetApi(Build.VERSION_CODES.HONEYCOMB)
    @SdkSuppress(minSdkVersion = Build.VERSION_CODES.HONEYCOMB)
    @Test
    fun executeUpdateDeleteAndTriggerWithMultipleTables() = runBlockingTest {
        val statement: SupportSQLiteStatement = real.compileStatement(
            "UPDATE " + TestDb.TABLE_EMPLOYEE + " SET " + TestDb.EmployeeTable.NAME + " = 'Zach'"
        )
        val managerObserver = db.createQuery(TestDb.TABLE_MANAGER, TestDb.SELECT_MANAGER_LIST)
        managerObserver.test {
            awaitItem().runQuery()
                .hasRow("Eve Evenson", "Alice Allison")
                .isExhausted()
        }
        val employeeFlow = db.createQuery(TestDb.TABLE_EMPLOYEE, TestDb.SELECT_EMPLOYEES)
        employeeFlow.test {
            awaitItem().runQuery()
                .hasRow("alice", "Alice Allison")
                .hasRow("bob", "Bob Bobberson")
                .hasRow("eve", "Eve Evenson")
                .isExhausted()
        }
        val employeeAndManagerTables = Collections.unmodifiableSet(HashSet(TestDb.BOTH_TABLES))
        db.executeUpdateDelete(employeeAndManagerTables, statement)
        employeeFlow.test {
            awaitItem().runQuery()
                .hasRow("alice", "Zach")
                .hasRow("bob", "Zach")
                .hasRow("eve", "Zach")
                .isExhausted()
        }
        managerObserver.test {
            awaitItem().runQuery()
                .hasRow("Zach", "Zach")
                .isExhausted()
        }
    }

    @TargetApi(Build.VERSION_CODES.HONEYCOMB)
    @SdkSuppress(minSdkVersion = Build.VERSION_CODES.HONEYCOMB)
    @Test
    fun executeUpdateDeleteAndTriggerWithNoTables() = runBlockingTest {
        val statement: SupportSQLiteStatement = real.compileStatement(
            "UPDATE " + TestDb.TABLE_EMPLOYEE + " SET " + TestDb.EmployeeTable.NAME + " = 'Zach'"
        )
        db.createQuery(TestDb.TABLE_EMPLOYEE, TestDb.SELECT_EMPLOYEES).test {
            awaitItem().runQuery()
                .hasRow("alice", "Alice Allison")
                .hasRow("bob", "Bob Bobberson")
                .hasRow("eve", "Eve Evenson")
                .isExhausted()
            db.executeUpdateDelete(emptySet(), statement)
        }
    }

    @TargetApi(Build.VERSION_CODES.HONEYCOMB)
    @SdkSuppress(minSdkVersion = Build.VERSION_CODES.HONEYCOMB)
    @Test
    fun executeUpdateDeleteThrowsAndDoesNotTrigger() = runBlockingTest {
        val statement: SupportSQLiteStatement = real.compileStatement(
            "UPDATE " + TestDb.TABLE_EMPLOYEE + " SET " + TestDb.EmployeeTable.USERNAME + " = 'alice'"
        )
        db.createQuery(TestDb.TABLE_EMPLOYEE, TestDb.SELECT_EMPLOYEES)
            .drop(1) // Skip initial
            .test {
                try {
                    db.executeUpdateDelete(TestDb.TABLE_EMPLOYEE, statement)
                    Assert.fail()
                } catch (ignored: SQLException) {
                }
            }
    }

    @TargetApi(Build.VERSION_CODES.HONEYCOMB)
    @SdkSuppress(minSdkVersion = Build.VERSION_CODES.HONEYCOMB)
    @Test
    fun executeUpdateDeleteWithArgsAndTrigger() = runBlockingTest {
        val statement: SupportSQLiteStatement = real.compileStatement(
            "UPDATE " + TestDb.TABLE_EMPLOYEE + " SET " + TestDb.EmployeeTable.NAME + " = ?"
        )
        statement.bindString(1, "Zach")
        db.createQuery(TestDb.TABLE_EMPLOYEE, TestDb.SELECT_EMPLOYEES).test {
            awaitItem().runQuery()
                .hasRow("alice", "Alice Allison")
                .hasRow("bob", "Bob Bobberson")
                .hasRow("eve", "Eve Evenson")
                .isExhausted()
            db.executeUpdateDelete(TestDb.TABLE_EMPLOYEE, statement)
            awaitItem().runQuery()
                .hasRow("alice", "Zach")
                .hasRow("bob", "Zach")
                .hasRow("eve", "Zach")
                .isExhausted()
        }
    }

    @TargetApi(Build.VERSION_CODES.HONEYCOMB)
    @SdkSuppress(minSdkVersion = Build.VERSION_CODES.HONEYCOMB)
    @Test
    fun executeUpdateDeleteWithArgsThrowsAndDoesNotTrigger() = runBlockingTest {
        val statement: SupportSQLiteStatement = real.compileStatement(
            "UPDATE " + TestDb.TABLE_EMPLOYEE + " SET " + TestDb.EmployeeTable.USERNAME + " = ?"
        )
        statement.bindString(1, "alice")
        db.createQuery(TestDb.TABLE_EMPLOYEE, TestDb.SELECT_EMPLOYEES)
            .drop(1) // Skip initial
            .test {
                try {
                    db.executeUpdateDelete(TestDb.TABLE_EMPLOYEE, statement)
                    Assert.fail()
                } catch (ignored: SQLException) {
                }
            }
    }

    @Test
    fun transactionOnlyNotifiesOnce() = runBlockingTest {
        db.createQuery(TestDb.TABLE_EMPLOYEE, TestDb.SELECT_EMPLOYEES).test {
            awaitItem().runQuery()
                .hasRow("alice", "Alice Allison")
                .hasRow("bob", "Bob Bobberson")
                .hasRow("eve", "Eve Evenson")
                .isExhausted()
            val transaction = db.newTransaction()
            try {
                db.insert(TestDb.TABLE_EMPLOYEE, SQLiteDatabase.CONFLICT_NONE, TestDb.employee("john", "John Johnson"))
                db.insert(TestDb.TABLE_EMPLOYEE, SQLiteDatabase.CONFLICT_NONE, TestDb.employee("nick", "Nick Nickers"))
                transaction.markSuccessful()
            } finally {
                transaction.end()
            }
            awaitItem().runQuery()
                .hasRow("alice", "Alice Allison")
                .hasRow("bob", "Bob Bobberson")
                .hasRow("eve", "Eve Evenson")
                .hasRow("john", "John Johnson")
                .hasRow("nick", "Nick Nickers")
                .isExhausted()
        }
    }

    @Test
    fun transactionOnlyNotifiesOnceWithCoroutines() = runBlocking {
        db.createQuery(TestDb.TABLE_EMPLOYEE, TestDb.SELECT_EMPLOYEES).test {
            awaitItem().runQuery()
                .hasRow("alice", "Alice Allison")
                .hasRow("bob", "Bob Bobberson")
                .hasRow("eve", "Eve Evenson")
                .isExhausted()
            db.withTransaction {
                db.insert(TestDb.TABLE_EMPLOYEE, SQLiteDatabase.CONFLICT_NONE, TestDb.employee("john", "John Johnson"))
                db.insert(TestDb.TABLE_EMPLOYEE, SQLiteDatabase.CONFLICT_NONE, TestDb.employee("nick", "Nick Nickers"))
            }
            awaitItem().runQuery()
                .hasRow("alice", "Alice Allison")
                .hasRow("bob", "Bob Bobberson")
                .hasRow("eve", "Eve Evenson")
                .hasRow("john", "John Johnson")
                .hasRow("nick", "Nick Nickers")
                .isExhausted()
        }
    }

    @Test
    fun transactionCreatedFromTransactionNotificationWorks() = runBlockingTest {
        // Tests the case where a transaction is created in the subscriber to a query which gets
        // notified as the result of another transaction being committed. With improper ordering, this
        // can result in creating a new transaction before the old is committed on the underlying DB.
        db.createQuery(TestDb.TABLE_EMPLOYEE, TestDb.SELECT_EMPLOYEES).test {
            db.newTransaction().end()
            val transaction = db.newTransaction()
            try {
                db.insert(TestDb.TABLE_EMPLOYEE, SQLiteDatabase.CONFLICT_NONE, TestDb.employee("john", "John Johnson"))
                transaction.markSuccessful()
            } finally {
                transaction.end()
            }
            awaitItem()
            awaitItem()
        }
    }

    @Test
    @Throws(IOException::class)
    fun transactionIsCloseable() = runBlockingTest {
        db.createQuery(TestDb.TABLE_EMPLOYEE, TestDb.SELECT_EMPLOYEES).test {
            awaitItem().runQuery()
                .hasRow("alice", "Alice Allison")
                .hasRow("bob", "Bob Bobberson")
                .hasRow("eve", "Eve Evenson")
                .isExhausted()
            val transaction = db.newTransaction()
            val closeableTransaction: Closeable = transaction // Verify type is implemented.
            closeableTransaction.use {
                db.insert(TestDb.TABLE_EMPLOYEE, SQLiteDatabase.CONFLICT_NONE, TestDb.employee("john", "John Johnson"))
                db.insert(TestDb.TABLE_EMPLOYEE, SQLiteDatabase.CONFLICT_NONE, TestDb.employee("nick", "Nick Nickers"))
                transaction.markSuccessful()
            }
            awaitItem().runQuery()
                .hasRow("alice", "Alice Allison")
                .hasRow("bob", "Bob Bobberson")
                .hasRow("eve", "Eve Evenson")
                .hasRow("john", "John Johnson")
                .hasRow("nick", "Nick Nickers")
                .isExhausted()
        }
    }

    @Test
    fun transactionDoesNotThrow() = runBlockingTest {
        db.createQuery(TestDb.TABLE_EMPLOYEE, TestDb.SELECT_EMPLOYEES).test {
            awaitItem().runQuery()
                .hasRow("alice", "Alice Allison")
                .hasRow("bob", "Bob Bobberson")
                .hasRow("eve", "Eve Evenson")
                .isExhausted()
            val transaction = db.newTransaction()
            try {
                db.insert(TestDb.TABLE_EMPLOYEE, SQLiteDatabase.CONFLICT_NONE, TestDb.employee("john", "John Johnson"))
                db.insert(TestDb.TABLE_EMPLOYEE, SQLiteDatabase.CONFLICT_NONE, TestDb.employee("nick", "Nick Nickers"))
                transaction.markSuccessful()
            } finally {
                transaction.close() // Transactions should not throw on close().
            }
            awaitItem().runQuery()
                .hasRow("alice", "Alice Allison")
                .hasRow("bob", "Bob Bobberson")
                .hasRow("eve", "Eve Evenson")
                .hasRow("john", "John Johnson")
                .hasRow("nick", "Nick Nickers")
                .isExhausted()
        }
    }

    @Test
    fun coroutineTransactionDoesNotThrow() = runBlocking {
        db.createQuery(TestDb.TABLE_EMPLOYEE, TestDb.SELECT_EMPLOYEES).test {
            awaitItem().runQuery()
                .hasRow("alice", "Alice Allison")
                .hasRow("bob", "Bob Bobberson")
                .hasRow("eve", "Eve Evenson")
                .isExhausted()
            db.withTransaction {
                db.insert(TestDb.TABLE_EMPLOYEE, SQLiteDatabase.CONFLICT_NONE, TestDb.employee("john", "John Johnson"))
                db.insert(TestDb.TABLE_EMPLOYEE, SQLiteDatabase.CONFLICT_NONE, TestDb.employee("nick", "Nick Nickers"))
            }
            awaitItem().runQuery()
                .hasRow("alice", "Alice Allison")
                .hasRow("bob", "Bob Bobberson")
                .hasRow("eve", "Eve Evenson")
                .hasRow("john", "John Johnson")
                .hasRow("nick", "Nick Nickers")
                .isExhausted()
        }
    }

    @Test
    fun queryCreatedDuringTransactionThrows() {
        db.newTransaction()
        try {
            db.createQuery(TestDb.TABLE_EMPLOYEE, TestDb.SELECT_EMPLOYEES)
            Assert.fail()
        } catch (e: IllegalStateException) {
            Truth.assertThat(e.message).startsWith("Cannot create observable query in transaction.")
        }
    }

    @Test
    fun queryCreatedDuringWithTransactionThrows() = runBlocking {
        db.withTransaction {
            try {
                db.createQuery(TestDb.TABLE_EMPLOYEE, TestDb.SELECT_EMPLOYEES)
                Assert.fail()
            } catch (e: IllegalStateException) {
                Truth.assertThat(e.message).startsWith("Cannot create observable query in transaction.")
            }
        }
    }

    @Test
    fun querySubscribedToDuringTransactionThrows() = runBlockingTest {
        val query = db.createQuery(TestDb.TABLE_EMPLOYEE, TestDb.SELECT_EMPLOYEES)
        db.newTransaction()
        query.test {
            awaitItem()
            Truth.assertThat(awaitError())
                .hasMessageThat()
                .contains("Cannot subscribe to observable query in a transaction.")
        }
    }

    @Test
    fun querySubscribedToDuringWithTransactionThrows() = runBlocking {
        val query = db.createQuery(TestDb.TABLE_EMPLOYEE, TestDb.SELECT_EMPLOYEES)
        db.withTransaction {
            query.test {
                awaitItem()
                Truth.assertThat(awaitError())
                    .hasMessageThat()
                    .contains("Cannot subscribe to observable query in a transaction.")
            }
        }
    }

    @Test
    fun callingEndMultipleTimesThrows() = runBlockingTest {
        val transaction = db.newTransaction()
        transaction.end()
        try {
            transaction.end()
            Assert.fail()
        } catch (e: IllegalStateException) {
            Truth.assertThat(e).hasMessageThat().isEqualTo("Not in transaction.")
        }
    }

    @Test
    fun querySubscribedToDuringTransactionOnDifferentThread() {
        val transaction = db.newTransaction()
        val latch = CountDownLatch(1)
        object : Thread() {
            override fun run() = runBlocking {
                db.createQuery(TestDb.TABLE_EMPLOYEE, TestDb.SELECT_EMPLOYEES).test {
                    awaitItem().runQuery()
                        .hasRow("alice", "Alice Allison")
                        .hasRow("bob", "Bob Bobberson")
                        .hasRow("eve", "Eve Evenson")
                        .isExhausted()
                }
                latch.countDown()
            }
        }.start()
        sleep(500) // Wait for the thread to block on initial query.
        transaction.end() // Allow other queries to continue.
        latch.await(500, TimeUnit.MILLISECONDS) // Wait for thread to observe initial query.
    }

    @Test
    fun queryCreatedBeforeTransactionButSubscribedAfter() = runBlockingTest {
        val query = db.createQuery(TestDb.TABLE_EMPLOYEE, TestDb.SELECT_EMPLOYEES)
        val transaction = db.newTransaction()
        try {
            db.insert(TestDb.TABLE_EMPLOYEE, SQLiteDatabase.CONFLICT_NONE, TestDb.employee("john", "John Johnson"))
            db.insert(TestDb.TABLE_EMPLOYEE, SQLiteDatabase.CONFLICT_NONE, TestDb.employee("nick", "Nick Nickers"))
            transaction.markSuccessful()
        } finally {
            transaction.end()
        }
        query.test {
            awaitItem().runQuery()
                .hasRow("alice", "Alice Allison")
                .hasRow("bob", "Bob Bobberson")
                .hasRow("eve", "Eve Evenson")
                .hasRow("john", "John Johnson")
                .hasRow("nick", "Nick Nickers")
                .isExhausted()
        }
    }

    @Test
    fun queryCreatedBeforeWithTransactionButSubscribedAfter() = runBlocking {
        val query = db.createQuery(TestDb.TABLE_EMPLOYEE, TestDb.SELECT_EMPLOYEES)
        db.withTransaction {
            db.insert(TestDb.TABLE_EMPLOYEE, SQLiteDatabase.CONFLICT_NONE, TestDb.employee("john", "John Johnson"))
            db.insert(TestDb.TABLE_EMPLOYEE, SQLiteDatabase.CONFLICT_NONE, TestDb.employee("nick", "Nick Nickers"))
        }
        query.test {
            awaitItem().runQuery()
                .hasRow("alice", "Alice Allison")
                .hasRow("bob", "Bob Bobberson")
                .hasRow("eve", "Eve Evenson")
                .hasRow("john", "John Johnson")
                .hasRow("nick", "Nick Nickers")
                .isExhausted()
        }
    }

    @Test
    fun synchronousQueryDuringTransaction() = runBlockingTest {
        val transaction = db.newTransaction()
        try {
            transaction.markSuccessful()
            db.query(TestDb.SELECT_EMPLOYEES)
                .hasRow("alice", "Alice Allison")
                .hasRow("bob", "Bob Bobberson")
                .hasRow("eve", "Eve Evenson")
                .isExhausted()
        } finally {
            transaction.end()
        }
    }

    @Test
    fun synchronousQueryDuringTransactionSeesChanges() = runBlockingTest {
        val transaction = db.newTransaction()
        try {
            db.query(TestDb.SELECT_EMPLOYEES)
                .hasRow("alice", "Alice Allison")
                .hasRow("bob", "Bob Bobberson")
                .hasRow("eve", "Eve Evenson")
                .isExhausted()
            db.insert(TestDb.TABLE_EMPLOYEE, SQLiteDatabase.CONFLICT_NONE, TestDb.employee("john", "John Johnson"))
            db.insert(TestDb.TABLE_EMPLOYEE, SQLiteDatabase.CONFLICT_NONE, TestDb.employee("nick", "Nick Nickers"))
            db.query(TestDb.SELECT_EMPLOYEES)
                .hasRow("alice", "Alice Allison")
                .hasRow("bob", "Bob Bobberson")
                .hasRow("eve", "Eve Evenson")
                .hasRow("john", "John Johnson")
                .hasRow("nick", "Nick Nickers")
                .isExhausted()
            transaction.markSuccessful()
        } finally {
            transaction.end()
        }
    }

    @Test
    fun synchronousQueryWithSupportSQLiteQueryDuringTransaction() = runBlockingTest {
        val transaction = db.newTransaction()
        try {
            transaction.markSuccessful()
            db.query(SimpleSQLiteQuery(TestDb.SELECT_EMPLOYEES))
                .hasRow("alice", "Alice Allison")
                .hasRow("bob", "Bob Bobberson")
                .hasRow("eve", "Eve Evenson")
                .isExhausted()
        } finally {
            transaction.end()
        }
    }

    @Test
    fun synchronousQueryWithSupportSQLiteQueryDuringTransactionSeesChanges() = runBlockingTest {
        val transaction = db.newTransaction()
        try {
            db.query(SimpleSQLiteQuery(TestDb.SELECT_EMPLOYEES))
                .hasRow("alice", "Alice Allison")
                .hasRow("bob", "Bob Bobberson")
                .hasRow("eve", "Eve Evenson")
                .isExhausted()
            db.insert(TestDb.TABLE_EMPLOYEE, SQLiteDatabase.CONFLICT_NONE, TestDb.employee("john", "John Johnson"))
            db.insert(TestDb.TABLE_EMPLOYEE, SQLiteDatabase.CONFLICT_NONE, TestDb.employee("nick", "Nick Nickers"))
            db.query(SimpleSQLiteQuery(TestDb.SELECT_EMPLOYEES))
                .hasRow("alice", "Alice Allison")
                .hasRow("bob", "Bob Bobberson")
                .hasRow("eve", "Eve Evenson")
                .hasRow("john", "John Johnson")
                .hasRow("nick", "Nick Nickers")
                .isExhausted()
            transaction.markSuccessful()
        } finally {
            transaction.end()
        }
    }

    @Test
    fun nestedTransactionsOnlyNotifyOnce() = runBlockingTest {
        db.createQuery(TestDb.TABLE_EMPLOYEE, TestDb.SELECT_EMPLOYEES).test {
            awaitItem().runQuery()
                .hasRow("alice", "Alice Allison")
                .hasRow("bob", "Bob Bobberson")
                .hasRow("eve", "Eve Evenson")
                .isExhausted()
            val transactionOuter = db.newTransaction()
            try {
                db.insert(TestDb.TABLE_EMPLOYEE, SQLiteDatabase.CONFLICT_NONE, TestDb.employee("john", "John Johnson"))
                val transactionInner = db.newTransaction()
                try {
                    db.insert(TestDb.TABLE_EMPLOYEE, SQLiteDatabase.CONFLICT_NONE, TestDb.employee("nick", "Nick Nickers"))
                    transactionInner.markSuccessful()
                } finally {
                    transactionInner.end()
                }
                transactionOuter.markSuccessful()
            } finally {
                transactionOuter.end()
            }
            awaitItem().runQuery()
                .hasRow("alice", "Alice Allison")
                .hasRow("bob", "Bob Bobberson")
                .hasRow("eve", "Eve Evenson")
                .hasRow("john", "John Johnson")
                .hasRow("nick", "Nick Nickers")
                .isExhausted()
        }
    }

    @Test
    fun nestedTransactionsOnMultipleTables() = runBlockingTest {
        db.createQuery(TestDb.BOTH_TABLES, TestDb.SELECT_MANAGER_LIST).test {
            awaitItem().runQuery()
                .hasRow("Eve Evenson", "Alice Allison")
                .isExhausted()
            val transactionOuter = db.newTransaction()
            try {
                var transactionInner = db.newTransaction()
                try {
                    db.insert(TestDb.TABLE_EMPLOYEE, SQLiteDatabase.CONFLICT_NONE, TestDb.employee("john", "John Johnson"))
                    transactionInner.markSuccessful()
                } finally {
                    transactionInner.end()
                }
                transactionInner = db.newTransaction()
                try {
                    db.insert(TestDb.TABLE_MANAGER, SQLiteDatabase.CONFLICT_NONE, TestDb.manager(testDb.aliceId, testDb.bobId))
                    transactionInner.markSuccessful()
                } finally {
                    transactionInner.end()
                }
                transactionOuter.markSuccessful()
            } finally {
                transactionOuter.end()
            }
            awaitItem().runQuery()
                .hasRow("Eve Evenson", "Alice Allison")
                .hasRow("Alice Allison", "Bob Bobberson")
                .isExhausted()
        }
    }

    @Test
    fun emptyTransactionDoesNotNotify() = runBlockingTest {
        db.createQuery(TestDb.TABLE_EMPLOYEE, TestDb.SELECT_EMPLOYEES).test {
            awaitItem().runQuery()
                .hasRow("alice", "Alice Allison")
                .hasRow("bob", "Bob Bobberson")
                .hasRow("eve", "Eve Evenson")
                .isExhausted()
            val transaction = db.newTransaction()
            try {
                transaction.markSuccessful()
            } finally {
                transaction.end()
            }
        }
    }

    @Test
    fun transactionRollbackDoesNotNotify() = runBlockingTest {
        db.createQuery(TestDb.TABLE_EMPLOYEE, TestDb.SELECT_EMPLOYEES).test {
            awaitItem().runQuery()
                .hasRow("alice", "Alice Allison")
                .hasRow("bob", "Bob Bobberson")
                .hasRow("eve", "Eve Evenson")
                .isExhausted()
            val transaction = db.newTransaction()
            try {
                db.insert(TestDb.TABLE_EMPLOYEE, SQLiteDatabase.CONFLICT_NONE, TestDb.employee("john", "John Johnson"))
                db.insert(TestDb.TABLE_EMPLOYEE, SQLiteDatabase.CONFLICT_NONE, TestDb.employee("nick", "Nick Nickers"))
                // No call to set successful.
            } finally {
                transaction.end()
            }
        }
    }

    //    @TargetApi(Build.VERSION_CODES.HONEYCOMB)
    //    @SdkSuppress(minSdkVersion = Build.VERSION_CODES.HONEYCOMB)
    //    @Test
    //    @Throws(InterruptedException::class)
    //    fun nonExclusiveTransactionWorks() = runBlockingTest {
    //        val transactionStarted = CountDownLatch(1)
    //        val transactionProceed = CountDownLatch(1)
    //        val transactionCompleted = CountDownLatch(1)
    //        object : Thread() {
    //            override fun run() = runBlockingTest {
    //                val transaction = db.newNonExclusiveTransaction()
    //                transactionStarted.countDown()
    //                try {
    //                    db.insert(TestDb.TABLE_EMPLOYEE, SQLiteDatabase.CONFLICT_NONE, TestDb.employee("hans", "Hans Hanson"))
    //                    transactionProceed.await(10, TimeUnit.SECONDS)
    //                } catch (e: InterruptedException) {
    //                    throw RuntimeException("Exception in transaction thread", e)
    //                }
    //                transaction.markSuccessful()
    //                transaction.close()
    //                transactionCompleted.countDown()
    //            }
    //        }.start()
    //        Truth.assertThat(transactionStarted.await(10, TimeUnit.SECONDS)).isTrue()
    //
    //        //Simple query
    //        val employees: TestDb.Employee = db.createQuery(TestDb.TABLE_EMPLOYEE, TestDb.SELECT_EMPLOYEES + " LIMIT 1")
    //            .mapToOne(TestDb.Employee.MAPPER)
    //            .first()
    //        Truth.assertThat(employees).isEqualTo(TestDb.Employee("alice", "Alice Allison"))
    //        transactionProceed.countDown()
    //        Truth.assertThat(transactionCompleted.await(10, TimeUnit.SECONDS)).isTrue()
    //    }

    @Test
    fun badQueryThrows() {
        try {
            db.query("SELECT * FROM missing")
            Assert.fail()
        } catch (e: SQLiteException) {
            Truth.assertThat(e.message).contains("no such table: missing")
        }
    }

    @Test
    fun badInsertThrows() {
        try {
            db.insert("missing", SQLiteDatabase.CONFLICT_NONE, TestDb.employee("john", "John Johnson"))
            Assert.fail()
        } catch (e: SQLiteException) {
            Truth.assertThat(e.message).contains("no such table: missing")
        }
    }

    @Test
    fun badUpdateThrows() {
        try {
            db.update("missing", SQLiteDatabase.CONFLICT_NONE, TestDb.employee("john", "John Johnson"), "1")
            Assert.fail()
        } catch (e: SQLiteException) {
            Truth.assertThat(e.message).contains("no such table: missing")
        }
    }

    @Test
    fun badDeleteThrows() {
        try {
            db.delete("missing", "1")
            Assert.fail()
        } catch (e: SQLiteException) {
            Truth.assertThat(e.message).contains("no such table: missing")
        }
    }
}