package com.provito.browser

import android.content.Context
import android.content.Intent
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket

/**
 * Provito's IP-hiding layer.
 *
 * Routes all WebView traffic through a proxy so websites never see the
 * device's real IP:
 *
 *  - Onion mode (free, open source): local HTTP proxy exposed by the
 *    Orbot app (https://github.com/guardianproject/orbot, Tor for
 *    Android). Sites see a Tor exit IP. Orbot is free software with no
 *    accounts and no logs - we deliberately avoid sketchy "free VPN API"
 *    providers, because a free anonymous VPN would still see your traffic.
 *  - Custom proxy: a user-provided trusted proxy takes precedence.
 *
 * Proxying is applied through androidx.webkit's ProxyController, so it
 * covers every request the WebView makes. DNS resolution is done remotely
 * by the Tor network when Orbot is used, so it does not leak either.
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

    /** A java.net.Proxy for the app's own HTTP calls (updater, blocklists). */
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

    fun applyToWebView(context: Context, onDone: (Boolean) -> Unit) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
            onDone(false)
            return
        }
        val controller = ProxyController.getInstance()
        val executor = java.util.concurrent.Executor { it.run() }
        val rule = rules(context)
        runCatching {
            if (rule != null) {
                val config = ProxyConfig.Builder()
                    .addProxyRule(rule)
                    .addDirect()
                    .build()
                controller.setProxyOverride(config, executor, Runnable { })
            } else {
                controller.clearProxyOverride(executor, Runnable { })
            }
            onDone(true)
        }.onFailure {
            onDone(false)
        }
    }

    fun openOrbot(context: Context) {
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri(ORBOT_PACKAGE))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.onFailure {
            runCatching {
                context.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri("market://details?id=$ORBOT_PACKAGE"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }
    }

    private fun Uri(u: String): android.net.Uri = android.net.Uri.parse(u)
}
