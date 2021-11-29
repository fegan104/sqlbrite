package com.example.sqlbrite.todo.ui

import android.database.sqlite.SQLiteDatabase
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.sqlbrite.todo.db.TodoList
import com.squareup.sqlbrite3.BriteDatabase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class NewListViewModel @Inject constructor(
    private val db: BriteDatabase
): ViewModel() {

    fun saveNewList(name: String) = viewModelScope.launch {
        db.insert(TodoList.TABLE, SQLiteDatabase.CONFLICT_NONE, TodoList.Builder().name(name).build())
    }
}