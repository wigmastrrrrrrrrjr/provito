package com.provito.browser

import android.content.Context
import android.content.Intent
import org.mozilla.geckoview.GeckoPreferenceController
import org.mozilla.geckoview.GeckoResult
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket

/**
 * Provito's IP-hiding layer.
 *
 * Routes all browser traffic through a proxy so websites never see the
 * device's real IP:
 *
 *  - Onion mode (free, open source): local HTTP proxy exposed by the
 *    Orbot app (https://github.com/guardianproject/orbot, Tor for
 *    Android). Sites see a Tor exit IP. Orbot is free software with no
 *    accounts and no logs - we deliberately avoid sketchy "free VPN API"
 *    providers, because a free anonymous VPN would still see your traffic.
 *  - Custom proxy: a user-provided trusted proxy takes precedence.
 *
 * GeckoView has no ProxyController API, so the proxy is configured through
 * Gecko's own network preferences (network.proxy.*). Those prefs cover every
 * request the engine makes, including DNS lookups, which are resolved by the
 * proxy rather than on-device.
 */
object ProxyManager {

    const val ORBOT_PACKAGE = "org.torproject.android"
    private const val ORBOT_HTTP_PORT = 8118
    const val KEY_ONION_MODE = "onion_mode"
    const val KEY_CUSTOM_PROXY = "custom_proxy"

    fun isOrbotInstalled(context: Context): Boolean = runCatching {
        context.packageManager.getPackageInfo(ORBOT_PACKAGE, 0)
    }.isSuccess

    /** Orbot exposes a local HTTP proxy once its VPN/Tor is up. */
    fun isOrbotProxyUp(): Boolean = runCatching {
        Socket().use { s ->
            s.connect(InetSocketAddress("127.0.0.1", ORBOT_HTTP_PORT), 1000)
            true
        }
    }.getOrDefault(false)

    /** Effective proxy rule ("host:port"), or null when proxying is off. */
    fun rules(context: Context): String? {
        val prefs = context.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE)
        val custom = prefs.getString(KEY_CUSTOM_PROXY, "")?.trim().orEmpty()
        if (custom.isNotEmpty()) return custom
        return if (prefs.getBoolean(KEY_ONION_MODE, false) && isOrbotProxyUp()) {
            "127.0.0.1:$ORBOT_HTTP_PORT"
        } else {
            null
        }
    }

    /** A java.net.Proxy for the app's own HTTP calls (updater, unsafe lists). */
    fun javaProxy(context: Context): Proxy? {
        val rule = rules(context) ?: return null
        val clean = rule.removePrefix("http=").substringBefore(';')
        val host = clean.substringBeforeLast(':')
        val port = clean.substringAfterLast(':').filter { it.isDigit() }.toIntOrNull() ?: return null
        return try {
            Proxy(Proxy.Type.HTTP, InetSocketAddress(host, port))
        } catch (_: Exception) {
            null
        }
    }

    /** Applies the current proxy rule to Gecko's network preferences. */
    fun applyToGecko(context: Context, onDone: (Boolean) -> Unit) {
        val results = ArrayList<GeckoResult<Void>>()
        try {
            val rule = rules(context)
            if (rule == null) {
                results.add(set("network.proxy.type", 0))
            } else {
                val clean = rule.removePrefix("http=").substringBefore(';')
                val host = clean.substringBeforeLast(':').ifBlank { "127.0.0.1" }
                val port = clean.substringAfterLast(':').filter { it.isDigit() }.toIntOrNull() ?: 8118
                results.add(set("network.proxy.type", 1))
                results.add(set("network.proxy.http", host))
                results.add(set("network.proxy.http_port", port))
                results.add(set("network.proxy.ssl", host))
                results.add(set("network.proxy.ssl_port", port))
                results.add(set("network.proxy.share_proxy_settings", true))
                results.add(set("network.proxy.no_proxies_on", ""))
            }
        } catch (_: Throwable) {
            onDone(false)
            return
        }
        GeckoResult.allOf(results).accept(
            { onDone(true) },
            { onDone(false) }
        )
    }

    private fun set(name: String, value: Any): GeckoResult<Void> = when (value) {
        is Boolean -> GeckoPreferenceController.setGeckoPref(
            name, value, GeckoPreferenceController.PREF_BRANCH_USER
        )
        is Int -> GeckoPreferenceController.setGeckoPref(
            name, value, GeckoPreferenceController.PREF_BRANCH_USER
        )
        else -> GeckoPreferenceController.setGeckoPref(
            name, value.toString(), GeckoPreferenceController.PREF_BRANCH_USER
        )
    }

    fun openOrbot(context: Context) {
        val market = Intent(
            Intent.ACTION_VIEW,
            android.net.Uri.parse("market://details?id=$ORBOT_PACKAGE")
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val web = Intent(
            Intent.ACTION_VIEW,
            android.net.Uri.parse("https://play.google.com/store/apps/details?id=$ORBOT_PACKAGE")
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(market) }
            .onFailure { runCatching { context.startActivity(web) } }
    }
}
