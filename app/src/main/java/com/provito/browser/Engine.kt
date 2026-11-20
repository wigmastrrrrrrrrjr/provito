package com.provito.browser

import android.content.Context
import org.mozilla.geckoview.ContentBlocking
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings

/**
 * Owns the single GeckoRuntime (the Firefox engine instance).
 *
 * Provito used to render with Android's system WebView; it now embeds
 * GeckoView, Mozilla's independent browser engine, so rendering is not tied
 * to whatever WebView the device happens to ship.
 */
object Engine {

    @Volatile
    private var runtime: GeckoRuntime? = null

    /** Must be called on the main thread (GeckoRuntime.create requires it). */
    fun runtime(context: Context): GeckoRuntime {
        runtime?.let { return it }
        synchronized(this) {
            runtime?.let { return it }
            val created = GeckoRuntime.create(context.applicationContext, buildSettings())
            runCatching {
                created.settings.setFingerprintingProtection(true)
                created.settings.setFingerprintingProtectionPrivateBrowsing(true)
            }
            runtime = created
            return created
        }
    }

    private fun buildSettings(): GeckoRuntimeSettings {
        val blocking = ContentBlocking.Settings.Builder()
            .antiTracking(
                ContentBlocking.AntiTracking.AD or
                    ContentBlocking.AntiTracking.ANALYTIC or
                    ContentBlocking.AntiTracking.SOCIAL or
                    ContentBlocking.AntiTracking.CONTENT or
                    ContentBlocking.AntiTracking.CRYPTOMINING or
                    ContentBlocking.AntiTracking.FINGERPRINTING or
                    ContentBlocking.AntiTracking.STP
            )
            .safeBrowsing(
                ContentBlocking.SafeBrowsing.MALWARE or
                    ContentBlocking.SafeBrowsing.PHISHING or
                    ContentBlocking.SafeBrowsing.UNWANTED or
                    ContentBlocking.SafeBrowsing.HARMFUL
            )
            .cookieBehavior(ContentBlocking.CookieBehavior.ACCEPT_FIRST_PARTY)
            .cookieBehaviorPrivateMode(ContentBlocking.CookieBehavior.ACCEPT_FIRST_PARTY)
            .enhancedTrackingProtectionLevel(ContentBlocking.EtpLevel.STRICT)
            .queryParameterStrippingEnabled(true)
            .queryParameterStrippingPrivateBrowsingEnabled(true)
            .cookiePurging(true)
            .build()

        return GeckoRuntimeSettings.Builder()
            .javaScriptEnabled(true)
            .contentBlocking(blocking)
            .globalPrivacyControlEnabled(true)
            .webManifest(false)
            .consoleOutput(false)
            .remoteDebuggingEnabled(false)
            .aboutConfigEnabled(false)
            .loginAutofillEnabled(false)
            .automaticFontSizeAdjustment(true)
            .fontInflation(true)
            .build()
    }
}
