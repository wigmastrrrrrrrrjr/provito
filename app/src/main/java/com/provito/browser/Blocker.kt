package com.provito.browser

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.InputStream

/**
 * Provito's blocking engine.
 *
 * Combines:
 *  - a bundled offline blocklist (assets/blocklist.txt)
 *  - an optionally downloaded open-source hosts list
 *    (StevenBlack/hosts: https://github.com/StevenBlack/hosts, MIT)
 *
 * Rules:
 *  - plain domain lines match the host or any subdomain of it
 *  - lines starting with "url:" are plain substring rules applied to the full URL
 *
 * Nothing is uploaded anywhere: all matching happens locally in this process.
 * The list is parsed off the main thread so startup stays fast even with
 * ~100k downloaded rules.
 */
class Blocker(private val context: Context) {

    @Volatile var enabled = true

    @Volatile private var ready = false
    @Volatile private var blockedHosts: Set<String> = emptySet()
    @Volatile private var urlRules: List<String> = emptyList()

    val hostCount: Int get() = blockedHosts.size
    val isReady: Boolean get() = ready

    /** Parse the blocklists on a background thread; ready flips when done. */
    fun loadAsync() {
        Thread { load() }.start()
    }

    fun load() {
        val hosts = HashSet<String>(8192)
        val rules = ArrayList<String>()
        runCatching { parse(context.assets.open("blocklist.txt"), hosts, rules) }
        runCatching {
            val f = File(context.filesDir, "blocklist_download.txt")
            if (f.exists()) f.inputStream().use { parse(it, hosts, rules) }
        }
        // Atomic swap - readers never see a half-built set.
        urlRules = rules
        blockedHosts = hosts
        ready = true
    }

    private fun parse(input: InputStream, hosts: MutableSet<String>, rules: MutableList<String>) {
        input.bufferedReader().useLines { lines ->
            for (raw in lines) {
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("#")) continue
                if (line.startsWith("url:")) {
                    val rule = line.removePrefix("url:")
                    if (rule.length > 4) rules.add(rule)
                    continue
                }
                val domain = extractDomain(line) ?: continue
                if (domain.length >= 4 && '.' in domain) hosts.add(domain)
            }
        }
    }

    /** Handles hosts-file lines like "0.0.0.0 domain.com" and plain "domain.com". */
    private fun extractDomain(line: String): String? {
        val parts = line.split(Regex("\\s+"))
        for (p in parts) {
            val lower = p.lowercase()
            if (lower == "0.0.0.0" || lower == "127.0.0.1" || lower == "::1") continue
            if (lower.endsWith(".local") || lower == "localhost") continue
            return lower.trimEnd('.')
        }
        return null
    }

    fun shouldBlock(urlString: String): Boolean {
        if (!enabled || !ready) return false
        if (urlString.startsWith("data:") ||
            urlString.startsWith("blob:") ||
            urlString.startsWith("about:")
        ) return false

        val host = extractHost(urlString)
        if (host != null) {
            if (blockedHosts.contains(host)) return true
            // check parent domains: sub.a.tracker.com -> a.tracker.com -> tracker.com -> com
            var idx = host.indexOf('.')
            while (idx != -1) {
                if (blockedHosts.contains(host.substring(idx + 1))) return true
                idx = host.indexOf('.', idx + 1)
            }
        }
        val rules = urlRules
        if (rules.isNotEmpty()) {
            for (rule in rules) if (rule in urlString) return true
        }
        return false
    }

    private fun extractHost(url: String): String? {
        return try {
            Uri.parse(url).host?.lowercase()
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Downloads the open-source StevenBlack unified hosts blocklist (ads + trackers).
     * This is opt-in and does a plain anonymous GET - no identifiers are sent.
     */
    fun downloadUpdatedList(): Boolean {
        return try {
            val url = java.net.URL(
                "https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts"
            )
            val conn = url.openConnection(
                ProxyManager.javaProxy(context) ?: java.net.Proxy.NO_PROXY
            ) as java.net.HttpURLConnection
            conn.connectTimeout = 10000
            conn.readTimeout = 20000
            conn.requestMethod = "GET"
            if (conn.responseCode !in 200..299) {
                conn.disconnect(); return false
            }
            val tmp = File(context.filesDir, "blocklist_download.tmp")
            conn.inputStream.use { input ->
                tmp.outputStream().use { input.copyTo(it) }
            }
            conn.disconnect()
            val dest = File(context.filesDir, "blocklist_download.txt")
            if (tmp.renameTo(dest)) {
                load()
                true
            } else {
                tmp.delete()
                false
            }
        } catch (_: Exception) {
            false
        }
    }
}
