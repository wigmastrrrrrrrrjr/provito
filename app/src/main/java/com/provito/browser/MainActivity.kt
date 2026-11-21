package com.provito.browser

import android.app.AlertDialog
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import com.google.android.material.progressindicator.LinearProgressIndicator
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.ContentBlocking
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSession.PromptDelegate.AlertPrompt
import org.mozilla.geckoview.GeckoSession.PromptDelegate.AuthPrompt
import org.mozilla.geckoview.GeckoSession.PromptDelegate.BeforeUnloadPrompt
import org.mozilla.geckoview.GeckoSession.PromptDelegate.ButtonPrompt
import org.mozilla.geckoview.GeckoSession.PromptDelegate.ChoicePrompt
import org.mozilla.geckoview.GeckoSession.PromptDelegate.FilePrompt
import org.mozilla.geckoview.GeckoSession.PromptDelegate.PopupPrompt
import org.mozilla.geckoview.GeckoSession.PromptDelegate.PromptResponse
import org.mozilla.geckoview.GeckoSession.PromptDelegate.RepostConfirmPrompt
import org.mozilla.geckoview.GeckoSession.PromptDelegate.TextPrompt
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.StorageController
import org.mozilla.geckoview.WebRequestError
import org.mozilla.geckoview.WebResponse
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicInteger

/**
 * Provito's browser shell.
 *
 * Rendering is done by GeckoView (Mozilla's Firefox engine), not by the
 * system WebView. Every tab owns a private GeckoSession, so cookies, cache
 * and storage are never shared with other apps and are dropped on exit.
 *
 * Provito's own pages (home / tabs / settings / warning) talk to the app
 * through provito:// URLs rather than a JavaScript bridge, so no JavaScript
 * interface is ever exposed to web content.
 */
class MainActivity : AppCompatActivity() {

    companion object {
        const val HOME_URL = "resource://android/assets/home.html"
        const val TABS_PAGE = "resource://android/assets/tabs.html"
        const val SETTINGS_PAGE = "resource://android/assets/settings.html"
        const val UNSAFE_PAGE = "resource://android/assets/unsafe.html"
        const val ERROR_PAGE = "resource://android/assets/error.html"
        const val PREFS = "provito_prefs"

        const val KEY_WIPE_ON_EXIT = "wipe_on_exit"
        const val KEY_SEARCH_ENGINE = "search_engine"          // brave | duckduckgo | mojeek
        const val KEY_BLOCK_ADS = "block_ads"
        const val KEY_UNLOAD_TABS = "unload_tabs"
        const val KEY_AUTO_UI_RESIZE = "auto_ui_resize"

        // RAM budget for tabs: ~1 GB. Tabs beyond the budget are unloaded
        // (their GeckoSession is closed; url + title are kept so they can be
        // reopened instantly). Estimated at ~40 MB per loaded tab.
        const val RAM_BUDGET_MB = 1024
        const val MB_PER_TAB = 40
    }

    private lateinit var unsafeSites: UnsafeSites
    private lateinit var runtime: org.mozilla.geckoview.GeckoRuntime

    private lateinit var webContainer: FrameLayout
    private lateinit var geckoView: GeckoView
    private lateinit var omnibox: EditText
    private lateinit var progress: LinearProgressIndicator
    private lateinit var txtBlocked: TextView
    private lateinit var btnBack: ImageButton
    private lateinit var btnForward: ImageButton

    private val prefs by lazy { getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    private val mainHandler = Handler(Looper.getMainLooper())

    /** A browser tab. When [session] is null the tab is unloaded and only
     *  its url/title (a few bytes) stay in RAM so it can be reopened. */
    inner class Tab(var url: String) {
        var session: GeckoSession? = null
        var title: String = "New tab"
        var lastUsed: Long = System.currentTimeMillis()
        var canBack: Boolean = false
        var canForward: Boolean = false
        var errorPage: Boolean = false
        var failedUrl: String? = null
        val isActive: Boolean get() = session != null
        val isOverlay: Boolean
            get() = url.startsWith(TABS_PAGE) ||
                url.startsWith(SETTINGS_PAGE) ||
                url.startsWith(UNSAFE_PAGE)
    }

    private val tabs = ArrayList<Tab>()
    private var current: Tab? = null
    private val blockedTotal = AtomicInteger(0)

    private var pendingFilePrompt: FilePrompt? = null
    private var pendingFileResult: GeckoResult<PromptResponse>? = null

    private val filePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val prompt = pendingFilePrompt
        val result = pendingFileResult
        pendingFilePrompt = null
        pendingFileResult = null
        if (prompt == null || result == null) return@registerForActivityResult
        result.complete(if (uri != null) prompt.confirm(this, arrayOf(uri)) else prompt.dismiss())
    }

    // ---------- Responsive UI ----------
    private var uiScale = 1f
    private var insetTop = 0
    private var insetBottom = 0
    private var insetLeft = 0
    private var insetRight = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)

        runtime = Engine.runtime(this)
        unsafeSites = UnsafeSites(this)
        unsafeSites.loadAsync()

        setContentView(R.layout.activity_main)
        webContainer = findViewById(R.id.webContainer)
        omnibox = findViewById(R.id.omnibox)
        progress = findViewById(R.id.progress)
        txtBlocked = findViewById(R.id.txtBlocked)
        btnBack = findViewById(R.id.btnBack)
        btnForward = findViewById(R.id.btnForward)

        if (prefs.getBoolean("auto_update_blocklists", false) && !unsafeDownloadExists()) {
            Thread { unsafeSites.downloadAllSources() }.start()
        }

        wireChrome()
        wireOmnibox()
        setupResponsiveUi()
        val firstRun = !prefs.getBoolean(SetupView.KEY_SETUP_DONE, false)
        addSplash(if (firstRun) ({ showSetup() }) else null)

        onBackPressedDispatcher.addCallback(
            this,
            object : androidx.activity.OnBackPressedCallback(true) {
                override fun handleOnBackPressed() = handleBack()
            }
        )

        geckoView = GeckoView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            // TextureView composites like a normal view, so the splash,
            // setup wizard and dialogs render correctly above the page.
            setViewBackend(GeckoView.BACKEND_TEXTURE_VIEW)
            setBackgroundColor(Color.parseColor("#14141F"))
            coverUntilFirstPaint(Color.parseColor("#14141F"))
        }
        webContainer.addView(geckoView)

        mainHandler.post {
            ProxyManager.applyToGecko(this) { ok ->
                runOnUiThread {
                    if (!ok) toast("Proxy settings could not be applied")
                    val incoming = intent?.dataString
                    if (incoming != null) newTab(incoming) else newTab(HOME_URL)
                    if (!firstRun) Updater.maybeCheck(this, automatic = true)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.dataString?.let { url -> mainHandler.post { newTab(url) } }
    }

    fun getCurrentVersionName(): String = BuildConfig.VERSION_NAME

    private fun addSplash(onEnd: (() -> Unit)? = null) {
        val scale = android.provider.Settings.Global.getFloat(
            contentResolver,
            android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f
        )
        if (scale == 0f) {
            onEnd?.invoke()
            return
        }
        val splash = SplashView(this)
        splash.onFinished = onEnd
        splash.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        (window.decorView as android.view.ViewGroup).addView(
            splash,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        splash.post { splash.start() }
    }

    private fun showSetup() {
        val setup = SetupView(this)
        setup.onFinished = {
            Updater.maybeCheck(this, automatic = true)
        }
        (window.decorView as android.view.ViewGroup).addView(
            setup,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        setup.post { setup.start() }
    }

    // ---------- Responsive UI engine ----------

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
            smallest >= 840f -> 1.5f
            smallest >= 720f -> 1.35f
            smallest >= 600f -> 1.2f
            else -> 1f
        }
        layoutShell()
    }

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
        runCatching { runtime.configurationChanged(newConfig) }
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
        UNSAFE_PAGE + "#level=" + level + "&u=" + URLEncoder.encode(target, "UTF-8")

    private fun errorUrl(failed: String, error: WebRequestError): String {
        val (reason, hint) = errorCopy(error)
        return ERROR_PAGE +
            "#u=" + Uri.encode(failed) +
            "&t=" + Uri.encode(reason) +
            "&h=" + Uri.encode(hint) +
            "&c=" + error.code
    }

    /** Reads a percent-encoded key from the fragment of an internal page URL. */
    private fun hashParam(url: String, key: String): String? {
        val fragment = url.substringAfter('#', "")
        if (fragment.isEmpty()) return null
        return runCatching {
            Uri.parse("provito://x?$fragment").getQueryParameter(key)
        }.getOrNull()
    }

    private fun errorCopy(error: WebRequestError): Pair<String, String> = when (error.code) {
        WebRequestError.ERROR_UNKNOWN_HOST ->
            "Cannot resolve the network IP address" to
                "The server's address could not be looked up. Check the URL, DNS, or proxy settings."
        WebRequestError.ERROR_OFFLINE ->
            "You appear to be offline" to "Check your internet connection and try again."
        WebRequestError.ERROR_NET_TIMEOUT ->
            "The site took too long to respond" to "The server did not answer in time. Try again."
        WebRequestError.ERROR_CONNECTION_REFUSED, WebRequestError.ERROR_PROXY_CONNECTION_REFUSED ->
            "The connection was refused" to "The server or proxy refused the connection."
        WebRequestError.ERROR_UNKNOWN_PROXY_HOST ->
            "The proxy could not be found" to
                "Check the proxy settings and make sure Orbot or your proxy is running."
        WebRequestError.ERROR_NET_INTERRUPT, WebRequestError.ERROR_NET_RESET ->
            "The connection was interrupted" to "The network dropped the connection. Try again."
        WebRequestError.ERROR_SECURITY_SSL,
        WebRequestError.ERROR_SECURITY_BAD_CERT,
        WebRequestError.ERROR_BAD_HSTS_CERT ->
            "The site's certificate is not trusted" to
                "Provito blocked the connection because the certificate is invalid."
        WebRequestError.ERROR_HTTPS_ONLY ->
            "This site requires HTTPS" to "The site tried to downgrade to an insecure connection."
        WebRequestError.ERROR_REDIRECT_LOOP ->
            "Too many redirects" to "The site kept redirecting in a loop."
        WebRequestError.ERROR_PORT_BLOCKED ->
            "This port is blocked" to "Provito blocked a connection to a restricted port."
        WebRequestError.ERROR_CORRUPTED_CONTENT,
        WebRequestError.ERROR_INVALID_CONTENT_ENCODING,
        WebRequestError.ERROR_UNSAFE_CONTENT_TYPE ->
            "The page data is invalid" to "The server sent content Provito could not decode safely."
        WebRequestError.ERROR_CONTENT_CRASHED ->
            "The page crashed" to "The page's content process stopped. Try again."
        WebRequestError.ERROR_FILE_NOT_FOUND ->
            "File not found" to "The requested file does not exist."
        WebRequestError.ERROR_FILE_ACCESS_DENIED ->
            "Access denied" to "Provito is not allowed to open that file."
        WebRequestError.ERROR_SAFEBROWSING_MALWARE_URI,
        WebRequestError.ERROR_SAFEBROWSING_UNWANTED_URI,
        WebRequestError.ERROR_SAFEBROWSING_HARMFUL_URI,
        WebRequestError.ERROR_SAFEBROWSING_PHISHING_URI ->
            "Blocked by Safe Browsing" to "This site is flagged as dangerous."
        else -> when (error.category) {
            WebRequestError.ERROR_CATEGORY_NETWORK ->
                "Cannot reach the site" to "A network error stopped the page from loading."
            WebRequestError.ERROR_CATEGORY_SECURITY ->
                "The connection is not secure" to "Provito blocked an insecure connection."
            WebRequestError.ERROR_CATEGORY_PROXY ->
                "Proxy error" to "The proxy could not complete the request."
            WebRequestError.ERROR_CATEGORY_SAFEBROWSING ->
                "Blocked by Safe Browsing" to "This site is flagged as dangerous."
            WebRequestError.ERROR_CATEGORY_CONTENT ->
                "The page could not be displayed" to "The server's content could not be processed."
            WebRequestError.ERROR_CATEGORY_URI ->
                "That address is not valid" to "Provito could not understand the URL."
            else ->
                "Cannot reach the site" to "The page failed to load for an unknown reason."
        }
    }

    private fun loadTarget(tab: Tab, url: String) {
        tab.url = unsafeSites.level(url)?.let { warningUrl(url, it) } ?: url
    }

    private fun loadInTab(tab: Tab, url: String) {
        if (url == TABS_PAGE) {
            openTabsPage()
            return
        }
        if (url == SETTINGS_PAGE) {
            openSettingsPage()
            return
        }
        val final = unsafeSites.level(url)?.let { warningUrl(url, it) } ?: url
        tab.url = final
        if (tab === current) omnibox.setText(displayUrl(final))
        val session = tab.session
        if (session != null) session.loadUri(final) else ensureLoaded(tab)
    }

    /** Waits (briefly, bounded) for the unsafe lists to finish parsing so
     *  the shield can't be raced on the very first navigation. */
    private fun loadWhenShieldReady(tab: Tab, url: String, attempts: Int = 0) {
        val session = tab.session ?: return
        if (tab.url != url) return
        if (unsafeSites.isReady || attempts >= 10 || !url.startsWith("http")) {
            val final = unsafeSites.level(url)?.let { warningUrl(url, it) } ?: url
            tab.url = final
            session.loadUri(final)
            if (tab === current) omnibox.setText(displayUrl(final))
            return
        }
        mainHandler.postDelayed({ loadWhenShieldReady(tab, url, attempts + 1) }, 200)
    }

    private fun unsafeDownloadExists(): Boolean =
        java.io.File(filesDir, UnsafeSites.DOWNLOAD_FILE).exists()

    /** Creates the private GeckoSession backing a tab. */
    private fun ensureLoaded(tab: Tab) {
        if (tab.session != null) return
        val settings = GeckoSessionSettings.Builder()
            .usePrivateMode(true)
            .useTrackingProtection(prefs.getBoolean(KEY_BLOCK_ADS, true))
            .suspendMediaWhenInactive(true)
            .userAgentMode(GeckoSessionSettings.USER_AGENT_MODE_MOBILE)
            .viewportMode(GeckoSessionSettings.VIEWPORT_MODE_MOBILE)
            .allowJavascript(true)
            .build()

        val session = GeckoSession(settings)
        session.navigationDelegate = PrivacyNavigationDelegate(tab)
        session.contentDelegate = PrivacyContentDelegate(tab)
        session.progressDelegate = PrivacyProgressDelegate(tab)
        session.permissionDelegate = PrivacyPermissionDelegate()
        session.promptDelegate = PrivacyPromptDelegate()
        session.contentBlockingDelegate = PrivacyContentBlockingDelegate()
        tab.session = session
        session.open(runtime)

        if (tab.url.startsWith("http") && !unsafeSites.isReady) {
            loadWhenShieldReady(tab, tab.url)
        } else {
            session.loadUri(tab.url)
        }
    }

    private fun activeTabCount(): Int = synchronized(tabs) { tabs.count { it.isActive } }

    private fun enforceTabBudget() {
        if (!prefs.getBoolean(KEY_UNLOAD_TABS, true)) return
        val budgetTabs = RAM_BUDGET_MB / MB_PER_TAB
        while (activeTabCount() > budgetTabs) {
            val victim = synchronized(tabs) {
                tabs.filter { it.isActive && it !== current && !it.isOverlay }
                    .minByOrNull { it.lastUsed }
            } ?: break
            unloadTab(victim)
        }
    }

    private fun unloadTab(tab: Tab) {
        val session = tab.session ?: return
        if (tab === current) return
        tab.session = null
        runCatching { session.close() }
    }

    private fun closeTab(tab: Tab) {
        synchronized(tabs) { tabs.remove(tab) }
        val wasCurrent = current === tab
        if (wasCurrent) {
            current = null
            runCatching { geckoView.releaseSession() }
        }
        tab.session?.let { runCatching { it.close() } }
        tab.session = null
        if (wasCurrent) {
            val next = synchronized(tabs) { tabs.lastOrNull() }
            if (next != null) switchTo(next) else newTab(HOME_URL)
        }
    }

    /** Destroys and recreates every loaded tab so proxy changes take effect. */
    private fun recreateAllTabs() {
        val snapshot = synchronized(tabs) { tabs.toList() }
        runCatching { geckoView.releaseSession() }
        for (tab in snapshot) {
            tab.session?.let { runCatching { it.close() } }
            tab.session = null
        }
        val cur = current ?: snapshot.firstOrNull() ?: return
        ensureLoaded(cur)
        switchTo(cur)
    }

    private fun switchTo(tab: Tab) {
        val previous = current
        if (previous != null && previous !== tab) {
            previous.session?.setActive(false)
            previous.session?.setFocused(false)
        }
        current = tab
        ensureLoaded(tab)
        tab.lastUsed = System.currentTimeMillis()
        tab.session?.let { session ->
            geckoView.setSession(session)
            session.setActive(true)
            session.setFocused(true)
        }
        omnibox.setText(barUrl(tab))
        omnibox.clearFocus()
        enforceTabBudget()
        updateNavButtons()
    }

    private fun updateNavButtons() {
        btnBack.alpha = if (current?.canBack == true) 1f else 0.35f
        btnForward.alpha = if (current?.canForward == true) 1f else 0.35f
    }

    private fun handleBack() {
        val tab = current ?: run { finish(); return }
        if (tab.canBack) {
            tab.session?.goBack()
        } else if (tab.isOverlay) {
            closeTab(tab)
        } else if (tabs.size > 1) {
            closeTab(tab)
        } else {
            moveTaskToBack(true)
        }
    }

    // ---------- GeckoView delegates ----------

    private inner class PrivacyNavigationDelegate(private val tab: Tab) :
        GeckoSession.NavigationDelegate {

        override fun onLocationChange(
            session: GeckoSession,
            url: String?,
            perms: MutableList<GeckoSession.PermissionDelegate.ContentPermission>,
            hasUserGesture: Boolean
        ) {
            if (url == null) return
            tab.url = url
            if (tab === current && !omnibox.hasFocus()) omnibox.setText(barUrl(tab))
        }

        override fun onCanGoBack(session: GeckoSession, canGoBack: Boolean) {
            tab.canBack = canGoBack
            if (tab === current) updateNavButtons()
        }

        override fun onCanGoForward(session: GeckoSession, canGoForward: Boolean) {
            tab.canForward = canGoForward
            if (tab === current) updateNavButtons()
        }

        override fun onLoadRequest(
            session: GeckoSession,
            request: GeckoSession.NavigationDelegate.LoadRequest
        ): GeckoResult<AllowOrDeny>? = handleRequest(tab, request, subframe = false)

        override fun onSubframeLoadRequest(
            session: GeckoSession,
            request: GeckoSession.NavigationDelegate.LoadRequest
        ): GeckoResult<AllowOrDeny>? = handleRequest(tab, request, subframe = true)

        override fun onNewSession(
            session: GeckoSession,
            uri: String
        ): GeckoResult<GeckoSession>? {
            // Create the tab but attach it to our GeckoView only after
            // GeckoView has finished setting up the new window.
            val tab = Tab(uri)
            loadTarget(tab, uri)
            synchronized(tabs) { tabs.add(tab) }
            tab.lastUsed = System.currentTimeMillis()
            ensureLoaded(tab)
            val created = tab.session ?: return null
            mainHandler.post { if (tabs.contains(tab)) switchTo(tab) }
            return GeckoResult.fromValue(created)
        }

        override fun onLoadError(
            session: GeckoSession,
            uri: String?,
            error: WebRequestError
        ): GeckoResult<String>? {
            if (tab === current) {
                progress.hide()
                updateNavButtons()
            }
            val failed = uri ?: tab.url
            // Never feed our own pages back into the error handler.
            if (failed.startsWith("resource://android/assets/") || failed.startsWith("provito://")) {
                return null
            }
            tab.errorPage = true
            tab.failedUrl = failed
            val page = errorUrl(failed, error)
            // Load the error page as a normal internal page so its
            // provito:// buttons behave exactly like the other pages.
            mainHandler.post {
                if (tab.session === session) {
                    tab.url = page
                    if (tab === current) omnibox.setText(barUrl(tab))
                    session.loadUri(page)
                }
            }
            return null
        }
    }

    private fun handleRequest(
        tab: Tab,
        request: GeckoSession.NavigationDelegate.LoadRequest,
        subframe: Boolean
    ): GeckoResult<AllowOrDeny> {
        val raw = request.uri
        val uri = Uri.parse(raw)
        val scheme = uri.scheme?.lowercase().orEmpty()

        if (scheme == "provito") {
            handleProvitoAction(tab, uri, request.triggerUri ?: tab.url)
            return GeckoResult.deny()
        }

        // target=_blank / window.open: GeckoView asks for a new session.
        if (!subframe && request.target == GeckoSession.NavigationDelegate.TARGET_WINDOW_NEW) {
            return GeckoResult.allow()
        }

        if (scheme == "http" || scheme == "https") {
            if (!subframe) {
                val level = unsafeSites.level(raw)
                if (level != null) {
                    tab.session?.loadUri(warningUrl(raw, level))
                    return GeckoResult.deny()
                }
            }
            return GeckoResult.allow()
        }

        // GeckoView exposes APK assets at resource://android/assets/ (the
        // WebView-only file:///android_asset/ path does not exist here).
        if (scheme == "resource") {
            return if (raw.startsWith("resource://android/assets/")) {
                GeckoResult.allow()
            } else {
                GeckoResult.deny()
            }
        }
        if (scheme == "file") {
            return GeckoResult.deny()
        }
        if (scheme == "about" || scheme == "data" || scheme == "blob") {
            return GeckoResult.allow()
        }

        // Unknown / external scheme: hand to the system, main frame only.
        if (subframe) return GeckoResult.deny()
        return try {
            startActivity(
                Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            GeckoResult.deny()
        } catch (_: Exception) {
            GeckoResult.deny()
        }
    }

    private fun handleProvitoAction(tab: Tab, uri: Uri, here: String) {
        when (uri.host) {
            "unsafe-continue" -> {
                if (!here.startsWith(UNSAFE_PAGE)) return
                val target = uri.getQueryParameter("u") ?: return
                unsafeSites.allowSession(target)
                tab.url = target
                tab.session?.loadUri(target)
            }
            "retry" -> {
                if (!here.startsWith(ERROR_PAGE) && !tab.errorPage) return
                val target = uri.getQueryParameter("u") ?: return
                tab.errorPage = false
                tab.failedUrl = null
                tab.url = target
                tab.session?.loadUri(target)
            }
            "home" -> {
                if (!here.startsWith(UNSAFE_PAGE) &&
                    !here.startsWith(SETTINGS_PAGE) &&
                    !here.startsWith(TABS_PAGE) &&
                    !here.startsWith(ERROR_PAGE) &&
                    !tab.errorPage
                ) return
                tab.errorPage = false
                tab.failedUrl = null
                loadInTab(tab, HOME_URL)
            }
            "set" -> {
                if (!here.startsWith(SETTINGS_PAGE)) return
                val key = uri.getQueryParameter("k") ?: return
                val value = uri.getQueryParameter("v") ?: return
                applySetting(key, value)
            }
            "act" -> {
                if (!here.startsWith(SETTINGS_PAGE)) return
                runSettingAction(uri.getQueryParameter("name") ?: return)
            }
            "tab" -> {
                if (!here.startsWith(TABS_PAGE)) return
                handleTabAction(tab, uri)
            }
        }
    }

    private inner class PrivacyContentDelegate(private val tab: Tab) :
        GeckoSession.ContentDelegate {

        override fun onTitleChange(session: GeckoSession, title: String?) {
            if (!title.isNullOrBlank()) tab.title = title
        }

        override fun onCloseRequest(session: GeckoSession) {
            closeTab(tab)
        }

        override fun onExternalResponse(session: GeckoSession, response: WebResponse) {
            val uri = Uri.parse(response.uri)
            val scheme = uri.scheme?.lowercase()
            if (scheme == "http" || scheme == "https") {
                try {
                    val request = DownloadManager.Request(uri)
                        .setNotificationVisibility(
                            DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
                        )
                        .setTitle(uri.lastPathSegment ?: "download")
                    (getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager)
                        .enqueue(request)
                    toast("Download started")
                } catch (_: Exception) {
                    // ignore unsupported downloads
                }
            }
            runCatching { response.body?.close() }
        }

        override fun onCrash(session: GeckoSession) = sessionDied(tab, "Tab crashed")

        override fun onKill(session: GeckoSession) = sessionDied(tab, "Tab stopped")
    }

    private fun sessionDied(tab: Tab, message: String) {
        if (tab.session == null) return
        toast(message)
        tab.session = null
        if (tab === current) {
            val next = synchronized(tabs) {
                tabs.firstOrNull { it !== tab && it.session != null }
            }
            if (next != null) switchTo(next) else newTab(HOME_URL)
        }
    }

    private inner class PrivacyProgressDelegate(private val tab: Tab) :
        GeckoSession.ProgressDelegate {

        override fun onPageStart(session: GeckoSession, url: String) {
            if (!url.startsWith(ERROR_PAGE)) {
                tab.errorPage = false
                tab.failedUrl = null
            }
            if (tab === current) {
                progress.show()
                progress.setIndeterminate(true)
            }
        }

        override fun onProgressChange(session: GeckoSession, newProgress: Int) {
            if (tab !== current) return
            if (newProgress in 1..99) {
                progress.show()
                progress.setIndeterminate(false)
                progress.setProgressCompat(newProgress, true)
            } else if (newProgress >= 100) {
                progress.hide()
            }
        }

        override fun onPageStop(session: GeckoSession, success: Boolean) {
            if (tab === current) {
                progress.hide()
                updateNavButtons()
            }
        }
    }

    private inner class PrivacyPermissionDelegate : GeckoSession.PermissionDelegate {

        override fun onContentPermissionRequest(
            session: GeckoSession,
            perm: GeckoSession.PermissionDelegate.ContentPermission
        ): GeckoResult<Int> =
            GeckoResult.fromValue(GeckoSession.PermissionDelegate.ContentPermission.VALUE_DENY)

        override fun onMediaPermissionRequest(
            session: GeckoSession,
            uri: String,
            video: Array<GeckoSession.PermissionDelegate.MediaSource>?,
            audio: Array<GeckoSession.PermissionDelegate.MediaSource>?,
            callback: GeckoSession.PermissionDelegate.MediaCallback
        ) = callback.reject()

        override fun onAndroidPermissionsRequest(
            session: GeckoSession,
            permissions: Array<String>?,
            callback: GeckoSession.PermissionDelegate.Callback
        ) = callback.reject()
    }

    private inner class PrivacyPromptDelegate : GeckoSession.PromptDelegate {

        override fun onAlertPrompt(
            session: GeckoSession,
            prompt: AlertPrompt
        ): GeckoResult<PromptResponse> {
            val result = GeckoResult<PromptResponse>()
            AlertDialog.Builder(this@MainActivity)
                .setTitle(prompt.title)
                .setMessage(prompt.message)
                .setPositiveButton("OK") { _, _ -> result.complete(prompt.dismiss()) }
                .setOnCancelListener { result.complete(prompt.dismiss()) }
                .show()
            return result
        }

        override fun onButtonPrompt(
            session: GeckoSession,
            prompt: ButtonPrompt
        ): GeckoResult<PromptResponse> {
            val result = GeckoResult<PromptResponse>()
            AlertDialog.Builder(this@MainActivity)
                .setTitle(prompt.title)
                .setMessage(prompt.message)
                .setPositiveButton("OK") { _, _ -> result.complete(prompt.confirm(0)) }
                .setNegativeButton("Cancel") { _, _ -> result.complete(prompt.dismiss()) }
                .setOnCancelListener { result.complete(prompt.dismiss()) }
                .show()
            return result
        }

        override fun onTextPrompt(
            session: GeckoSession,
            prompt: TextPrompt
        ): GeckoResult<PromptResponse> {
            val result = GeckoResult<PromptResponse>()
            val input = EditText(this@MainActivity).apply {
                setText(prompt.defaultValue ?: "")
            }
            AlertDialog.Builder(this@MainActivity)
                .setTitle(prompt.title)
                .setMessage(prompt.message)
                .setView(input)
                .setPositiveButton("OK") { _, _ ->
                    result.complete(prompt.confirm(input.text.toString()))
                }
                .setNegativeButton("Cancel") { _, _ -> result.complete(prompt.dismiss()) }
                .setOnCancelListener { result.complete(prompt.dismiss()) }
                .show()
            return result
        }

        override fun onChoicePrompt(
            session: GeckoSession,
            prompt: ChoicePrompt
        ): GeckoResult<PromptResponse> {
            val result = GeckoResult<PromptResponse>()
            val labels = prompt.choices.map { it.label ?: "" }.toTypedArray()
            AlertDialog.Builder(this@MainActivity)
                .setTitle(prompt.title)
                .setMessage(prompt.message)
                .setItems(labels) { _, which ->
                    result.complete(prompt.confirm(prompt.choices[which]))
                }
                .setOnCancelListener { result.complete(prompt.dismiss()) }
                .show()
            return result
        }

        override fun onAuthPrompt(
            session: GeckoSession,
            prompt: AuthPrompt
        ): GeckoResult<PromptResponse> {
            val result = GeckoResult<PromptResponse>()
            val column = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(56, 16, 56, 0)
            }
            val user = EditText(this@MainActivity).apply {
                hint = "Username"
                setText(prompt.authOptions.username ?: "")
            }
            val pass = EditText(this@MainActivity).apply {
                hint = "Password"
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            }
            column.addView(user)
            column.addView(pass)
            AlertDialog.Builder(this@MainActivity)
                .setTitle(prompt.title ?: "Sign in")
                .setMessage(prompt.message)
                .setView(column)
                .setPositiveButton("Sign in") { _, _ ->
                    result.complete(
                        prompt.confirm(user.text.toString(), pass.text.toString())
                    )
                }
                .setNegativeButton("Cancel") { _, _ -> result.complete(prompt.dismiss()) }
                .setOnCancelListener { result.complete(prompt.dismiss()) }
                .show()
            return result
        }

        override fun onBeforeUnloadPrompt(
            session: GeckoSession,
            prompt: BeforeUnloadPrompt
        ): GeckoResult<PromptResponse> =
            GeckoResult.fromValue(prompt.confirm(AllowOrDeny.ALLOW))

        override fun onRepostConfirmPrompt(
            session: GeckoSession,
            prompt: RepostConfirmPrompt
        ): GeckoResult<PromptResponse> =
            GeckoResult.fromValue(prompt.dismiss())

        override fun onPopupPrompt(
            session: GeckoSession,
            prompt: PopupPrompt
        ): GeckoResult<PromptResponse> =
            GeckoResult.fromValue(prompt.confirm(AllowOrDeny.ALLOW))

        override fun onFilePrompt(
            session: GeckoSession,
            prompt: FilePrompt
        ): GeckoResult<PromptResponse> {
            pendingFileResult?.complete(pendingFilePrompt?.dismiss() ?: prompt.dismiss())
            val result = GeckoResult<PromptResponse>()
            pendingFilePrompt = prompt
            pendingFileResult = result
            val types = prompt.mimeTypes
                ?.filter { it.isNotBlank() }
                ?.toTypedArray()
                ?.takeIf { it.isNotEmpty() }
                ?: arrayOf("*/*")
            return try {
                filePicker.launch(types)
                result
            } catch (_: Throwable) {
                pendingFilePrompt = null
                pendingFileResult = null
                GeckoResult.fromValue(prompt.dismiss())
            }
        }
    }

    private inner class PrivacyContentBlockingDelegate : ContentBlocking.Delegate {
        override fun onContentBlocked(session: GeckoSession, event: ContentBlocking.BlockEvent) {
            blockedTotal.incrementAndGet()
            refreshShieldBadge()
        }
    }

    // ---------- UI wiring ----------

    /** What the address bar should show for a tab. */
    private fun barUrl(tab: Tab): String {
        val failed = tab.failedUrl
        return if (tab.errorPage && failed != null) failed else displayUrl(tab.url)
    }

    private fun displayUrl(url: String): String = when {
        url.startsWith(HOME_URL) -> ""
        url.startsWith(TABS_PAGE) -> "provito://tabs"
        url.startsWith(SETTINGS_PAGE) -> "provito://settings"
        url.startsWith(UNSAFE_PAGE) -> "Blocked site"
        url.startsWith(ERROR_PAGE) -> {
            val failed = hashParam(url, "u").orEmpty()
            if (failed.startsWith("resource://android/assets/")) "" else failed
        }
        // Never expose GeckoView's internal asset scheme in the UI.
        url.startsWith("resource://android/assets/") -> ""
        else -> url
    }

    private fun displayTitle(tab: Tab): String = when {
        tab.url.startsWith(TABS_PAGE) -> "Tabs"
        tab.url.startsWith(SETTINGS_PAGE) -> "Settings"
        tab.url.startsWith(UNSAFE_PAGE) -> "Blocked site"
        tab.url.startsWith(ERROR_PAGE) -> "Problem loading page"
        else -> tab.title.ifBlank { "New tab" }
    }

    private fun wireChrome() {
        findViewById<ImageButton>(R.id.btnShield).setOnClickListener { showPrivacyStats() }
        findViewById<ImageButton>(R.id.btnBack).setOnClickListener {
            current?.session?.goBack()
        }
        findViewById<ImageButton>(R.id.btnForward).setOnClickListener {
            current?.session?.goForward()
        }
        findViewById<ImageButton>(R.id.btnHome).setOnClickListener {
            val tab = current ?: return@setOnClickListener
            if (tab.isOverlay) newTab(HOME_URL) else loadInTab(tab, HOME_URL)
        }
        findViewById<ImageButton>(R.id.btnRefresh).setOnClickListener {
            val tab = current ?: return@setOnClickListener
            val failed = tab.failedUrl
            if (tab.errorPage && failed != null) {
                tab.errorPage = false
                tab.failedUrl = null
                tab.url = failed
                tab.session?.loadUri(failed)
            } else {
                tab.session?.reload()
            }
        }
        findViewById<ImageButton>(R.id.btnTabs).setOnClickListener { openTabsPage() }
        findViewById<ImageButton>(R.id.btnMenu).setOnClickListener {
            showMenu(it as ImageButton)
        }
    }

    private fun openTabsPage() {
        val cur = current
        if (cur != null && cur.url.startsWith(TABS_PAGE)) {
            refreshTabsOverlay()
            return
        }
        if (cur != null && cur.isOverlay) closeTab(cur)
        newTab(tabsUrl())
    }

    private fun openSettingsPage() {
        val cur = current
        if (cur != null && cur.url.startsWith(SETTINGS_PAGE)) {
            refreshSettingsOverlay()
            return
        }
        if (cur != null && cur.isOverlay) closeTab(cur)
        newTab(settingsUrl())
    }

    private fun tabsUrl(): String {
        val array = org.json.JSONArray()
        synchronized(tabs) {
            tabs.forEachIndexed { index, tab ->
                if (tab.url.startsWith(TABS_PAGE)) return@forEachIndexed
                val item = org.json.JSONObject()
                item.put("id", index)
                item.put("title", displayTitle(tab))
                item.put("url", barUrl(tab))
                item.put("unloaded", tab.session == null)
                item.put("current", tab === current)
                array.put(item)
            }
        }
        return TABS_PAGE + "#data=" + URLEncoder.encode(array.toString(), "UTF-8")
    }

    private fun settingsUrl(): String {
        val hash = StringBuilder()
        fun add(key: String, value: Any) {
            if (hash.isNotEmpty()) hash.append('&')
            hash.append(key).append('=').append(URLEncoder.encode(value.toString(), "UTF-8"))
        }
        add("v", BuildConfig.VERSION_NAME)
        add("search", prefs.getString(KEY_SEARCH_ENGINE, "brave") ?: "brave")
        add("block", if (prefs.getBoolean(KEY_BLOCK_ADS, true)) 1 else 0)
        add("wipe", if (prefs.getBoolean(KEY_WIPE_ON_EXIT, true)) 1 else 0)
        add("unload", if (prefs.getBoolean(KEY_UNLOAD_TABS, true)) 1 else 0)
        add("resize", if (prefs.getBoolean(KEY_AUTO_UI_RESIZE, true)) 1 else 0)
        add("onion", if (prefs.getBoolean(ProxyManager.KEY_ONION_MODE, false)) 1 else 0)
        add("updchk", if (Updater.isEnabled(this)) 1 else 0)
        add("blocked", blockedTotal.get())
        add("orbot", if (ProxyManager.isOrbotInstalled(this)) 1 else 0)
        return SETTINGS_PAGE + "#" + hash
    }

    private fun refreshTabsOverlay() {
        val tab = current ?: return
        if (!tab.url.startsWith(TABS_PAGE)) return
        val url = tabsUrl()
        tab.url = url
        tab.session?.loadUri(url)
    }

    private fun refreshSettingsOverlay() {
        val tab = current ?: return
        if (!tab.url.startsWith(SETTINGS_PAGE)) return
        val url = settingsUrl()
        tab.url = url
        tab.session?.loadUri(url)
    }

    private fun wireOmnibox() {
        omnibox.setOnFocusChangeListener { _, focused ->
            if (!focused) current?.let { omnibox.setText(barUrl(it)) }
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

    // ---------- Settings and tabs page actions ----------

    private fun parseBool(value: String): Boolean? = when (value) {
        "1", "true", "TRUE" -> true
        "0", "false", "FALSE" -> false
        else -> null
    }

    private fun applySetting(key: String, value: String) {
        val editor = prefs.edit()
        when (key) {
            KEY_SEARCH_ENGINE -> {
                if (value !in setOf("brave", "duckduckgo", "mojeek")) return
                editor.putString(KEY_SEARCH_ENGINE, value)
            }
            KEY_BLOCK_ADS -> {
                val enabled = parseBool(value) ?: return
                editor.putBoolean(KEY_BLOCK_ADS, enabled)
                synchronized(tabs) {
                    tabs.forEach { it.session?.settings?.useTrackingProtection = enabled }
                }
            }
            KEY_UNLOAD_TABS -> {
                val enabled = parseBool(value) ?: return
                editor.putBoolean(KEY_UNLOAD_TABS, enabled)
                if (enabled) enforceTabBudget()
            }
            KEY_AUTO_UI_RESIZE -> {
                val enabled = parseBool(value) ?: return
                editor.putBoolean(KEY_AUTO_UI_RESIZE, enabled)
                applyResponsiveUi()
            }
            KEY_WIPE_ON_EXIT -> {
                val enabled = parseBool(value) ?: return
                editor.putBoolean(KEY_WIPE_ON_EXIT, enabled)
            }
            Updater.PREF_UPDATE_CHECK -> {
                val enabled = parseBool(value) ?: return
                editor.putBoolean(Updater.PREF_UPDATE_CHECK, enabled)
            }
            ProxyManager.KEY_ONION_MODE -> {
                val enabled = parseBool(value) ?: return
                editor.putBoolean(ProxyManager.KEY_ONION_MODE, enabled)
                editor.apply()
                ProxyManager.applyToGecko(this) { ok ->
                    runOnUiThread {
                        when {
                            !ok -> toast("Proxy settings could not be applied")
                            enabled && !ProxyManager.isOrbotProxyUp() ->
                                toast("Orbot is not running - start it, then reload tabs")
                            else -> {
                                recreateAllTabs()
                                toast(
                                    if (enabled) "Traffic now routed through Tor"
                                    else "Proxy routing off"
                                )
                            }
                        }
                        refreshSettingsOverlay()
                    }
                }
                return
            }
            else -> return
        }
        editor.apply()
    }

    private fun runSettingAction(name: String) {
        when (name) {
            "orbot-install" -> ProxyManager.openOrbot(this)
            "orbot-status" -> Thread {
                val installed = ProxyManager.isOrbotInstalled(this)
                val running = ProxyManager.isOrbotProxyUp()
                runOnUiThread {
                    toast(
                        "Orbot: " + when {
                            running -> "running"
                            installed -> "installed but not started"
                            else -> "not installed"
                        }
                    )
                }
            }.start()
            "check-updates" -> Updater.maybeCheck(this, automatic = false)
            "unsafe-update" -> updateUnsafeLists()
            "wipe" -> {
                wipeBrowsingData()
                recreateAllTabs()
                toast("Browsing data cleared")
            }
        }
    }

    private fun handleTabAction(tab: Tab, uri: Uri) {
        when (uri.getQueryParameter("op")) {
            "select" -> {
                val id = uri.getQueryParameter("id")?.toIntOrNull() ?: return
                val target = synchronized(tabs) { tabs.getOrNull(id) } ?: return
                if (tab !== target && tab.isOverlay) closeTab(tab)
                switchTo(target)
            }
            "close" -> {
                val id = uri.getQueryParameter("id")?.toIntOrNull() ?: return
                val victim = synchronized(tabs) { tabs.getOrNull(id) } ?: return
                closeTab(victim)
                refreshTabsOverlay()
            }
            "new" -> {
                if (tab.isOverlay) closeTab(tab)
                newTab(HOME_URL)
            }
        }
    }

    private fun updateUnsafeLists() {
        toast("Updating unsafe-site lists...")
        Thread {
            val okCount = unsafeSites.downloadAllSources()
            runOnUiThread {
                toast(
                    if (okCount > 0) {
                        "Updated $okCount/${UnsafeSites.SOURCES.size} lists " +
                            "(malware: ${unsafeSites.malwareCount}, " +
                            "phishing: ${unsafeSites.phishingCount})"
                    } else {
                        "Update failed - check connection"
                    }
                )
            }
        }.start()
    }

    // ---------- Menu, privacy actions ----------

    private fun showMenu(anchor: ImageButton) {
        val menu = PopupMenu(this, anchor)
        menu.menuInflater.inflate(R.menu.main_menu, menu.menu)
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
                    current?.let { closeTab(it) }
                    true
                }
                R.id.action_settings -> {
                    openSettingsPage()
                    true
                }
                R.id.action_clear_data -> {
                    wipeBrowsingData()
                    recreateAllTabs()
                    toast("Browsing data cleared")
                    true
                }
                R.id.action_wipe_on_exit -> {
                    item.isChecked = !item.isChecked
                    prefs.edit().putBoolean(KEY_WIPE_ON_EXIT, item.isChecked).apply()
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
                R.id.action_update_unsafe -> {
                    updateUnsafeLists()
                    true
                }
                R.id.action_about -> {
                    AlertDialog.Builder(this)
                        .setTitle("Provito")
                        .setMessage(
                            "Private browser v${BuildConfig.VERSION_NAME}\n\n" +
                                "Engine: GeckoView 140 (Mozilla Firefox)\n" +
                                "Default search: Brave\n\n" +
                                "No accounts, no history, no telemetry, " +
                                "no third-party cookies.\n" +
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
                "Blocked since launch: ${blockedTotal.get()}\n\n" +
                    "Engine: GeckoView 140 (Firefox)\n\n" +
                    "Active protections:\n" +
                    "- Strict tracking protection (trackers, cryptominers, " +
                    "fingerprinters, social trackers)\n" +
                    "- Safe Browsing for malware and phishing hosts\n" +
                    "- Third-party cookies blocked\n" +
                    "- Geolocation, camera and microphone denied\n" +
                    "- Private sessions: nothing written to disk\n" +
                    "- Unsafe-site shield (six live lists)"
            )
            .setPositiveButton("OK", null)
            .show()
    }

    private fun refreshShieldBadge() {
        txtBlocked.text = blockedTotal.get().let {
            if (it > 999) "${it / 1000}k" else it.toString()
        }
    }

    private fun wipeBrowsingData() {
        runCatching {
            runtime.storageController.clearData(StorageController.ClearFlags.ALL)
        }
        blockedTotal.set(0)
        refreshShieldBadge()
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    override fun onPause() {
        super.onPause()
        current?.session?.setActive(false)
    }

    override fun onResume() {
        super.onResume()
        current?.session?.setActive(true)
    }

    override fun onDestroy() {
        val wipe = prefs.getBoolean(KEY_WIPE_ON_EXIT, true)
        if (wipe) wipeBrowsingData()
        Updater.cleanup(this)
        runCatching { geckoView.releaseSession() }
        synchronized(tabs) {
            for (tab in tabs.toList()) {
                tab.session?.let { runCatching { it.close() } }
                tab.session = null
            }
            tabs.clear()
        }
        super.onDestroy()
    }
}
