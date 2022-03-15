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

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import java.lang.AssertionError
import java.util.Arrays

internal class TestDb : SupportSQLiteOpenHelper.Callback(1) {
    internal interface EmployeeTable {
        companion object {

            const val ID = "_id"
            const val USERNAME = "username"
            const val NAME = "name"
        }
    }

    internal class Employee(val username: String, val name: String) {

        override fun equals(o: Any?): Boolean {
            if (o === this) return true
            if (o !is Employee) return false
            val other = o
            return username == other.username && name == other.name
        }

        override fun hashCode(): Int {
            return username.hashCode() * 17 + name.hashCode()
        }

        override fun toString(): String {
            return "Employee[$username $name]"
        }

        companion object {

            @JvmField
            val MAPPER = { cursor: Cursor ->
                Employee( //
                    cursor.getString(cursor.getColumnIndexOrThrow(EmployeeTable.USERNAME)),
                    cursor.getString(cursor.getColumnIndexOrThrow(EmployeeTable.NAME))
                )
            }
        }
    }

    internal interface ManagerTable {
        companion object {

            const val ID = "_id"
            const val EMPLOYEE_ID = "employee_id"
            const val MANAGER_ID = "manager_id"
        }
    }

    @JvmField
    var aliceId: Long = 0
    @JvmField
    var bobId: Long = 0
    @JvmField
    var eveId: Long = 0
    override fun onCreate(db: SupportSQLiteDatabase) {
        db.execSQL("PRAGMA foreign_keys=ON")
        db.execSQL(CREATE_EMPLOYEE)
        aliceId = db.insert(TABLE_EMPLOYEE, SQLiteDatabase.CONFLICT_FAIL, employee("alice", "Alice Allison"))
        bobId = db.insert(TABLE_EMPLOYEE, SQLiteDatabase.CONFLICT_FAIL, employee("bob", "Bob Bobberson"))
        eveId = db.insert(TABLE_EMPLOYEE, SQLiteDatabase.CONFLICT_FAIL, employee("eve", "Eve Evenson"))
        db.execSQL(CREATE_MANAGER)
        db.insert(TABLE_MANAGER, SQLiteDatabase.CONFLICT_FAIL, manager(eveId, aliceId))
    }

    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
        throw AssertionError()
    }

    companion object {

        const val TABLE_EMPLOYEE = "employee"
        const val TABLE_MANAGER = "manager"
        const val SELECT_EMPLOYEES = "SELECT " + EmployeeTable.USERNAME + ", " + EmployeeTable.NAME + " FROM " + TABLE_EMPLOYEE
        const val SELECT_MANAGER_LIST = (""
            + "SELECT e." + EmployeeTable.NAME + ", m." + EmployeeTable.NAME + " "
            + "FROM " + TABLE_MANAGER + " AS manager "
            + "JOIN " + TABLE_EMPLOYEE + " AS e "
            + "ON manager." + ManagerTable.EMPLOYEE_ID + " = e." + EmployeeTable.ID + " "
            + "JOIN " + TABLE_EMPLOYEE + " as m "
            + "ON manager." + ManagerTable.MANAGER_ID + " = m." + EmployeeTable.ID)
        @JvmField
        val BOTH_TABLES: Collection<String> = Arrays.asList(TABLE_EMPLOYEE, TABLE_MANAGER)
        private const val CREATE_EMPLOYEE = ("CREATE TABLE " + TABLE_EMPLOYEE + " ("
            + EmployeeTable.ID + " INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT, "
            + EmployeeTable.USERNAME + " TEXT NOT NULL UNIQUE, "
            + EmployeeTable.NAME + " TEXT NOT NULL)")
        private const val CREATE_MANAGER = ("CREATE TABLE " + TABLE_MANAGER + " ("
            + ManagerTable.ID + " INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT, "
            + ManagerTable.EMPLOYEE_ID + " INTEGER NOT NULL UNIQUE REFERENCES " + TABLE_EMPLOYEE + "(" + EmployeeTable.ID + "), "
            + ManagerTable.MANAGER_ID + " INTEGER NOT NULL REFERENCES " + TABLE_EMPLOYEE + "(" + EmployeeTable.ID + "))")

        @JvmStatic
        fun employee(username: String?, name: String?): ContentValues {
            val values = ContentValues()
            values.put(EmployeeTable.USERNAME, username)
            values.put(EmployeeTable.NAME, name)
            return values
        }

        @JvmStatic
        fun manager(employeeId: Long, managerId: Long): ContentValues {
            val values = ContentValues()
            values.put(ManagerTable.EMPLOYEE_ID, employeeId)
            values.put(ManagerTable.MANAGER_ID, managerId)
            return values
        }
    }
}