package com.example.sqlkite.todo.ui

import android.database.sqlite.SQLiteDatabase
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.sqlkite.todo.db.TodoList
import com.frankegan.sqlkite.KiteDatabase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class NewListViewModel @Inject constructor(
    private val db: KiteDatabase
): ViewModel() {

    fun saveNewList(name: String) = viewModelScope.launch {
        db.insert(TodoList.TABLE, SQLiteDatabase.CONFLICT_NONE, TodoList.Builder().name(name).build())
    }
}