/*
 * SubsPipe: trims settings that don't apply to a subscriptions-only app.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.subspipe

import android.content.Context
import androidx.preference.PreferenceGroup
import org.schabi.newpipe.R

/**
 * Settings SubsPipe hides. They are only hidden, not removed, so the code that reads
 * them keeps working with their default values.
 */
object SubsPipeSettings {
    private val HIDDEN_KEYS = intArrayOf(
        // Main screen: NewPipe's own updater (SubsPipe updates from GitHub) and debug tools
        R.string.update_pref_screen_key,
        R.string.update_app_key,
        R.string.manual_update_key,
        R.string.debug_pref_screen_key,

        // Content: other services, YouTube-wide search and the editable main tabs
        R.string.main_page_content_key,
        R.string.peertube_instance_setup_key,
        R.string.show_search_suggestions_key,

        // Appearance
        R.string.show_hold_to_append_key,

        // Video and audio
        R.string.show_play_with_kodi_key,
        R.string.prefer_descriptive_audio_key,

        // Downloads: file name character tweaks
        R.string.settings_file_charset_key,
        R.string.settings_file_replacement_character_key,
    )

    @Volatile
    private var hiddenKeys: Set<String>? = null

    private fun hiddenKeys(context: Context): Set<String> =
        hiddenKeys ?: HIDDEN_KEYS.map { context.getString(it) }.toSet().also { hiddenKeys = it }

    @JvmStatic
    fun isHidden(context: Context, key: String?): Boolean =
        key != null && key in hiddenKeys(context)

    /** Hides every SubsPipe-hidden preference inside [group], at any depth. */
    @JvmStatic
    fun prune(context: Context, group: PreferenceGroup?) {
        if (group == null) {
            return
        }
        for (i in 0 until group.preferenceCount) {
            val pref = group.getPreference(i)
            if (isHidden(context, pref.key)) {
                pref.isVisible = false
            } else if (pref is PreferenceGroup) {
                prune(context, pref)
            }
        }
    }
}
