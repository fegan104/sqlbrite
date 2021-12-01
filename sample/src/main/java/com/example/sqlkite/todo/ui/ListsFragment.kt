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
import android.view.MenuItem
import android.view.MenuItem.SHOW_AS_ACTION_IF_ROOM
import android.view.MenuItem.SHOW_AS_ACTION_WITH_TEXT
import android.view.View
import android.view.ViewGroup
import android.widget.ListView
import androidx.core.view.MenuItemCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.sqlkite.todo.R
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

@AndroidEntryPoint
class ListsFragment : Fragment() {

    private val viewModel by viewModels<ListsViewModel>()
    private lateinit var listView: ListView
    private lateinit var emptyView: View
    private lateinit var listener: Listener
    private lateinit var adapter: ListsAdapter

    override fun onAttach(context: Context) {
        check(context is Listener) { "Activity must implement fragment Listener." }
        super.onAttach(context)
        setHasOptionsMenu(true)
        listener = context
        adapter = ListsAdapter(context)
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        super.onCreateOptionsMenu(menu, inflater)
        val item: MenuItem = menu.add(R.string.new_list)
            .setOnMenuItemClickListener {
                listener.onNewListClicked()
                true
            }
        MenuItemCompat.setShowAsAction(item, SHOW_AS_ACTION_IF_ROOM or SHOW_AS_ACTION_WITH_TEXT)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
       val root = inflater.inflate(R.layout.lists, container, false)
        listView = root.findViewById(android.R.id.list)
        listView.setOnItemClickListener { parent, view, position, id ->
            listClicked(id)
        }
        emptyView = root.findViewById(android.R.id.empty)
        return root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        activity?.title = "To-Do"
        listView.emptyView = emptyView
        listView.adapter = adapter
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.observeListItems().collect { title ->
                    adapter.accept(title)
                }
            }
        }

    }

    private fun listClicked(listId: Long) {
        listener.onListClicked(listId)
    }

    internal interface Listener {

        fun onListClicked(id: Long)
        fun onNewListClicked()
    }

    companion object {

        @JvmStatic
        fun newInstance(): Fragment {
            return ListsFragment()
        }
    }
}