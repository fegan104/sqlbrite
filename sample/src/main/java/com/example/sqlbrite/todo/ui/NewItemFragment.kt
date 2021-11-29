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
package com.example.sqlbrite.todo.ui

import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.content.DialogInterface
import android.database.sqlite.SQLiteDatabase
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.viewModels
import com.example.sqlbrite.todo.R
import com.example.sqlbrite.todo.db.TodoItem
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.function.BiFunction

@AndroidEntryPoint
class NewItemFragment : DialogFragment() {

    private val viewModel by viewModels<NewItemViewModel>()
    private lateinit var name: EditText

    private val listId: Long
        get() = arguments!!.getLong(KEY_LIST_ID)

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val context: Context? = activity
        val view: View = LayoutInflater.from(context).inflate(R.layout.new_item, null)
        name = view.findViewById(android.R.id.input)
        return AlertDialog.Builder(context)
            .setTitle(R.string.new_item)
            .setView(view)
            .setPositiveButton(R.string.create) { _, _ -> createClicked() }
            .setNegativeButton(R.string.cancel) { _, _ -> }
            .create()
    }

    private fun createClicked() {
        viewModel.saveNewItem(listId, name.text.toString())
    }

    companion object {

        private const val KEY_LIST_ID = "list_id"

        @JvmStatic
        fun newInstance(listId: Long): NewItemFragment {
            val arguments = Bundle()
            arguments.putLong(KEY_LIST_ID, listId)
            val fragment = NewItemFragment()
            fragment.arguments = arguments
            return fragment
        }
    }
}