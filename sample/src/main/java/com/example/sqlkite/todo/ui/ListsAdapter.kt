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
import android.widget.BaseAdapter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView


internal class ListsAdapter(context: Context?) : BaseAdapter() {

    private val inflater: LayoutInflater = LayoutInflater.from(context)
    private var items: List<ListsItem> = emptyList()
    fun accept(items: List<ListsItem>) {
        this.items = items
        notifyDataSetChanged()
    }

    override fun getCount(): Int {
        return items.size
    }

    override fun getItem(position: Int): ListsItem {
        return items[position]
    }

    override fun getItemId(position: Int): Long {
        return getItem(position).id
    }

    override fun hasStableIds(): Boolean {
        return true
    }

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        var convertView = convertView
        if (convertView == null) {
            convertView = inflater.inflate(android.R.layout.simple_list_item_1, parent, false)
        }
        val item = getItem(position)
        (convertView as TextView).text = item.name + " (" + item.itemCount + ")"
        return convertView
    }

}