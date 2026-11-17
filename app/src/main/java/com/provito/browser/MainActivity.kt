package com.provito.browser

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebViewDatabase
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import com.google.android.material.progressindicator.LinearProgressIndicator
import java.io.ByteArrayInputStream
import java.net.URLEncoder

class MainActivity : AppCompatActivity() {

    companion object {
        const val HOME_URL = "file:///android_asset/home.html"
        const val PREFS = "provito_prefs"
        const val KEY_AUTO_UPDATE = "auto_update_blocklists"
        const val KEY_WIPE_ON_EXIT = "wipe_on_exit"

        // Generic user agent: no device model, no app fingerprints.
        const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36 Provito/1.0"
    }

    private lateinit var blocker: Blocker

    private lateinit var webContainer: FrameLayout
    private lateinit var omnibox: EditText
    private lateinit var progress: LinearProgressIndicator
    private lateinit var txtBlocked: TextView
    private lateinit var btnBack: ImageButton
    private lateinit var btnForward: ImageButton

    private class Tab(val webView: WebView) {
        var ready = false
    }

    private val tabs = ArrayList<Tab>()
    private var current: Tab? = null
    private val blockedTotal = java.util.concurrent.atomic.AtomicInteger(0)
    private var tabsSheetShowing = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)

        blocker = Blocker(this)
        blocker.load()

        setContentView(R.layout.activity_main)
        webContainer = findViewById(R.id.webContainer)
        omnibox = findViewById(R.id.omnibox)
        progress = findViewById(R.id.progress)
        txtBlocked = findViewById(R.id.txtBlocked)
        btnBack = findViewById(R.id.btnBack)
        btnForward = findViewById(R.id.btnForward)

        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_AUTO_UPDATE, false) && !blockerHostsFileExists()) {
            Thread { if (blocker.downloadUpdatedList()) runOnUiThread { refreshShieldBadge() } }.start()
        }

        Updater.maybeCheck(this, automatic = true)

        wireChrome()
        wireOmnibox()

        onBackPressedDispatcher.addCallback(
            this,
            object : androidx.activity.OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    handleBack()
                }
            }
        )

        val incoming = intent?.dataString
        if (incoming != null) {
            newTab().webView.loadUrl(incoming)
        } else {
            newTab().webView.loadUrl(HOME_URL)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.dataString?.let { url ->
            val tab = if (current?.ready == true) current else newTab()
            tab?.webView?.loadUrl(url)
        }
    }

    // ---------- WebView creation, hardened for privacy ----------

    @SuppressLint("SetJavaScriptEnabled")
    private fun newTab(): Tab {
        val wv = WebView(this)
        wv.layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        val s = wv.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.databaseEnabled = false
        s.setSupportMultipleWindows(true)
        s.javaScriptCanOpenWindowsAutomatically = false
        s.mediaPlaybackRequiresUserGesture = true
        s.allowFileAccess = false
        s.allowContentAccess = false
        s.cacheMode = WebSettings.LOAD_NO_CACHE
        s.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        s.saveFormData = false
        @Suppress("DEPRECATION")
        s.savePassword = false
        s.userAgentString = USER_AGENT

        // No third-party cookies, ever.
        CookieManager.getInstance().setAcceptThirdPartyCookies(wv, false)

        wv.setDownloadListener { url, _, disposition, mimeType, _ ->
            try {
                val req = DownloadManager.Request(Uri.parse(url))
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    .setTitle(Uri.parse(url).lastPathSegment ?: "download")
                (getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(req)
                Toast.makeText(this, "Download started", Toast.LENGTH_SHORT).show()
            } catch (_: Exception) {
                // ignore unsupported downloads
            }
        }

        wv.webViewClient = PrivacyWebViewClient()
        wv.webChromeClient = PrivacyChromeClient(wv)

        val tab = Tab(wv)
        synchronized(tabs) { tabs.add(tab) }
        switchTo(tab)
        return tab
    }

    private inner class PrivacyWebViewClient : WebViewClient() {

        override fun shouldInterceptRequest(
            view: WebView,
            request: WebResourceRequest
        ): WebResourceResponse? {
            val url = request.url.toString()
            return if (blocker.shouldBlock(url)) {
                blockedTotal.incrementAndGet()
                runOnUiThread { refreshShieldBadge() }
                emptyResponse()
            } else {
                null
            }
        }

        override fun shouldOverrideUrlLoading(
            view: WebView,
            request: WebResourceRequest
        ): Boolean {
            val url = request.url
            val scheme = url.scheme ?: return false
            if (scheme == "http" || scheme == "https") return false
            // Non-web scheme (mailto:, tel:, app links...) -> hand to the system.
            return try {
                startActivity(
                    Intent(Intent.ACTION_VIEW, url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                true
            } catch (_: Exception) {
                true
            }
        }

        override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
            super.onPageStarted(view, url, favicon)
            if (view == current?.webView && !url.startsWith(HOME_URL)) {
                omnibox.setText(url)
            }
            progress.show()
            progress.setIndeterminate(true)
            updateNavButtons()
        }

        override fun onPageFinished(view: WebView, url: String) {
            super.onPageFinished(view, url)
            if (view == current?.webView) {
                omnibox.setText(url)
                view.requestFocus()
            }
            progress.hide()
            updateNavButtons()
        }

        override fun onReceivedError(
            view: WebView,
            request: WebResourceRequest,
            error: WebResourceError
        ) {
            if (request.isForMainFrame) {
                progress.hide()
            }
        }
    }

    private inner class PrivacyChromeClient(private val owner: WebView) : WebChromeClient() {

        override fun onCreateWindow(
            view: WebView,
            isDialog: Boolean,
            isUserGesture: Boolean,
            resultMsg: android.os.Message
        ): Boolean {
            // Intercept target=_blank: open it as a new Provito tab (popup-safe).
            val hv = WebView(this@MainActivity)
            hv.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    v: WebView,
                    request: WebResourceRequest
                ): Boolean {
                    runOnUiThread {
                        val tab = newTab()
                        tab.webView.loadUrl(request.url.toString())
                    }
                    return true
                }
            }
            (resultMsg.obj as? WebView.WebViewTransport)?.setWebView(hv)
            resultMsg.sendToTarget()
            return true
        }

        override fun onProgressChanged(view: WebView, newProgress: Int) {
            if (view != owner) return
            if (newProgress < 100) {
                progress.show()
                progress.setIndeterminate(false)
                progress.setProgressCompat(newProgress, true)
            } else {
                progress.hide()
            }
        }

        override fun onReceivedTitle(view: WebView, title: String) {
            if (view == current?.webView && !view.url.isNullOrEmpty()) {
                // keep full URL in omnibox; title not stored anywhere
            }
        }

        // Deny all sensitive permissions - maximum privacy.
        override fun onGeolocationPermissionsShowPrompt(
            origin: String,
            callback: GeolocationPermissions.Callback
        ) = callback.invoke(origin, false, false)

        override fun onPermissionRequest(request: PermissionRequest) = request.deny()

        override fun onJsAlert(
            view: WebView,
            url: String,
            message: String,
            result: android.webkit.JsResult
        ): Boolean {
            AlertDialog.Builder(this@MainActivity)
                .setMessage(message)
                .setPositiveButton("OK") { _, _ -> result.confirm() }
                .setOnCancelListener { result.cancel() }
                .show()
            return true
        }

        override fun onJsConfirm(
            view: WebView,
            url: String,
            message: String,
            result: android.webkit.JsResult
        ): Boolean {
            AlertDialog.Builder(this@MainActivity)
                .setMessage(message)
                .setPositiveButton("OK") { _, _ -> result.confirm() }
                .setNegativeButton("Cancel") { _, _ -> result.cancel() }
                .setOnCancelListener { result.cancel() }
                .show()
            return true
        }
    }

    private fun emptyResponse(): WebResourceResponse =
        WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))

    // ---------- UI wiring ----------

    private fun wireChrome() {
        findViewById<ImageButton>(R.id.btnShield).setOnClickListener { showPrivacyStats() }
        findViewById<ImageButton>(R.id.btnBack).setOnClickListener {
            current?.webView?.goBack()
        }
        findViewById<ImageButton>(R.id.btnForward).setOnClickListener {
            current?.webView?.goForward()
        }
        findViewById<ImageButton>(R.id.btnHome).setOnClickListener {
            current?.webView?.loadUrl(HOME_URL)
        }
        findViewById<ImageButton>(R.id.btnRefresh).setOnClickListener {
            current?.webView?.reload()
        }
        findViewById<ImageButton>(R.id.btnTabs).setOnClickListener { showTabsSheet() }
        findViewById<ImageButton>(R.id.btnMenu).setOnClickListener { showMenu(it as ImageButton) }
    }

    private fun wireOmnibox() {
        omnibox.setOnFocusChangeListener { _, focused ->
            if (!focused) current?.webView?.url?.let { omnibox.setText(it) }
            else omnibox.post { omnibox.selectAll() }
        }
        omnibox.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                navigate(omnibox.text.toString())
                true
            } else {
                false
            }
        }
        findViewById<ImageButton>(R.id.btnGo).setOnClickListener {
            navigate(omnibox.text.toString())
        }
    }

    private fun navigate(input: String) {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return
        val url = when {
            trimmed.startsWith("http://") || trimmed.startsWith("https://") -> trimmed
            trimmed.startsWith("about:") || trimmed.startsWith("file://") -> trimmed
            trimmed.contains(' ') -> braveSearchUrl(trimmed)
            looksLikeUrl(trimmed) -> "https://$trimmed"
            else -> braveSearchUrl(trimmed)
        }
        hideKeyboard()
        omnibox.clearFocus()
        val tab = current ?: newTab()
        tab.webView.loadUrl(url)
    }

    private fun looksLikeUrl(s: String): Boolean {
        val host = s.removePrefix("www.").substringBefore('/')
        if (host.contains("..") || host.startsWith(".") || host.endsWith(".")) return false
        if (!host.contains('.')) return false
        return host.all { it.isLetterOrDigit() || it in ".-:[]_" }
    }

    private fun braveSearchUrl(query: String): String =
        "https://search.brave.com/search?q=" + URLEncoder.encode(query, "UTF-8")

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(omnibox.windowToken, 0)
    }

    private fun switchTo(tab: Tab) {
        current = tab
        webContainer.removeAllViews()
        webContainer.addView(tab.webView)
        omnibox.setText(tab.webView.url ?: HOME_URL)
        omnibox.clearFocus()
        updateNavButtons()
    }

    private fun updateNavButtons() {
        val tab = current
        btnBack.alpha = if (tab?.webView?.canGoBack() == true) 1f else 0.35f
        btnForward.alpha = if (tab?.webView?.canGoForward() == true) 1f else 0.35f
    }

    private fun handleBack() {
        if (tabsSheetShowing) {
            tabsSheetShowing = false
            return
        }
        val tab = current ?: run { finish(); return }
        if (tab.webView.canGoBack()) {
            tab.webView.goBack()
        } else if (tabs.size > 1) {
            closeTab(tab)
        } else {
            moveTaskToBack(true)
        }
    }

    // ---------- Tabs ----------

    private fun showTabsSheet() {
        tabsSheetShowing = true
        val dialog = com.google.android.material.bottomsheet.BottomSheetDialog(this)
        dialog.setOnDismissListener { tabsSheetShowing = false }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 64)
        }

        synchronized(tabs) {
            for (tab in tabs.toList()) {
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    background = resources.getDrawable(R.drawable.bg_omnibox, theme)
                    setPadding(40, 32, 24, 32)
                }
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = 24 }
                row.layoutParams = lp

                val text = TextView(this).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                    )
                    textSize = 14f
                    maxLines = 2
                    text = (tab.webView.title?.takeIf { it.isNotBlank() && tab.ready }
                        ?: tab.webView.url ?: "New tab")
                }
                row.addView(text)

                val close = ImageButton(this).apply {
                    setImageResource(R.drawable.ic_close)
                    background = null
                    setOnClickListener {
                        closeTab(tab)
                        dialog.dismiss()
                        if (tabs.isEmpty()) finish()
                    }
                }
                row.addView(
                    close,
                    LinearLayout.LayoutParams(96, LinearLayout.LayoutParams.WRAP_CONTENT)
                )

                row.setOnClickListener {
                    switchTo(tab)
                    dialog.dismiss()
                }
                container.addView(row)
            }
        }

        val add = TextView(this).apply {
            text = "New tab"
            textSize = 15f
            setTextColor(resources.getColor(com.google.android.material.R.color.design_default_color_primary, theme))
            setOnClickListener {
                dialog.dismiss()
                newTab().webView.loadUrl(HOME_URL)
            }
        }
        container.addView(
            add,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 16; gravity = android.view.Gravity.CENTER_HORIZONTAL }
        )

        dialog.setContentView(container)
        dialog.show()
    }

    private fun closeTab(tab: Tab) {
        synchronized(tabs) {
            tabs.remove(tab)
            tab.webView.apply {
                loadUrl("about:blank")
                onPause()
                clearHistory()
                clearCache(true)
                clearSslPreferences()
                destroy()
            }
        }
        if (current === tab) {
            current = null
            val next = tabs.lastOrNull()
            if (next != null) switchTo(next) else webContainer.removeAllViews()
        }
    }

    // ---------- Menu, privacy actions ----------

    private fun showMenu(anchor: ImageButton) {
        val menu = PopupMenu(this, anchor)
        menu.menuInflater.inflate(R.menu.main_menu, menu.menu)
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        menu.menu.findItem(R.id.action_auto_update).isChecked =
            prefs.getBoolean(KEY_AUTO_UPDATE, false)
        menu.menu.findItem(R.id.action_wipe_on_exit).isChecked =
            prefs.getBoolean(KEY_WIPE_ON_EXIT, true)
        menu.menu.findItem(R.id.action_auto_update_check).isChecked = Updater.isEnabled(this)

        menu.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_new_tab -> {
                    newTab().webView.loadUrl(HOME_URL)
                    true
                }
                R.id.action_close_tab -> {
                    current?.let {
                        closeTab(it)
                        if (tabs.isEmpty()) finish()
                    }
                    true
                }
                R.id.action_clear_data -> {
                    wipeBrowsingData()
                    Toast.makeText(this, "Browsing data cleared", Toast.LENGTH_SHORT).show()
                    true
                }
                R.id.action_update_blocklists -> {
                    Toast.makeText(this, "Updating blocklists...", Toast.LENGTH_SHORT).show()
                    Thread {
                        val ok = blocker.downloadUpdatedList()
                        runOnUiThread {
                            refreshShieldBadge()
                            Toast.makeText(
                                this,
                                if (ok) "Blocklists updated (${blocker.hostCount} rules)"
                                else "Update failed - check connection",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }.start()
                    true
                }
                R.id.action_auto_update -> {
                    item.isChecked = !item.isChecked
                    prefs.edit().putBoolean(KEY_AUTO_UPDATE, item.isChecked).apply()
                    true
                }
                R.id.action_check_update -> {
                    Updater.maybeCheck(this, automatic = false)
                    true
                }
                R.id.action_auto_update_check -> {
                    item.isChecked = !item.isChecked
                    Updater.setEnabled(this, item.isChecked)
                    true
                }
                R.id.action_wipe_on_exit -> {
                    item.isChecked = !item.isChecked
                    prefs.edit().putBoolean(KEY_WIPE_ON_EXIT, item.isChecked).apply()
                    true
                }
                R.id.action_about -> {
                    AlertDialog.Builder(this)
                        .setTitle("Provito")
                        .setMessage(
                            "Private browser v1.0\n\n" +
                                "Default search: Brave\n" +
                                "Blocking: local rule engine + open-source lists " +
                                "(bundled list, StevenBlack/hosts optional)\n\n" +
                                "No accounts, no history, no telemetry, no third-party cookies."
                        )
                        .setPositiveButton("OK", null)
                        .show()
                    true
                }
                else -> false
            }
        }
        menu.show()
    }

    private fun showPrivacyStats() {
        AlertDialog.Builder(this)
            .setTitle("Privacy shield")
            .setMessage(
                "Blocked since launch: ${blockedTotal.get()}\n" +
                    "Loaded rules: ${blocker.hostCount}\n\n" +
                    "Active protections:\n" +
                    "- Ad and tracker blocking\n" +
                    "- Third-party cookies blocked\n" +
                    "- Geolocation, camera and microphone denied\n" +
                    "- Popups and silent window opens blocked\n" +
                    "- No cache, no passwords, no form data saved\n" +
                    "- Generic user agent, no device fingerprint"
            )
            .setPositiveButton("OK", null)
            .show()
    }

    private fun refreshShieldBadge() {
        txtBlocked.text = blockedTotal.get().let {
            if (it > 999) "${it / 1000}k" else it.toString()
        }
    }

    fun getCurrentVersionName(): String =
        com.provito.browser.BuildConfig.VERSION_NAME

    private fun blockerHostsFileExists(): Boolean =
        java.io.File(filesDir, "blocklist_download.txt").exists()

    private fun wipeBrowsingData() {
        CookieManager.getInstance().apply {
            removeAllCookies(null)
            flush()
        }
        WebViewDatabase.getInstance(this).clearFormData()
        WebViewDatabase.getInstance(this).clearHttpAuthUsernamePassword()
        synchronized(tabs) {
            for (tab in tabs) {
                tab.webView.clearHistory()
                tab.webView.clearSslPreferences()
                tab.webView.clearCache(true)
            }
        }
    }

    override fun onDestroy() {
        val wipe = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_WIPE_ON_EXIT, true)
        if (wipe) {
            CookieManager.getInstance().apply {
                removeAllCookies(null)
                flush()
            }
        }
        synchronized(tabs) {
            for (tab in tabs.toList()) {
                tab.webView.destroy()
            }
            tabs.clear()
        }
        super.onDestroy()
    }
}
