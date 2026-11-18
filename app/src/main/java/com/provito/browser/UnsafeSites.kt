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
 * Sources (all free/open, verified live):
 *  - Phishing Army (community phishing blocklist, MIT)
 *  - malware-filter phishing-filter (Phishing Army/URLhaus derived, MIT/CC0)
 *  - malware-filter urlhaus-filter (abuse.ch URLhaus mirror, CC0)
 *  - OpenPhish free community feed
 *  - Spam404 community blacklist
 *  - stamparm/blackbook (malware domains)
 *
 * A bundled starter list covers offline use before any download.
 * Everything is checked locally; nothing is uploaded anywhere.
 */
class UnsafeSites(private val context: Context) {

    companion object {
        const val DOWNLOAD_FILE = "unsafe_download.txt"

        /** Open-source lists with their severity. */
        val SOURCES = arrayOf(
            // phishing Army community blocklist (extended)
            Source(
                "https://phishing.army/download/phishing_army_blocklist_extended.txt",
                "phishing", "Phishing Army"
            ),
            // malware-filter phishing blocklist (daily, MIT/CC0)
            Source(
                "https://malware-filter.gitlab.io/malware-filter/phishing-filter-domains.txt",
                "phishing", "phishing-filter"
            ),
            // malware-filter urlhaus mirror (malware distribution domains, CC0)
            Source(
                "https://malware-filter.gitlab.io/malware-filter/urlhaus-filter-domains.txt",
                "malware", "URLhaus filter"
            ),
            // OpenPhish free community feed (URLs; hosts extracted)
            Source("https://openphish.com/feed.txt", "phishing", "OpenPhish"),
            // Spam404 community scam/phishing blacklist
            Source(
                "https://raw.githubusercontent.com/Spam404/lists/master/main-blacklist.txt",
                "phishing", "Spam404"
            ),
            // stamparm/blackbook malware domains
            Source(
                "https://raw.githubusercontent.com/stamparm/blackbook/master/blackbook.txt",
                "malware", "Blackbook"
            )
        )
    }

    data class Source(val url: String, val severity: String, val name: String) {
        val cacheFile: String get() = "unsafe_src_${SOURCES.indexOf(this)}.txt"
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
        val malware = HashSet<String>(2048)
        val phishing = HashSet<String>(65536)

        // Bundled starter list (severity via #malware/#phishing sections).
        runCatching {
            parse(context.assets.open("unsafe_list.txt"), malware, phishing, null)
        }
        // Downloaded sources, severity per source config.
        for (src in SOURCES) {
            val f = File(context.filesDir, src.cacheFile)
            if (f.exists()) {
                runCatching {
                    f.inputStream().use { parse(it, malware, phishing, src.severity) }
                }
            }
        }
        // Legacy single-file download (older versions).
        val legacy = File(context.filesDir, DOWNLOAD_FILE)
        if (legacy.exists()) {
            runCatching {
                legacy.inputStream().use { parse(it, malware, phishing, "phishing") }
            }
        }

        malwareHosts = malware
        phishingHosts = phishing
        ready = true
    }

    /**
     * Parses a list. When [forcedSeverity] is null the file can switch sets
     * with "#malware" / "#phishing" section markers (bundled list format).
     * Handles plain domains, hosts-file lines and full URLs.
     */
    private fun parse(
        input: InputStream,
        malware: MutableSet<String>,
        phishing: MutableSet<String>,
        forcedSeverity: String?
    ) {
        var section = "phishing"
        input.bufferedReader().useLines { lines ->
            for (raw in lines) {
                val line = raw.trim()
                if (line.isEmpty()) continue
                if (line.startsWith("#malware") && forcedSeverity == null) {
                    section = "malware"; continue
                }
                if (line.startsWith("#phishing") && forcedSeverity == null) {
                    section = "phishing"; continue
                }
                if (line.startsWith("#") || line.startsWith("//")) continue
                val domain = extractDomain(line) ?: continue
                if (domain.length < 4 || '.' !in domain) continue
                if (forcedSeverity == "malware" || (forcedSeverity == null && section == "malware")) {
                    malware.add(domain)
                } else {
                    phishing.add(domain)
                }
            }
        }
    }

    /** Handles domains, hosts-file entries and full URLs. */
    private fun extractDomain(line: String): String? {
        if (line.contains("://")) {
            return try {
                Uri.parse(line).host?.lowercase()?.trimEnd('.')
            } catch (_: Exception) {
                null
            }
        }
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

    /**
     * Downloads every open-source source (anonymous GETs, sequential).
     * Returns the number of sources that succeeded.
     */
    fun downloadAllSources(): Int {
        var ok = 0
        for (src in SOURCES) {
            if (downloadOne(src)) ok++
        }
        load()
        return ok
    }

    private fun downloadOne(src: Source): Boolean {
        return try {
            val conn = java.net.URL(src.url).openConnection(
                ProxyManager.javaProxy(context) ?: java.net.Proxy.NO_PROXY
            ) as java.net.HttpURLConnection
            conn.connectTimeout = 10000
            conn.readTimeout = 30000
            conn.requestMethod = "GET"
            if (conn.responseCode !in 200..299) {
                conn.disconnect(); return false
            }
            val tmp = File(context.filesDir, "${src.cacheFile}.tmp")
            conn.inputStream.use { input ->
                tmp.outputStream().use { input.copyTo(it) }
            }
            conn.disconnect()
            tmp.renameTo(File(context.filesDir, src.cacheFile))
        } catch (_: Exception) {
            false
        }
    }
}
