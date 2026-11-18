package com.provito.browser

import android.app.AlertDialog
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Provito's self-updater.
 *
 * Checks the public GitHub Releases API (an anonymous GET - no identifiers,
 * no telemetry) and, when a newer release is found, shows the release notes
 * and lets the user download and install the update with one tap.
 */
object Updater {

    const val REPO = "wigmastrrrrrrrrjr/provito"
    private const val API = "https://api.github.com/repos/$REPO/releases/latest"
    const val PREF_UPDATE_CHECK = "update_check_enabled"

    private class ReleaseInfo(val version: String, val notes: String, val apkUrl: String)

    fun maybeCheck(activity: MainActivity, automatic: Boolean) {
        val prefs = activity.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE)
        if (automatic && !prefs.getBoolean(PREF_UPDATE_CHECK, true)) return

        Thread {
            val release = fetchLatest(activity) ?: run {
                if (!automatic) {
                    activity.runOnUiThread {
                        Toast.makeText(activity, "Could not check for updates", Toast.LENGTH_SHORT).show()
                    }
                }
                return@Thread
            }

            val current = activity.getCurrentVersionName()
            if (!isNewer(release.version, current)) {
                if (!automatic) {
                    activity.runOnUiThread {
                        Toast.makeText(activity, "Provito is up to date (v$current)", Toast.LENGTH_SHORT).show()
                    }
                }
                return@Thread
            }

            activity.runOnUiThread {
                AlertDialog.Builder(activity)
                    .setTitle("Update available - v${release.version}")
                    .setMessage("Would you like to update? This update has:\n\n${release.notes}")
                    .setPositiveButton("Update") { _, _ -> download(activity, release) }
                    .setNegativeButton("Not now", null)
                    .show()
            }
        }.start()
    }

    private fun fetchLatest(context: Context): ReleaseInfo? {
        return try {
            val conn = URL(API).openConnection(
                ProxyManager.javaProxy(context) ?: java.net.Proxy.NO_PROXY
            ) as HttpURLConnection
            conn.connectTimeout = 10000
            conn.readTimeout = 15000
            conn.setRequestProperty("User-Agent", "Provito-Updater")
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            if (conn.responseCode !in 200..299) {
                conn.disconnect()
                return null
            }
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()

            val tag = regexFind(text, "\"tag_name\"\\s*:\\s*\"([^\"]+)\"") ?: return null
            val version = tag.removePrefix("v")
            val notesRaw = regexFind(text, "\"body\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"") ?: ""
            val notes = unescape(notesRaw)
                .replace(Regex("#\\d+"), "")
                .trim()
                .ifBlank { "Bug fixes and improvements." }
                .let { if (it.length > 1500) it.take(1500) + "..." else it }
            val apk = regexFind(text, "\"browser_download_url\"\\s*:\\s*\"([^\"]+\\.apk)\"")
                ?: return null
            ReleaseInfo(version, notes, apk)
        } catch (_: Exception) {
            null
        }
    }

    private fun regexFind(text: String, pattern: String): String? {
        val m = Regex(pattern).find(text) ?: return null
        return m.groupValues.getOrNull(1)
    }

    private fun unescape(s: String): String {
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (val n = s[i + 1]) {
                    'n' -> sb.append('\n')
                    't' -> sb.append('\t')
                    '"' -> sb.append('"')
                    '\\' -> sb.append('\\')
                    '/' -> sb.append('/')
                    'u' -> {
                        if (i + 5 < s.length) {
                            val code = s.substring(i + 2, i + 6).toIntOrNull(16)
                            if (code != null) {
                                sb.append(code.toChar())
                                i += 4
                            }
                        }
                    }
                    else -> sb.append(n)
                }
                i += 2
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString()
    }

    /** Semantic comparison: 1 < 1.0.1 < 1.1 < 2.0 */
    private fun isNewer(remote: String, local: String): Boolean {
        val r = remote.split('.').map { it.filter { ch -> ch.isDigit() }.toIntOrNull() ?: 0 }
        val l = local.split('.').map { it.filter { ch -> ch.isDigit() }.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(r.size, l.size)) {
            val rv = r.getOrElse(i) { 0 }
            val lv = l.getOrElse(i) { 0 }
            if (rv != lv) return rv > lv
        }
        return false
    }

    private fun download(activity: MainActivity, release: ReleaseInfo) {
        val name = "provito-update-${release.version}.apk"
        val dm = activity.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val request = DownloadManager.Request(Uri.parse(release.apkUrl))
            .setTitle(name)
            .setDescription("Provito update")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(
                activity, Environment.DIRECTORY_DOWNLOADS, name
            )
        val id = try {
            dm.enqueue(request)
        } catch (_: Exception) {
            Toast.makeText(activity, "Download failed", Toast.LENGTH_SHORT).show()
            return
        }

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1) != id) return
                activity.unregisterReceiver(this)
                install(activity, File(activity.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), name))
            }
        }
        val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        if (Build.VERSION.SDK_INT >= 33) {
            activity.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            activity.registerReceiver(receiver, filter)
        }
        Toast.makeText(activity, "Downloading update...", Toast.LENGTH_SHORT).show()
    }

    private fun install(activity: MainActivity, apk: File) {
        if (!apk.exists() || apk.length() == 0L) {
            Toast.makeText(activity, "Update file missing", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val staged = File(activity.filesDir, "update.apk")
            apk.inputStream().use { input ->
                FileOutputStream(staged).use { output -> input.copyTo(output) }
            }
            apk.delete()
            val uri = FileProvider.getUriForFile(
                activity, "${activity.packageName}.fileprovider", staged
            )
            val intent = Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            activity.startActivity(intent)
        } catch (_: Exception) {
            Toast.makeText(activity, "Could not start installer", Toast.LENGTH_SHORT).show()
        }
    }

    fun setEnabled(activity: MainActivity, enabled: Boolean) {
        activity.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(PREF_UPDATE_CHECK, enabled)
            .apply()
    }

    fun isEnabled(activity: MainActivity): Boolean =
        activity.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE)
            .getBoolean(PREF_UPDATE_CHECK, true)
}
