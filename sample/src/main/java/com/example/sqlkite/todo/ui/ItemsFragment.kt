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
package com.example.sqlkite.todo.ui

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.AdapterView.OnItemClickListener
import android.widget.ListView
import androidx.core.view.MenuItemCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.sqlkite.todo.R
import com.example.sqlkite.todo.db.Db
import com.example.sqlkite.todo.db.TodoItem
import com.example.sqlkite.todo.db.TodoList
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

@AndroidEntryPoint
class ItemsFragment : Fragment() {

    private val viewModel: ItemsViewModel by viewModels()
    private lateinit var listener: Listener
    private lateinit var adapter: ItemsAdapter
    private var listView: ListView? = null
    private var emptyView: View? = null

    private val listId: Long
        get() = requireArguments().getLong(KEY_LIST_ID)

    override fun onAttach(context: Context) {
        check(context is Listener) { "Activity must implement fragment Listener." }
        super.onAttach(context)
        setHasOptionsMenu(true)
        listener = context
        adapter = ItemsAdapter(activity)
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        super.onCreateOptionsMenu(menu, inflater)
        val item = menu.add(R.string.new_item)
            .setOnMenuItemClickListener {
                listener.onNewItemClicked(listId)
                true
            }
        MenuItemCompat.setShowAsAction(item, MenuItemCompat.SHOW_AS_ACTION_IF_ROOM or MenuItemCompat.SHOW_AS_ACTION_WITH_TEXT)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.items, container, false)
        listView = view.findViewById(android.R.id.list)
        emptyView = view.findViewById(android.R.id.empty)
        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        listView!!.emptyView = emptyView
        listView!!.adapter = adapter
        listView!!.onItemClickListener = OnItemClickListener { parent: AdapterView<*>?, view1: View?, position: Int, id: Long ->
            val newValue = !adapter.getItem(position).complete
            viewModel.updateToDo(
                TodoItem.TABLE,
                TodoItem.Builder().complete(newValue).build(),
                id.toString()
            )
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.observerTitle(listId.toString()).collect { title ->
                    activity?.title = title
                }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.observeTodoItems(listId.toString()).collect { items ->
                    adapter.accept(items)
                }
            }
        }
    }

    companion object {

        private const val KEY_LIST_ID = "list_id"
        const val LIST_QUERY = ("SELECT * FROM "
            + TodoItem.TABLE
            + " WHERE "
            + TodoItem.LIST_ID
            + " = ? ORDER BY "
            + TodoItem.COMPLETE
            + " ASC")
        const val COUNT_QUERY = ("SELECT COUNT(*) FROM "
            + TodoItem.TABLE
            + " WHERE "
            + TodoItem.COMPLETE
            + " = "
            + Db.BOOLEAN_FALSE
            + " AND "
            + TodoItem.LIST_ID
            + " = ?")
        const val TITLE_QUERY = "SELECT " + TodoList.NAME + " FROM " + TodoList.TABLE + " WHERE " + TodoList.ID + " = ?"

        @JvmStatic
        fun newInstance(listId: Long): ItemsFragment {
            val arguments = Bundle()
            arguments.putLong(KEY_LIST_ID, listId)
            val fragment = ItemsFragment()
            fragment.arguments = arguments
            return fragment
        }
    }

    interface Listener {

        fun onNewItemClicked(listId: Long)
    }
}