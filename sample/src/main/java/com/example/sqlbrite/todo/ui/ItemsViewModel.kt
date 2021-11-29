package com.example.sqlbrite.todo.ui

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.sqlbrite.todo.db.TodoItem
import com.example.sqlbrite.todo.db.TodoList
import com.squareup.sqlbrite3.BriteDatabase
import com.squareup.sqlbrite3.mapToList
import com.squareup.sqlbrite3.mapToOne
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ItemsViewModel @Inject constructor(
    private val db: BriteDatabase
) : ViewModel() {

    fun updateToDo(table: String, build: ContentValues, todoId: String) = viewModelScope.launch {
        db.update(
            table = table,
            conflictAlgorithm = SQLiteDatabase.CONFLICT_NONE,
            values = build,
            whereClause = "${TodoItem.ID} = ?",
            whereArgs = arrayOf(todoId)
        )
    }

    fun observerTitle(listId: String): Flow<String> {
        return db.createQuery(TodoItem.TABLE, ItemsFragment.COUNT_QUERY, listId)
            .mapToOne { it.getInt(0) }
            .combine(
                db.createQuery(TodoList.TABLE, ItemsFragment.TITLE_QUERY, listId)
                    .mapToOne { it.getString(0) }
            ) { listName, itemCount -> "$listName ($itemCount)" }
    }

    fun observeTodoItems(listId: String): Flow<List<TodoItem>> {
        return db.createQuery(
            TodoItem.TABLE,
            ItemsFragment.LIST_QUERY,
            listId
        ).mapToList(TodoItem.MAPPER)
    }
}