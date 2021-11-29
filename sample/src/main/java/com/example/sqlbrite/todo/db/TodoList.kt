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
import android.os.Parcelable
import kotlinx.parcelize.Parcelize

// Note: normally I wouldn't prefix table classes but I didn't want 'List' to be overloaded.
@Parcelize
data class TodoList(
    val id: Long,
    val name: String?,
    val archived: Boolean,
) : Parcelable {

    class Builder {

        private val values = ContentValues()
        fun id(id: Long): Builder {
            values.put(ID, id)
            return this
        }

        fun name(name: String?): Builder {
            values.put(NAME, name)
            return this
        }

        fun archived(archived: Boolean): Builder {
            values.put(ARCHIVED, archived)
            return this
        }

        fun build(): ContentValues {
            return values // TODO defensive copy?
        }
    }

    companion object {

        const val TABLE = "todo_list"
        const val ID = "_id"
        const val NAME = "name"
        const val ARCHIVED = "archived"
    }
}