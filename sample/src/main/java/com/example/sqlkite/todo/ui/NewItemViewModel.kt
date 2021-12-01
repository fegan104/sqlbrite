package com.example.sqlkite.todo.ui

import android.database.sqlite.SQLiteDatabase
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.sqlkite.todo.db.TodoItem
import com.frankegan.sqlkite.KiteDatabase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class NewItemViewModel @Inject constructor(
    private val db: KiteDatabase
): ViewModel() {

    fun saveNewItem(listId: Long, description: String) = viewModelScope.launch {
        db.insert(
            TodoItem.TABLE,
            SQLiteDatabase.CONFLICT_NONE,
            TodoItem.Builder().listId(listId).description(description).build()
        )
    }
}