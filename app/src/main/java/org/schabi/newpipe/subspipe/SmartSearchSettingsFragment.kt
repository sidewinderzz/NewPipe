/*
 * SubsPipe: Settings > Smart search.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.subspipe

import android.os.Bundle
import android.text.InputType
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.schedulers.Schedulers
import org.schabi.newpipe.NewPipeDatabase
import org.schabi.newpipe.R
import org.schabi.newpipe.database.subscription.SubscriptionEntity
import org.schabi.newpipe.settings.BasePreferenceFragment

/** AI key, tagging buttons and the channel topics editor. */
class SmartSearchSettingsFragment : BasePreferenceFragment() {
    private val disposables = CompositeDisposable()

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        addPreferencesFromResourceRegistry()

        findPreference<EditTextPreference>(ChannelTagger.AI_KEY_PREF)?.apply {
            setOnBindEditTextListener {
                it.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                it.setSingleLine()
            }
            setOnPreferenceChangeListener { _, _ ->
                view?.post { refresh() }
                true
            }
        }
        findPreference<Preference>("subspipe_tag_now")?.setOnPreferenceClickListener {
            tag(redo = false)
            true
        }
        findPreference<Preference>("subspipe_redo_tags")?.setOnPreferenceClickListener {
            tag(redo = true)
            true
        }
        findPreference<Preference>("subspipe_channel_topics")?.setOnPreferenceClickListener {
            showChannels()
            true
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onDestroy() {
        disposables.clear()
        super.onDestroy()
    }

    private fun refresh() {
        val context = context ?: return
        val key = ChannelTagger.apiKey(context)
        findPreference<EditTextPreference>(ChannelTagger.AI_KEY_PREF)?.summary = if (key == null) {
            getString(R.string.subspipe_ai_key_none)
        } else {
            getString(R.string.subspipe_ai_key_set, ChannelTagger.provider(key).label, key.takeLast(4))
        }
        disposables.add(
            Single.fromCallable { SmartSearch.statusText(context) }
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                    { findPreference<Preference>("subspipe_smart_search_status")?.summary = it },
                    {}
                )
        )
    }

    private fun tag(redo: Boolean) {
        val context = requireContext().applicationContext
        if (ChannelTagger.apiKey(context) == null) {
            Toast.makeText(context, R.string.subspipe_need_key, Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(context, R.string.subspipe_tagging, Toast.LENGTH_SHORT).show()
        SmartSearchIndexer.tagChannelsNow(context, redo) { tagged ->
            val message = if (tagged != null) {
                context.getString(R.string.subspipe_tagging_done, tagged)
            } else {
                context.getString(
                    R.string.subspipe_tagging_failed,
                    SmartSearchIndexer.lastTagError.orEmpty()
                )
            }
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            refresh()
        }
    }

    private fun showChannels() {
        val context = requireContext()
        disposables.add(
            Single.fromCallable {
                NewPipeDatabase.getInstance(context).subscriptionDAO().getAll().blockingFirst()
                    .filter { it.url != null }
                    .sortedBy { it.name.orEmpty().lowercase() }
            }
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe({ showChannelList(it) }, {})
        )
    }

    private fun showChannelList(channels: List<SubscriptionEntity>) {
        val context = context ?: return
        val store = SmartSearchStore.get(context)
        val rows = channels.map { channel ->
            val tags = store.tags(channel.url)?.tags.orEmpty()
            val tagText = tags.joinToString(", ").ifEmpty { getString(R.string.subspipe_no_tags) }
            "${channel.name}\n$tagText"
        }.toTypedArray<CharSequence>()
        AlertDialog.Builder(context)
            .setTitle(R.string.subspipe_channel_topics_title)
            .setItems(rows) { _, which -> editTags(channels[which], channels) }
            .setPositiveButton(R.string.ok, null)
            .show()
    }

    private fun editTags(channel: SubscriptionEntity, channels: List<SubscriptionEntity>) {
        val context = context ?: return
        val url = channel.url ?: return
        val store = SmartSearchStore.get(context)
        val input = EditText(context).apply {
            setText(store.tags(url)?.tags.orEmpty().joinToString(", "))
            hint = getString(R.string.subspipe_edit_tags_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        val padding = (20 * resources.displayMetrics.density).toInt()
        val container = android.widget.FrameLayout(context).apply {
            setPadding(padding, padding / 2, padding, 0)
            addView(input)
        }
        AlertDialog.Builder(context)
            .setTitle(channel.name)
            .setView(container)
            .setPositiveButton(R.string.ok) { _, _ ->
                val tags = SmartSearchStore.splitTags(input.text.toString())
                Single.fromCallable {
                    store.putTags(url, tags, SmartSearchStore.TagSource.USER)
                }
                    .subscribeOn(Schedulers.io())
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe({ showChannelList(channels) }, {})
                    .let { disposables.add(it) }
            }
            .setNegativeButton(R.string.cancel) { _, _ -> showChannelList(channels) }
            .show()
    }
}
