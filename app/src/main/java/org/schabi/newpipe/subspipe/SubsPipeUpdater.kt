/*
 * SubsPipe: checks the fork's GitHub releases for a newer build and installs it.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.subspipe

import android.app.Activity
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Environment
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.core.Maybe
import io.reactivex.rxjava3.schedulers.Schedulers
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject
import org.schabi.newpipe.BuildConfig
import org.schabi.newpipe.R

/**
 * Every SubsPipe build from GitHub Actions is published as a release tagged
 * `subspipe-<run number>`, and its versionCode is `100000 + <run number>`.
 * On app start (at most every few hours) this asks GitHub for the latest release and,
 * if it is newer, offers to download it and opens Android's installer.
 */
object SubsPipeUpdater {
    private const val TAG = "SubsPipeUpdater"
    private const val LATEST_RELEASE_URL =
        "https://api.github.com/repos/sidewinderzz/NewPipe/releases/latest"
    private const val TAG_PREFIX = "subspipe-"
    private const val VERSION_CODE_BASE = 100000
    private const val CHECK_INTERVAL_MS = 6L * 60 * 60 * 1000
    private const val PREF_LAST_CHECK = "subspipe_last_update_check"
    private const val APK_MIME = "application/vnd.android.package-archive"

    private data class Release(val buildNumber: Int, val apkUrl: String)

    /** Check for an update if the last check was long enough ago. */
    fun checkInBackground(activity: Activity, force: Boolean = false) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(activity)
        val now = System.currentTimeMillis()
        if (!force && now - prefs.getLong(PREF_LAST_CHECK, 0) < CHECK_INTERVAL_MS) {
            return
        }
        prefs.edit { putLong(PREF_LAST_CHECK, now) }

        val currentBuild = BuildConfig.VERSION_CODE - VERSION_CODE_BASE
        Maybe.fromCallable<Release> { fetchLatestRelease() }
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe(
                { release ->
                    if (release.buildNumber > currentBuild && !activity.isFinishing) {
                        showUpdateDialog(activity, release)
                    } else if (force) {
                        Toast.makeText(activity, R.string.subspipe_up_to_date, Toast.LENGTH_SHORT).show()
                    }
                },
                { Log.w(TAG, "Update check failed", it) }
            )
    }

    /** @return the latest release, or null (an empty Maybe) if there is none with an APK */
    private fun fetchLatestRelease(): Release? {
        val connection = URL(LATEST_RELEASE_URL).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 15000
            connection.readTimeout = 15000
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                return null
            }
            val json = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            val tag = json.optString("tag_name")
            if (!tag.startsWith(TAG_PREFIX)) {
                return null
            }
            val buildNumber = tag.removePrefix(TAG_PREFIX).toIntOrNull() ?: return null
            val assets = json.optJSONArray("assets") ?: return null
            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                if (asset.optString("name").endsWith(".apk")) {
                    return Release(buildNumber, asset.getString("browser_download_url"))
                }
            }
            return null
        } finally {
            connection.disconnect()
        }
    }

    private fun showUpdateDialog(activity: Activity, release: Release) {
        AlertDialog.Builder(activity)
            .setTitle(R.string.subspipe_update_title)
            .setMessage(activity.getString(R.string.subspipe_update_message, release.buildNumber))
            .setPositiveButton(R.string.subspipe_update_now) { _, _ -> download(activity, release) }
            .setNegativeButton(R.string.subspipe_update_later, null)
            .show()
    }

    private fun download(activity: Activity, release: Release) {
        val context = activity.applicationContext
        val downloadManager = ContextCompat.getSystemService(context, DownloadManager::class.java)
            ?: return
        val fileName = "SubsPipe-${release.buildNumber}.apk"
        val request = DownloadManager.Request(Uri.parse(release.apkUrl))
            .setTitle(fileName)
            .setMimeType(APK_MIME)
            .setNotificationVisibility(
                DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
            )
            .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, fileName)
        val downloadId = downloadManager.enqueue(request)
        Toast.makeText(activity, R.string.subspipe_update_downloading, Toast.LENGTH_SHORT).show()

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context, intent: Intent) {
                if (intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1) != downloadId) {
                    return
                }
                context.unregisterReceiver(this)
                val uri = downloadManager.getUriForDownloadedFile(downloadId) ?: return
                val install = Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, APK_MIME)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                try {
                    context.startActivity(install)
                } catch (e: Exception) {
                    Log.w(TAG, "Could not open the installer", e)
                }
            }
        }
        ContextCompat.registerReceiver(
            context, receiver, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
            ContextCompat.RECEIVER_EXPORTED
        )
    }
}
