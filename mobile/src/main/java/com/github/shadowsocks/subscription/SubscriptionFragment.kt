/*******************************************************************************
 *                                                                             *
 *  Copyright (C) 2017 by Max Lv <max.c.lv@gmail.com>                          *
 *  Copyright (C) 2017 by Mygod Studio <contact-shadowsocks-android@mygod.be>  *
 *                                                                             *
 *  This program is free software: you can redistribute it and/or modify       *
 *  it under the terms of the GNU General Public License as published by       *
 *  the Free Software Foundation, either version 3 of the License, or          *
 *  (at your option) any later version.                                        *
 *                                                                             *
 *  This program is distributed in the hope that it will be useful,            *
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of             *
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the              *
 *  GNU General Public License for more details.                               *
 *                                                                             *
 *  You should have received a copy of the GNU General Public License          *
 *  along with this program. If not, see <http://www.gnu.org/licenses/>.       *
 *                                                                             *
 *******************************************************************************/

package com.github.shadowsocks.subscription

import android.annotation.SuppressLint
import android.content.DialogInterface
import android.content.Intent
import android.os.Bundle
import android.os.Parcelable
import android.text.Editable
import android.text.TextWatcher
import android.view.*
import android.widget.AdapterView
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.Toolbar
import androidx.recyclerview.widget.DefaultItemAnimator
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.github.shadowsocks.MainActivity
import com.github.shadowsocks.R
import com.github.shadowsocks.ToolbarFragment
import com.github.shadowsocks.bg.BaseService
import com.github.shadowsocks.database.ProfileManager
import com.github.shadowsocks.plugin.AlertDialogFragment
import com.github.shadowsocks.utils.asIterable
import com.github.shadowsocks.utils.readableMessage
import com.github.shadowsocks.widget.ListHolderListener
import com.github.shadowsocks.widget.MainListListener
import com.github.shadowsocks.widget.UndoSnackbarManager
import com.google.android.material.textfield.TextInputLayout
import kotlinx.android.parcel.Parcelize
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import me.zhanghai.android.fastscroll.FastScrollerBuilder
import me.zhanghai.android.materialprogressbar.MaterialProgressBar
import java.net.HttpURLConnection
import java.net.MalformedURLException
import java.net.URL
import java.util.*

class SubscriptionFragment : ToolbarFragment(), Toolbar.OnMenuItemClickListener {
    companion object {
        private const val REQUEST_CODE_ADD = 1
        private const val REQUEST_CODE_EDIT = 2

        private const val SELECTED_URLS = "com.github.shadowsocks.acl.subscription.SELECTED_URLS"
    }


    @Parcelize
    data class SubItem(val item: String = "") : Parcelable {
        fun toURL() = URL(item)
    }

    @Parcelize
    data class SubEditResult(val edited: SubItem, val replacing: SubItem) : Parcelable

    class SubDialogFragment : AlertDialogFragment<SubItem, SubEditResult>(),
            TextWatcher, AdapterView.OnItemSelectedListener {
        private lateinit var editText: EditText
        private lateinit var inputLayout: TextInputLayout
        private val positive by lazy { (dialog as AlertDialog).getButton(AlertDialog.BUTTON_POSITIVE) }

        override fun AlertDialog.Builder.prepare(listener: DialogInterface.OnClickListener) {
            val activity = requireActivity()
            @SuppressLint("InflateParams")
            val view = activity.layoutInflater.inflate(R.layout.dialog_subscription, null)
            editText = view.findViewById(R.id.content)
            inputLayout = view.findViewById(R.id.content_layout)
            editText.setText(arg.item)
            editText.addTextChangedListener(this@SubDialogFragment)
            setTitle(R.string.add_subscription)
            setPositiveButton(android.R.string.ok, listener)
            setNegativeButton(android.R.string.cancel, null)
            if (arg.item.isNotEmpty()) setNeutralButton(R.string.delete, listener)
            setView(view)
        }

        override fun onStart() {
            super.onStart()
            validate()
        }

        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        override fun afterTextChanged(s: Editable) = validate(value = s)
        override fun onNothingSelected(parent: AdapterView<*>?) = check(false)
        override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) = validate()

        private fun validate(value: Editable = editText.text) {
            var message = ""
            positive.isEnabled = try {
                val url = URL(value.toString())
                if ("http".equals(url.protocol, true)) message = getString(R.string.cleartext_http_warning)
                true
            } catch (e: MalformedURLException) {
                message = e.readableMessage
                false
            }
            inputLayout.error = message
        }

        override fun ret(which: Int) = when (which) {
            DialogInterface.BUTTON_POSITIVE -> {
                SubEditResult(editText.text.toString().let { text -> SubItem(text) }, arg)
            }
            DialogInterface.BUTTON_NEUTRAL -> SubEditResult(arg, arg)
            else -> null
        }

        override fun onClick(dialog: DialogInterface?, which: Int) {
            if (which != DialogInterface.BUTTON_NEGATIVE) super.onClick(dialog, which)
        }
    }

    private inner class SubViewHolder(view: View) : RecyclerView.ViewHolder(view),
            View.OnClickListener, View.OnLongClickListener {
        lateinit var item: URL
        private val text = view.findViewById<TextView>(android.R.id.text1)

        init {
            view.isFocusable = true
            view.setOnClickListener(this)
            view.setOnLongClickListener(this)
            view.setBackgroundResource(R.drawable.background_selectable)
        }

        fun bind(url: URL) {
            item = url
            text.text = url.toString()
            itemView.isSelected = selectedItems.contains(url)
        }

        override fun onClick(v: View?) {
            if (selectedItems.isNotEmpty()) onLongClick(v)
            else SubDialogFragment().withArg(SubItem(item.toString())).show(this@SubscriptionFragment, REQUEST_CODE_EDIT)
        }

        override fun onLongClick(v: View?): Boolean {
            if (!selectedItems.add(item)) selectedItems.remove(item)    // toggle
            itemView.isSelected = !itemView.isSelected
            return true
        }
    }

    private inner class SubscriptionAdapter : RecyclerView.Adapter<SubViewHolder>() {
        private val subscription = Subscription.instance
        private var savePending = false

        override fun onBindViewHolder(holder: SubViewHolder, i: Int) {
            holder.bind(subscription.urls[i])
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = SubViewHolder(LayoutInflater
                .from(parent.context).inflate(android.R.layout.simple_list_item_1, parent, false))

        override fun getItemCount(): Int = subscription.urls.size()

        private fun apply() {
            if (!savePending) {
                savePending = true
                list.post {
                    Subscription.instance = subscription
                    savePending = false
                }
            }
        }

        fun add(url: URL): Int {
            val old = subscription.urls.size()
            val index = subscription.urls.add(url)
            if (old != subscription.urls.size()) {
                notifyItemInserted(index)
                apply()
            }
            return index
        }

        fun remove(i: Int) {
            undoManager.remove(Pair(i, subscription.urls[i]))
            subscription.urls.removeItemAt(i)
            notifyItemRemoved(i)
            apply()
        }

        fun remove(item: Any) {
            when (item) {
                is URL -> {
                    notifyItemRemoved(subscription.urls.indexOf(item))
                    subscription.urls.remove(item)
                    apply()
                }
            }
        }

        fun undo(actions: List<Pair<Int, Any>>) {
            for ((_, item) in actions)
                when (item) {
                    is URL -> {
                        add(item)
                    }
                }
        }
    }

    private val isEnabled get() = (activity as MainActivity).state == BaseService.State.Stopped

    private val selectedItems = HashSet<Any>()
    private val adapter by lazy { SubscriptionAdapter() }
    private lateinit var list: RecyclerView
    private lateinit var progress: MaterialProgressBar
    private var mode: ActionMode? = null
    private lateinit var undoManager: UndoSnackbarManager<Any>
    private var fetchJob: Job? = null

    private fun fetchServerFromSubscriptions() {
        if (fetchJob?.isActive != true) {
            val activity = activity as MainActivity
            progress.visibility = View.VISIBLE

            fetchJob = GlobalScope.launch {
                val subscription = Subscription.instance

                try {
                    for (url in subscription.urls.asIterable()) {
                        val connection = url.openConnection() as HttpURLConnection
                        ProfileManager.createProfilesFromJson(sequenceOf(connection.inputStream),replace = true)
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    activity.snackbar(e.readableMessage).show()
                } finally {
                    progress.post {
                        progress.visibility = View.INVISIBLE
                    }
                }
            }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? =
            inflater.inflate(R.layout.layout_subscriptions, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        view.setOnApplyWindowInsetsListener(ListHolderListener)
        if (savedInstanceState != null) {
            selectedItems.addAll(savedInstanceState.getStringArray(SELECTED_URLS)?.map { URL(it) }
                    ?: listOf())
        }
        toolbar.setTitle(R.string.subscriptions)
        toolbar.inflateMenu(R.menu.subscription_menu)
        toolbar.setOnMenuItemClickListener(this)
        val activity = activity as MainActivity
        list = view.findViewById(R.id.list)
        list.setOnApplyWindowInsetsListener(MainListListener)
        list.layoutManager = LinearLayoutManager(activity, RecyclerView.VERTICAL, false)
        list.itemAnimator = DefaultItemAnimator()
        list.adapter = adapter
        progress = view.findViewById(R.id.indeterminate_horizontal_progress)
        FastScrollerBuilder(list).useMd2Style().build()
        undoManager = UndoSnackbarManager(activity, adapter::undo)
        ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.START or ItemTouchHelper.END) {
            override fun getSwipeDirs(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder): Int =
                    if (isEnabled && selectedItems.isEmpty()) super.getSwipeDirs(recyclerView, viewHolder) else 0

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) =
                    adapter.remove(viewHolder.adapterPosition)

            override fun onMove(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder,
                                target: RecyclerView.ViewHolder): Boolean = false
        }).attachToRecyclerView(list)
    }

    override fun onBackPressed(): Boolean {
        val mode = mode
        return if (mode != null) {
            mode.finish()
            true
        } else super.onBackPressed()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putStringArray(SELECTED_URLS, selectedItems.filterIsInstance<URL>().map(URL::toString).toTypedArray())
    }

    override fun onMenuItemClick(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_manual_settings -> {
            SubDialogFragment().withArg(SubItem()).show(this, REQUEST_CODE_ADD)
            true
        }
        R.id.action_update_subscription -> {
            fetchServerFromSubscriptions()
            true
        }
        else -> false
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        val editing = when (requestCode) {
            REQUEST_CODE_ADD -> false
            REQUEST_CODE_EDIT -> true
            else -> return super.onActivityResult(requestCode, resultCode, data)
        }
        val ret by lazy { AlertDialogFragment.getRet<SubEditResult>(data!!) }
        when (resultCode) {
            DialogInterface.BUTTON_POSITIVE -> {
                if (editing) adapter.remove(ret.replacing.toURL())
                adapter.add(ret.edited.toURL()).also { list.post { list.scrollToPosition(it) } }
            }
            DialogInterface.BUTTON_NEUTRAL -> ret.replacing.toURL().let { item ->
                adapter.remove(item)
                undoManager.remove(Pair(-1, item))
            }
        }
    }

    override fun onDetach() {
        undoManager.flush()
        mode?.finish()
        super.onDetach()
    }
}
