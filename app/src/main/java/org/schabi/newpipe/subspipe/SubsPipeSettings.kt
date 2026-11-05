/*
 * SubsPipe: trims settings that don't apply to a subscriptions-only app.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.subspipe

import android.content.Context
import androidx.preference.PreferenceGroup
import androidx.preference.PreferenceManager
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

    private const val DEFAULTS_VERSION_KEY = "subspipe_defaults_version"
    private const val DEFAULTS_VERSION = 1

    /**
     * Applies the defaults chosen for SubsPipe once per install (and again only when
     * [DEFAULTS_VERSION] goes up), so later changes made in settings are kept.
     */
    @JvmStatic
    fun applyDefaultsOnce(context: Context) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        if (prefs.getInt(DEFAULTS_VERSION_KEY, 0) >= DEFAULTS_VERSION) {
            return
        }
        prefs.edit()
            // Under a video: no YouTube recommendations, no info boxes
            .putBoolean(context.getString(R.string.show_next_video_key), false)
            .putBoolean(context.getString(R.string.show_meta_info_key), false)
            .putInt(DEFAULTS_VERSION_KEY, DEFAULTS_VERSION)
            .apply()
    }

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
