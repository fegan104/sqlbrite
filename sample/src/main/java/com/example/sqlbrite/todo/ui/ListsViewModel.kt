package com.example.sqlbrite.todo.ui

import androidx.lifecycle.ViewModel
import com.squareup.sqlbrite3.BriteDatabase
import com.squareup.sqlbrite3.mapToList
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

@HiltViewModel
class ListsViewModel @Inject constructor(
    private val db: BriteDatabase
): ViewModel() {

    fun observeListItems(): Flow<List<ListsItem>> {
        return db.createQuery(ListsItem.TABLES, ListsItem.QUERY)
            .mapToList(ListsItem.MAPPER)
    }
}