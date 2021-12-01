package com.example.sqlkite.todo.ui

import androidx.lifecycle.ViewModel
import com.frankegan.sqlkite.KiteDatabase
import com.frankegan.sqlkite.mapToList
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

@HiltViewModel
class ListsViewModel @Inject constructor(
    private val db: KiteDatabase
): ViewModel() {

    fun observeListItems(): Flow<List<ListsItem>> {
        return db.createQuery(ListsItem.TABLES, ListsItem.QUERY)
            .mapToList(ListsItem.MAPPER)
    }
}