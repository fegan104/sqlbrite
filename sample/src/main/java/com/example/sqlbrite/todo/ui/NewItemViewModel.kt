package com.example.sqlbrite.todo.ui

import android.database.sqlite.SQLiteDatabase
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.sqlbrite.todo.db.TodoItem
import com.squareup.sqlbrite3.BriteDatabase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class NewItemViewModel @Inject constructor(
    private val db: BriteDatabase
): ViewModel() {

    fun saveNewItem(listId: Long, description: String) = viewModelScope.launch {
        db.insert(
            TodoItem.TABLE,
            SQLiteDatabase.CONFLICT_NONE,
            TodoItem.Builder().listId(listId).description(description).build()
        )
    }
}