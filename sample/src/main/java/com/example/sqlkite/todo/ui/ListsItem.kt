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
package com.example.sqlkite.todo.ui

import android.database.Cursor
import android.os.Parcelable
import com.example.sqlkite.todo.db.Db
import com.example.sqlkite.todo.db.TodoItem
import com.example.sqlkite.todo.db.TodoList
import kotlinx.parcelize.Parcelize

@Parcelize
data class ListsItem(
    val id: Long,
    val name: String?,
    val itemCount: Int
) : Parcelable {

    companion object {

        private const val ALIAS_LIST = "list"
        private const val ALIAS_ITEM = "item"
        private const val LIST_ID = ALIAS_LIST + "." + TodoList.ID
        private const val LIST_NAME = ALIAS_LIST + "." + TodoList.NAME
        private const val ITEM_COUNT = "item_count"
        private const val ITEM_ID = ALIAS_ITEM + "." + TodoItem.ID
        private const val ITEM_LIST_ID = ALIAS_ITEM + "." + TodoItem.LIST_ID

        var TABLES: Collection<String> = listOf(TodoList.TABLE, TodoItem.TABLE)

        var QUERY = (""
            + "SELECT " + LIST_ID + ", " + LIST_NAME + ", COUNT(" + ITEM_ID + ") as " + ITEM_COUNT
            + " FROM " + TodoList.TABLE + " AS " + ALIAS_LIST
            + " LEFT OUTER JOIN " + TodoItem.TABLE + " AS " + ALIAS_ITEM + " ON " + LIST_ID + " = " + ITEM_LIST_ID
            + " GROUP BY " + LIST_ID)

        var MAPPER: (Cursor?) -> ListsItem = { cursor ->
            val id: Long = Db.getLong(cursor, TodoList.ID)
            val name: String = Db.getString(cursor, TodoList.NAME)
            val itemCount: Int = Db.getInt(cursor, ITEM_COUNT)
            ListsItem(id, name, itemCount)
        }
    }
}