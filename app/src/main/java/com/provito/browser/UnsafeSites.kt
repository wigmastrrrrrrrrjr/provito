package com.provito.browser

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.InputStream

/**
 * Unsafe-site shield.
 *
 * Checks every main-frame navigation against open-source lists:
 *
 *  - "malware"  : hard block. No bypass - the site cannot be viewed.
 *  - "phishing" : warn. The user can continue for that host for the rest
 *                 of the session (in RAM only, never persisted).
 *
 * Bundled starter list: assets/unsafe_list.txt (curated patterns from
 * public abuse reporting). Full coverage via the free open-source
 * Phishing Army blocklist: https://phishing.army (daily updated, free).
 *
 * Everything is checked locally; nothing is uploaded anywhere.
 */
class UnsafeSites(private val context: Context) {

    companion object {
        const val DOWNLOAD_URL =
            "https://phishing.army/download/phishing_army_blocklist.txt"
        const val DOWNLOAD_FILE = "unsafe_download.txt"
    }

    @Volatile private var ready = false
    @Volatile private var malwareHosts: Set<String> = emptySet()
    @Volatile private var phishingHosts: Set<String> = emptySet()

    /** Hosts the user explicitly allowed this session (RAM only). */
    private val sessionAllowed =
        java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())

    val malwareCount: Int get() = malwareHosts.size
    val phishingCount: Int get() = phishingHosts.size
    val isReady: Boolean get() = ready

    fun loadAsync() {
        Thread { load() }.start()
    }

    fun load() {
        val malware = HashSet<String>(256)
        val phishing = HashSet<String>(8192)
        runCatching {
            parse(context.assets.open("unsafe_list.txt"), malware, phishing)
        }
        runCatching {
            val f = File(context.filesDir, DOWNLOAD_FILE)
            if (f.exists()) f.inputStream().use { parse(it, malware, phishing) }
        }
        malwareHosts = malware
        phishingHosts = phishing
        ready = true
    }

    /** Sections: "#malware" -> hard block set, "#phishing" -> warn set. */
    private fun parse(input: InputStream, malware: MutableSet<String>, phishing: MutableSet<String>) {
        var section = "phishing"
        input.bufferedReader().useLines { lines ->
            for (raw in lines) {
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("#!")) continue
                if (line.startsWith("#malware")) { section = "malware"; continue }
                if (line.startsWith("#phishing")) { section = "phishing"; continue }
                if (line.startsWith("#")) continue
                val domain = extractDomain(line) ?: continue
                if (domain.length >= 4 && '.' in domain) {
                    if (section == "malware") malware.add(domain) else phishing.add(domain)
                }
            }
        }
    }

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

    /** "malware" | "phishing" | null for a full URL. */
    fun level(url: String): String? {
        if (!ready) return null
        if (!url.startsWith("http://") && !url.startsWith("https://")) return null
        val host = try {
            Uri.parse(url).host?.lowercase()
        } catch (_: Exception) {
            null
        } ?: return null
        if (sessionAllowed.contains(host)) return null

        if (matches(malwareHosts, host)) return "malware"
        if (matches(phishingHosts, host)) return "phishing"
        return null
    }

    private fun matches(set: Set<String>, host: String): Boolean {
        if (set.contains(host)) return true
        var idx = host.indexOf('.')
        while (idx != -1) {
            if (set.contains(host.substring(idx + 1))) return true
            idx = host.indexOf('.', idx + 1)
        }
        return false
    }

    fun allowSession(url: String) {
        try {
            Uri.parse(url).host?.let { sessionAllowed.add(it.lowercase()) }
        } catch (_: Exception) {
            // ignore malformed
        }
    }

    /** Downloads the free open-source Phishing Army blocklist (anonymous GET). */
    fun downloadUpdatedList(): Boolean {
        return try {
            val conn = java.net.URL(DOWNLOAD_URL).openConnection(
                ProxyManager.javaProxy(context) ?: java.net.Proxy.NO_PROXY
            ) as java.net.HttpURLConnection
            conn.connectTimeout = 10000
            conn.readTimeout = 30000
            conn.requestMethod = "GET"
            if (conn.responseCode !in 200..299) {
                conn.disconnect(); return false
            }
            val tmp = File(context.filesDir, "$DOWNLOAD_FILE.tmp")
            conn.inputStream.use { input ->
                tmp.outputStream().use { input.copyTo(it) }
            }
            conn.disconnect()
            val dest = File(context.filesDir, DOWNLOAD_FILE)
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
