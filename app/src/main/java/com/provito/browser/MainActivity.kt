package com.provito.browser

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.JavascriptInterface
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
        const val TABS_PAGE = "file:///android_asset/tabs.html"
        const val SETTINGS_PAGE = "file:///android_asset/settings.html"
        const val UNSAFE_PAGE = "file:///android_asset/unsafe.html"
        const val PREFS = "provito_prefs"

        const val KEY_AUTO_UPDATE = "auto_update_blocklists"   // blocklist downloads
        const val KEY_WIPE_ON_EXIT = "wipe_on_exit"
        const val KEY_SEARCH_ENGINE = "search_engine"          // brave | duckduckgo | mojeek
        const val KEY_BLOCK_ADS = "block_ads"
        const val KEY_UNLOAD_TABS = "unload_tabs"
        const val KEY_AUTO_UI_RESIZE = "auto_ui_resize"

        // RAM budget for tabs: ~1 GB. Tabs beyond the budget are unloaded
        // (their WebView is destroyed; url + title are kept so they can be
        // reopened instantly). Estimated at ~30 MB per loaded tab.
        const val RAM_BUDGET_MB = 1024
        const val MB_PER_TAB = 30

        // Generic user agent: no device model, no app fingerprints.
        const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36 Provito/1.0"
    }

    private lateinit var blocker: Blocker
    private lateinit var unsafeSites: UnsafeSites

    private lateinit var webContainer: FrameLayout
    private lateinit var omnibox: EditText
    private lateinit var progress: LinearProgressIndicator
    private lateinit var txtBlocked: TextView
    private lateinit var btnBack: ImageButton
    private lateinit var btnForward: ImageButton

    private val prefs by lazy { getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    private val mainHandler = Handler(Looper.getMainLooper())

    /** A browser tab. When [webView] is null the tab is unloaded and only
     *  its url/title (a few bytes) stay in RAM so it can be reopened. */
    inner class Tab(var url: String) {
        var webView: WebView? = null
        var title: String = "New tab"
        var lastUsed: Long = System.currentTimeMillis()
        val isActive: Boolean get() = webView != null
        val isOverlay: Boolean get() = url == TABS_PAGE || url == SETTINGS_PAGE
    }

    private val tabs = ArrayList<Tab>()
    private var current: Tab? = null
    private val blockedTotal = java.util.concurrent.atomic.AtomicInteger(0)

    // ---------- Responsive UI ----------
    // The shell measures the window in dp and scales bars, text and touch
    // targets so the UI stays proportional from phones up to tablets.
    private var uiScale = 1f
    private var insetTop = 0
    private var insetBottom = 0
    private var insetLeft = 0
    private var insetRight = 0

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)

        blocker = Blocker(this)
        blocker.enabled = prefs.getBoolean(KEY_BLOCK_ADS, true)
        // Parse blocklists off the main thread so the shell paints immediately.
        blocker.loadAsync()
        unsafeSites = UnsafeSites(this)
        unsafeSites.loadAsync()

        setContentView(R.layout.activity_main)
        webContainer = findViewById(R.id.webContainer)
        omnibox = findViewById(R.id.omnibox)
        progress = findViewById(R.id.progress)
        txtBlocked = findViewById(R.id.txtBlocked)
        btnBack = findViewById(R.id.btnBack)
        btnForward = findViewById(R.id.btnForward)

        // Warm up the cookie store off the main thread (it is a costly
        // synchronous init that otherwise lands on startup).
        Thread { CookieManager.getInstance().setAcceptCookie(true) }.start()

        if (prefs.getBoolean(KEY_AUTO_UPDATE, false)) {
            if (!blockerHostsFileExists()) {
                Thread { if (blocker.downloadUpdatedList()) runOnUiThread { refreshShieldBadge() } }.start()
            }
            if (!unsafeDownloadExists()) {
                Thread { unsafeSites.downloadAllSources() }.start()
            }
        }

        wireChrome()
        wireOmnibox()
        setupResponsiveUi()
        addSplash()

        onBackPressedDispatcher.addCallback(
            this,
            object : androidx.activity.OnBackPressedCallback(true) {
                override fun handleOnBackPressed() = handleBack()
            }
        )

        // Defer WebView creation until after the first frame - the native
        // shell (omnibox + toolbar) is visible instantly. Proxy rules must
        // be set before the first WebView exists.
        mainHandler.post {
            ProxyManager.applyToWebView(this) {
                runOnUiThread {
                    val incoming = intent?.dataString
                    if (incoming != null) {
                        newTab(incoming)
                    } else {
                        newTab(HOME_URL)
                    }
                    Updater.maybeCheck(this, automatic = true)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.dataString?.let { url -> mainHandler.post { newTab(url) } }
    }

    fun getCurrentVersionName(): String = BuildConfig.VERSION_NAME

    /** Startup animation overlay: shield, drawn check mark, logo slides
     *  beside "Provito", fades out over the loaded app. Pure canvas view,
     *  no startup cost, skipped if system animations are disabled. */
    private fun addSplash() {
        val scale = android.provider.Settings.Global.getFloat(
            contentResolver,
            android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f
        )
        if (scale == 0f) return
        val splash = SplashView(this)
        (window.decorView as android.view.ViewGroup).addView(
            splash,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        splash.post { splash.start() }
    }

    // ---------- Responsive UI engine ----------

    /** Measures the window in dp and picks a scale tier for the shell. */
    private fun applyResponsiveUi() {
        val dm = resources.displayMetrics
        val wm = getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
        val bounds = if (android.os.Build.VERSION.SDK_INT >= 30) {
            wm.currentWindowMetrics.bounds
        } else {
            @Suppress("DEPRECATION")
            android.graphics.Rect(0, 0, wm.defaultDisplay.width, wm.defaultDisplay.height)
        }
        val widthDp = bounds.width() / dm.density
        val heightDp = bounds.height() / dm.density
        val smallest = minOf(widthDp, heightDp)

        val auto = prefs.getBoolean(KEY_AUTO_UI_RESIZE, true)
        uiScale = when {
            !auto -> 1f
            smallest >= 840f -> 1.5f   // large tablets / desktop-ish
            smallest >= 720f -> 1.35f  // tablets
            smallest >= 600f -> 1.2f   // small tablets / large foldables
            else -> 1f                 // phones
        }
        layoutShell()
    }

    /** Applies scale + system insets to the native shell. */
    private fun layoutShell() {
        val d = resources.displayMetrics.density
        fun px(dp: Float) = (dp * uiScale * d).toInt()

        val topBar = findViewById<LinearLayout>(R.id.topBar)
        val bottomBar = findViewById<LinearLayout>(R.id.bottomBar)
        val btnShield = findViewById<ImageButton>(R.id.btnShield)
        val btnGo = findViewById<ImageButton>(R.id.btnGo)

        topBar.layoutParams.height = px(56f) + insetTop
        topBar.setPadding(px(8f) + insetLeft, insetTop, px(8f) + insetRight, 0)

        btnShield.layoutParams.width = px(36f)
        btnShield.layoutParams.height = px(36f)
        btnGo.layoutParams.width = px(36f)
        btnGo.layoutParams.height = px(36f)

        omnibox.layoutParams.height = px(42f)
        omnibox.textSize = 14f * uiScale
        txtBlocked.textSize = 11f * uiScale
        txtBlocked.minWidth = px(34f)

        bottomBar.layoutParams.height = px(52f) + insetBottom
        bottomBar.setPadding(insetLeft, 0, insetRight, insetBottom)

        topBar.requestLayout()
        bottomBar.requestLayout()
    }

    /** Keeps bars clear of status/nav bars, cutouts and the keyboard. */
    private fun setupResponsiveUi() {
        val types = androidx.core.view.WindowInsetsCompat.Type.systemBars() or
            androidx.core.view.WindowInsetsCompat.Type.displayCutout()
        val topBar = findViewById<LinearLayout>(R.id.topBar)
        val bottomBar = findViewById<LinearLayout>(R.id.bottomBar)

        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(topBar) { _, insets ->
            val i = insets.getInsets(types)
            insetTop = i.top
            insetLeft = i.left
            insetRight = i.right
            layoutShell()
            insets
        }
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(bottomBar) { _, insets ->
            val i = insets.getInsets(
                types or androidx.core.view.WindowInsetsCompat.Type.ime()
            )
            insetBottom = i.bottom
            layoutShell()
            insets
        }
        applyResponsiveUi()
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        // Fold/unfold, rotation, window resize (configChanges is declared in
        // the manifest so the activity survives and just relayouts).
        applyResponsiveUi()
    }

    // ---------- Tabs and RAM management ----------

    private fun newTab(url: String = HOME_URL): Tab {
        val tab = Tab(url)
        loadTarget(tab, url)
        synchronized(tabs) { tabs.add(tab) }
        tab.lastUsed = System.currentTimeMillis()
        ensureLoaded(tab)
        enforceTabBudget()
        switchTo(tab)
        return tab
    }

    // ---------- Unsafe-site shield ----------

    private fun warningUrl(target: String, level: String): String =
        UNSAFE_PAGE + "?level=" + level + "&u=" + URLEncoder.encode(target, "UTF-8")

    /** Returns the URL to actually load: target, or the warning page. */
    private fun loadTarget(tab: Tab, url: String) {
        tab.url = unsafeSites.level(url)?.let { warningUrl(url, it) } ?: url
    }

    private fun loadInTab(tab: Tab, url: String) {
        // Chrome pages opened via the omnibox must be proper overlay tabs
        // so their JS bridges get attached.
        if (url == TABS_PAGE) {
            openTabsPage()
            return
        }
        if (url == SETTINGS_PAGE) {
            openSettingsPage()
            return
        }
        val final = unsafeSites.level(url)?.let { warningUrl(url, it) } ?: url
        omnibox.setText(final)
        tab.webView?.loadUrl(final)
    }

    private fun tabFor(view: WebView): Tab? =
        synchronized(tabs) { tabs.firstOrNull { it.webView === view } }

    private fun unsafeDownloadExists(): Boolean =
        java.io.File(filesDir, UnsafeSites.DOWNLOAD_FILE).exists()

    /** Creates (or recreates) the WebView backing a tab. */
    @SuppressLint("SetJavaScriptEnabled")
    private fun ensureLoaded(tab: Tab) {
        if (tab.webView != null) return
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
        // Cached loads are dramatically faster on revisit; the cache is
        // wiped on exit (see wipeBrowsingData) so nothing lingers.
        s.cacheMode = WebSettings.LOAD_DEFAULT
        s.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        s.saveFormData = false
        @Suppress("DEPRECATION")
        s.savePassword = false
        s.userAgentString = USER_AGENT

        // No third-party cookies, ever.
        CookieManager.getInstance().setAcceptThirdPartyCookies(wv, false)

        wv.setDownloadListener { url, _, _, _, _ ->
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

        // JS bridges only on Provito's own chrome pages - never exposed to
        // websites. Both HTML pages call the interface as "Android".
        if (tab.url == TABS_PAGE) {
            wv.addJavascriptInterface(TabsBridge(), "Android")
        }
        if (tab.url == SETTINGS_PAGE) {
            wv.addJavascriptInterface(SettingsBridge(), "Android")
        }

        wv.loadUrl(tab.url)
        tab.webView = wv
    }

    /** Estimated total tab RAM: ~30 MB per loaded tab, budget ~1 GB. */
    private fun activeTabCount(): Int = synchronized(tabs) { tabs.count { it.isActive } }

    private fun enforceTabBudget() {
        if (!prefs.getBoolean(KEY_UNLOAD_TABS, true)) return
        val budgetTabs = RAM_BUDGET_MB / MB_PER_TAB
        while (activeTabCount() > budgetTabs) {
            val victim = synchronized(tabs) {
                tabs
                    .filter { it.isActive && it !== current && !it.isOverlay }
                    .minByOrNull { it.lastUsed }
            } ?: break
            unloadTab(victim)
        }
    }

    private fun unloadTab(tab: Tab) {
        val wv = tab.webView ?: return
        tab.url = wv.url ?: tab.url
        tab.title = wv.title?.takeIf { it.isNotBlank() } ?: tab.title
        tab.webView = null
        synchronized(tabs) { if (!tabs.contains(tab)) tabs.add(tab) }
        try {
            wv.stopLoading()
            wv.onPause()
            if (wv.parent != null) webContainer.removeView(wv)
            wv.removeAllViews()
            wv.destroy()
        } catch (_: Exception) {
            // already torn down
        }
    }

    /** Destroys and recreates every loaded tab so proxy changes take effect. */
    private fun recreateAllTabs() {
        val snapshot = synchronized(tabs) { tabs.toList() }
        for (t in snapshot) {
            val wv = t.webView ?: continue
            t.url = wv.url ?: t.url
            t.title = wv.title?.takeIf { it.isNotBlank() } ?: t.title
        }
        webContainer.removeAllViews()
        for (t in snapshot) unloadTab(t)
        val cur = current ?: snapshot.firstOrNull() ?: return
        ensureLoaded(cur)
        switchTo(cur)
    }

    private fun switchTo(tab: Tab) {
        current = tab
        ensureLoaded(tab)
        tab.lastUsed = System.currentTimeMillis()
        val wv = tab.webView
        if (wv != null) {
            wv.onResume()
            webContainer.removeAllViews()
            webContainer.addView(wv)
        }
        omnibox.setText(tab.url)
        omnibox.clearFocus()
        enforceTabBudget()
        updateNavButtons()
    }

    private fun closeTab(tab: Tab) {
        synchronized(tabs) { tabs.remove(tab) }
        tab.webView?.let { wv ->
            try {
                wv.stopLoading()
                wv.onPause()
                wv.clearHistory()
                wv.clearCache(true)
                wv.clearSslPreferences()
                wv.removeAllViews()
                wv.destroy()
            } catch (_: Exception) {
                // already torn down
            }
        }
        tab.webView = null
        if (current === tab) {
            current = null
            val next = synchronized(tabs) { tabs.lastOrNull() }
            if (next != null) switchTo(next) else {
                webContainer.removeAllViews()
                newTab(HOME_URL)
            }
        }
    }

    private fun updateNavButtons() {
        val tab = current
        btnBack.alpha = if (tab?.webView?.canGoBack() == true) 1f else 0.35f
        btnForward.alpha = if (tab?.webView?.canGoForward() == true) 1f else 0.35f
    }

    private fun handleBack() {
        val tab = current ?: run { finish(); return }
        val wv = tab.webView
        if (wv != null && wv.canGoBack()) {
            wv.goBack()
        } else if (tab.isOverlay) {
            closeTab(tab)
        } else if (tabs.size > 1) {
            closeTab(tab)
        } else {
            moveTaskToBack(true)
        }
    }

    // ---------- WebView clients, hardened for privacy ----------

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

            if (scheme == "http" || scheme == "https") {
                // Unsafe-site shield: check every main-frame navigation.
                if (request.isForMainFrame) {
                    val level = unsafeSites.level(url.toString())
                    if (level != null) {
                        view.loadUrl(warningUrl(url.toString(), level))
                        return true
                    }
                }
                // Provito chrome pages never navigate in place: links open
                // in a fresh (bridge-free) tab instead.
                if ((view.url ?: "").startsWith("file:///android_asset/")) {
                    runOnUiThread { newTab(url.toString()) }
                    return true
                }
                return false
            }

            // Provito internal actions.
            if (scheme == "provito") {
                when (url.host) {
                    "unsafe-continue" -> {
                        val target = try {
                            url.getQueryParameter("u")
                        } catch (_: Exception) {
                            null
                        }
                        if (target != null) {
                            unsafeSites.allowSession(target)
                            runOnUiThread {
                                val warningTab = tabFor(view)
                                newTab(target)
                                warningTab?.let { closeTab(it) }
                            }
                        }
                    }
                    "home" -> {
                        runOnUiThread {
                            val warningTab = tabFor(view)
                            newTab(HOME_URL)
                            warningTab?.let { closeTab(it) }
                        }
                    }
                }
                return true
            }

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
            // Backstop: catches JS redirects and any navigation that slipped
            // past the client checks.
            val level = unsafeSites.level(url)
            if (level != null) {
                view.stopLoading()
                view.loadUrl(warningUrl(url, level))
                return
            }
            if (view == current?.webView) omnibox.setText(url)
            progress.show()
            progress.setIndeterminate(true)
            updateNavButtons()
        }

        override fun onPageFinished(view: WebView, url: String) {
            super.onPageFinished(view, url)
            current?.title = view.title?.takeIf { it.isNotBlank() } ?: current?.title ?: "New tab"
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
                        newTab(request.url.toString())
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
        findViewById<ImageButton>(R.id.btnTabs).setOnClickListener { openTabsPage() }
        findViewById<ImageButton>(R.id.btnMenu).setOnClickListener { showMenu(it as ImageButton) }
    }

    private fun openTabsPage() {
        // If we are already on the tabs page just refresh it.
        if (current?.url == TABS_PAGE) {
            current?.webView?.reload()
            return
        }
        newTab(TABS_PAGE)
    }

    private fun openSettingsPage() {
        if (current?.url == SETTINGS_PAGE) {
            current?.webView?.reload()
            return
        }
        newTab(SETTINGS_PAGE)
    }

    private fun wireOmnibox() {
        omnibox.setOnFocusChangeListener { _, focused ->
            if (!focused) current?.url?.let { omnibox.setText(it) }
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

    private fun searchEngineUrl(query: String): String = when (
        prefs.getString(KEY_SEARCH_ENGINE, "brave")
    ) {
        "duckduckgo" -> "https://duckduckgo.com/?q=" + URLEncoder.encode(query, "UTF-8")
        "mojeek" -> "https://www.mojeek.com/search?q=" + URLEncoder.encode(query, "UTF-8")
        else -> "https://search.brave.com/search?q=" + URLEncoder.encode(query, "UTF-8")
    }

    private fun navigate(input: String) {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return
        val url = when {
            trimmed.startsWith("http://") || trimmed.startsWith("https://") -> trimmed
            trimmed.startsWith("about:") || trimmed.startsWith("file://") -> trimmed
            trimmed == "provito://settings" -> SETTINGS_PAGE
            trimmed == "provito://tabs" -> TABS_PAGE
            trimmed.contains(' ') -> searchEngineUrl(trimmed)
            looksLikeUrl(trimmed) -> "https://$trimmed"
            else -> searchEngineUrl(trimmed)
        }
        hideKeyboard()
        omnibox.clearFocus()
        val tab = current ?: newTab(HOME_URL)
        loadInTab(tab, url)
    }

    private fun looksLikeUrl(s: String): Boolean {
        val host = s.removePrefix("www.").substringBefore('/')
        if (host.contains("..") || host.startsWith(".") || host.endsWith(".")) return false
        if (!host.contains('.')) return false
        return host.all { it.isLetterOrDigit() || it in ".-:[]_" }
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(omnibox.windowToken, 0)
    }

    // ---------- Menu, privacy actions ----------

    private fun showMenu(anchor: ImageButton) {
        val menu = PopupMenu(this, anchor)
        menu.menuInflater.inflate(R.menu.main_menu, menu.menu)
        menu.menu.findItem(R.id.action_auto_update).isChecked =
            prefs.getBoolean(KEY_AUTO_UPDATE, false)
        menu.menu.findItem(R.id.action_wipe_on_exit).isChecked =
            prefs.getBoolean(KEY_WIPE_ON_EXIT, true)
        menu.menu.findItem(R.id.action_auto_update_check).isChecked = Updater.isEnabled(this)

        menu.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_new_tab -> {
                    newTab(HOME_URL)
                    true
                }
                R.id.action_close_tab -> {
                    current?.let {
                        closeTab(it)
                    }
                    true
                }
                R.id.action_settings -> {
                    openSettingsPage()
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
                            "Private browser v${BuildConfig.VERSION_NAME}\n\n" +
                                "Default search: Brave\n" +
                                "Blocking: local rule engine + open-source lists\n\n" +
                                "No accounts, no history, no telemetry, no third-party cookies.\n" +
                                "Honestly vibecoded - see the README."
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
                    "- No passwords, no form data saved\n" +
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
                tab.webView?.clearHistory()
                tab.webView?.clearSslPreferences()
                tab.webView?.clearCache(true)
            }
        }
    }

    // ---------- HTML chrome-page bridges ----------
    // These interfaces are attached ONLY to Provito's own asset pages
    // (tabs.html / settings.html), never to websites.

    inner class SettingsBridge {
        @JavascriptInterface
        fun get(key: String): String = when (key) {
            KEY_SEARCH_ENGINE -> prefs.getString(KEY_SEARCH_ENGINE, "brave") ?: "brave"
            KEY_BLOCK_ADS -> prefs.getBoolean(KEY_BLOCK_ADS, true).toString()
            KEY_UNLOAD_TABS -> prefs.getBoolean(KEY_UNLOAD_TABS, true).toString()
            KEY_AUTO_UI_RESIZE -> prefs.getBoolean(KEY_AUTO_UI_RESIZE, true).toString()
            KEY_WIPE_ON_EXIT -> prefs.getBoolean(KEY_WIPE_ON_EXIT, true).toString()
            KEY_AUTO_UPDATE -> prefs.getBoolean(KEY_AUTO_UPDATE, false).toString()
            ProxyManager.KEY_ONION_MODE -> prefs.getBoolean(ProxyManager.KEY_ONION_MODE, false).toString()
            Updater.PREF_UPDATE_CHECK -> prefs.getBoolean(Updater.PREF_UPDATE_CHECK, true).toString()
            else -> ""
        }

        @JavascriptInterface
        fun set(key: String, value: String) {
            val e = prefs.edit()
            when (key) {
                KEY_SEARCH_ENGINE -> e.putString(KEY_SEARCH_ENGINE, value)
                KEY_BLOCK_ADS -> {
                    val b = value.toBooleanStrictOrNull() ?: return
                    e.putBoolean(KEY_BLOCK_ADS, b)
                    blocker.enabled = b
                }
                KEY_UNLOAD_TABS -> {
                    val b = value.toBooleanStrictOrNull() ?: return
                    e.putBoolean(KEY_UNLOAD_TABS, b)
                    if (b) runOnUiThread { enforceTabBudget() }
                }
                KEY_AUTO_UI_RESIZE -> {
                    val b = value.toBooleanStrictOrNull() ?: return
                    e.putBoolean(KEY_AUTO_UI_RESIZE, b)
                    runOnUiThread { applyResponsiveUi() }
                }
                KEY_WIPE_ON_EXIT -> {
                    val b = value.toBooleanStrictOrNull() ?: return
                    e.putBoolean(KEY_WIPE_ON_EXIT, b)
                }
                KEY_AUTO_UPDATE -> {
                    val b = value.toBooleanStrictOrNull() ?: return
                    e.putBoolean(KEY_AUTO_UPDATE, b)
                }
                ProxyManager.KEY_ONION_MODE -> {
                    val b = value.toBooleanStrictOrNull() ?: return
                    e.putBoolean(ProxyManager.KEY_ONION_MODE, b)
                    e.apply()
                    ProxyManager.applyToWebView(this@MainActivity) { ok ->
                        runOnUiThread {
                            if (!ok) {
                                toast("Proxying not supported on this WebView version")
                            } else if (b && !ProxyManager.isOrbotProxyUp()) {
                                toast("Orbot is not running - start it, then reload tabs")
                            } else {
                                recreateAllTabs()
                                toast(
                                    if (b) "Traffic now routed through Tor"
                                    else "Proxy routing off"
                                )
                            }
                        }
                    }
                    return
                }
                Updater.PREF_UPDATE_CHECK -> {
                    val b = value.toBooleanStrictOrNull() ?: return
                    e.putBoolean(Updater.PREF_UPDATE_CHECK, b)
                }
                else -> return
            }
            e.apply()
        }

        @JavascriptInterface
        fun version(): String = BuildConfig.VERSION_NAME

        @JavascriptInterface
        fun blockedCount(): Int = blockedTotal.get()

        @JavascriptInterface
        fun ruleCount(): Int = blocker.hostCount

        @JavascriptInterface
        fun toast(message: String) {
            runOnUiThread {
                Toast.makeText(this@MainActivity, message, Toast.LENGTH_SHORT).show()
            }
        }

        @JavascriptInterface
        fun checkUpdatesNow() {
            runOnUiThread { Updater.maybeCheck(this@MainActivity, automatic = false) }
        }

        @JavascriptInterface
        fun updateBlocklists() {
            runOnUiThread {
                Toast.makeText(this@MainActivity, "Updating blocklists...", Toast.LENGTH_SHORT).show()
            }
            Thread {
                val ok = blocker.downloadUpdatedList()
                runOnUiThread {
                    refreshShieldBadge()
                    toast(
                        if (ok) "Blocklists updated (${blocker.hostCount} rules)"
                        else "Update failed - check connection"
                    )
                }
            }.start()
        }

        @JavascriptInterface
        fun clearBrowsingData() {
            runOnUiThread {
                wipeBrowsingData()
                toast("Browsing data cleared")
            }
        }

        @JavascriptInterface
        fun closePage() {
            runOnUiThread {
                current?.let { closeTab(it) }
            }
        }

        @JavascriptInterface
        fun updateUnsafeLists() {
            runOnUiThread {
                Toast.makeText(this@MainActivity, "Updating unsafe-site lists...", Toast.LENGTH_SHORT).show()
            }
            Thread {
                val okCount = unsafeSites.downloadAllSources()
                runOnUiThread {
                    toast(
                        if (okCount > 0) {
                            "Updated $okCount/${UnsafeSites.SOURCES.size} lists " +
                                "(malware: ${unsafeSites.malwareCount}, phishing: ${unsafeSites.phishingCount})"
                        } else {
                            "Update failed - check connection"
                        }
                    )
                }
            }.start()
        }

        @JavascriptInterface
        fun orbotInstalled(): Boolean = ProxyManager.isOrbotInstalled(this@MainActivity)

        @JavascriptInterface
        fun orbotRunning(): Boolean = ProxyManager.isOrbotProxyUp()

        @JavascriptInterface
        fun openOrbot() {
            runOnUiThread { ProxyManager.openOrbot(this@MainActivity) }
        }
    }

    inner class TabsBridge {
        private fun esc(s: String): String =
            s.replace("\\", "\\\\").replace("\"", "\\\"")

        @JavascriptInterface
        fun getTabs(): String = synchronized(tabs) {
            val sb = StringBuilder("[")
            for ((i, tab) in tabs.withIndex()) {
                val wv = tab.webView
                val title = wv?.title?.takeIf { it.isNotBlank() }
                    ?: tab.title.ifBlank { "New tab" }
                val url = wv?.url ?: tab.url
                sb.append("{\"id\":").append(i)
                    .append(",\"title\":\"").append(esc(title))
                    .append("\",\"url\":\"").append(esc(url))
                    .append("\",\"unloaded\":").append(wv == null)
                    .append(",\"current\":").append(tab === current)
                    .append('}')
                if (i != tabs.size - 1) sb.append(',')
            }
            sb.append(']').toString()
        }

        @JavascriptInterface
        fun select(id: Int) {
            runOnUiThread {
                val target = synchronized(tabs) { tabs.getOrNull(id) } ?: return@runOnUiThread
                val overlay = current
                if (overlay != null && overlay.isOverlay) closeTab(overlay)
                switchTo(target)
            }
        }

        @JavascriptInterface
        fun close(id: Int) {
            runOnUiThread {
                synchronized(tabs) { tabs.getOrNull(id) }?.let { closeTab(it) }
            }
        }

        @JavascriptInterface
        fun newTabFromUi() {
            runOnUiThread {
                current?.takeIf { it.isOverlay }?.let { closeTab(it) }
                newTab(HOME_URL)
            }
        }
    }

    override fun onDestroy() {
        val wipe = prefs.getBoolean(KEY_WIPE_ON_EXIT, true)
        if (wipe) {
            CookieManager.getInstance().apply {
                removeAllCookies(null)
                flush()
            }
        }
        synchronized(tabs) {
            for (tab in tabs.toList()) {
                tab.webView?.destroy()
                tab.webView = null
            }
            tabs.clear()
        }
        super.onDestroy()
    }
}
