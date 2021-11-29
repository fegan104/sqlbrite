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
package com.example.sqlbrite.todo.db

import android.content.ContentValues
import android.database.Cursor
import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class TodoItem(
    val id: Long,
    val listId: Long,
    val description: String?,
    val complete: Boolean
) : Parcelable {


    class Builder {

        private val values = ContentValues()
        fun id(id: Long): Builder {
            values.put(ID, id)
            return this
        }

        fun listId(listId: Long): Builder {
            values.put(LIST_ID, listId)
            return this
        }

        fun description(description: String?): Builder {
            values.put(DESCRIPTION, description)
            return this
        }

        fun complete(complete: Boolean): Builder {
            values.put(COMPLETE, if (complete) Db.BOOLEAN_TRUE else Db.BOOLEAN_FALSE)
            return this
        }

        fun build(): ContentValues {
            return values // TODO defensive copy?
        }
    }

    companion object {

        const val TABLE = "todo_item"
        const val ID = "_id"
        const val LIST_ID = "todo_list_id"
        const val DESCRIPTION = "description"
        const val COMPLETE = "complete"

        val MAPPER: (Cursor?) -> TodoItem = { cursor ->
            val id = Db.getLong(cursor, ID)
            val listId = Db.getLong(cursor, LIST_ID)
            val description = Db.getString(cursor, DESCRIPTION)
            val complete = Db.getBoolean(cursor, COMPLETE)
            TodoItem(id, listId, description, complete)
        }
    }
}