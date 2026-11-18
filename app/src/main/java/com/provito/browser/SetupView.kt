package com.provito.browser

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView

/**
 * First-run setup. Shown only when no saved data is detected
 * (the "setup_done" flag is absent). Slides up smoothly over the app,
 * lets the user pick a search engine and core privacy/performance
 * options, with the Continue button pinned at the bottom.
 */
@SuppressLint("UseSwitchCompatOrMaterialCode")
class SetupView(context: Context) : FrameLayout(context) {

    companion object {
        const val KEY_SETUP_DONE = "setup_done"
        private const val BG = 0xFF14141F.toInt()
        private const val CARD = 0xFF1E1E2E.toInt()
        private const val BORDER = 0xFF2C2C45.toInt()
        private const val ACCENT = 0xFF8E7BFF.toInt()
        private const val TEXT = 0xFFECEAF8.toInt()
        private const val MUTED = 0xFF8F8CA6.toInt()
        private val ENGINES = arrayOf(
            arrayOf("brave", "Brave"),
            arrayOf("duckduckgo", "DuckDuckGo"),
            arrayOf("mojeek", "Mojeek")
        )
    }

    var onFinished: (() -> Unit)? = null

    private val prefs = context.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE)
    private var selectedEngine = "brave"
    private val chipViews = HashMap<String, TextView>()

    private var blockSwitch: Switch? = null
    private var unloadSwitch: Switch? = null
    private var updatesSwitch: Switch? = null

    private fun dp(v: Float): Int = (v * resources.displayMetrics.density).toInt()

    init {
        setBackgroundColor(BG)
        buildUi()
    }

    private fun pill(fill: Int, stroke: Int): GradientDrawable =
        GradientDrawable().apply {
            cornerRadius = dp(24f).toFloat()
            setColor(fill)
            setStroke(dp(1.5f), stroke)
        }

    private fun label(text: String, color: Int, size: Float, bold: Boolean = false): TextView =
        TextView(context).apply {
            this.text = text
            setTextColor(color)
            textSize = size
            if (bold) typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }

    private fun buildUi() {
        val scroll = ScrollView(context).apply {
            isVerticalScrollBarEnabled = false
            clipToPadding = false
        }
        val col = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(28f), dp(40f), dp(28f), dp(28f))
        }

        col.addView(ImageView(context).apply {
            setImageResource(R.drawable.ic_launcher_foreground)
            layoutParams = LinearLayout.LayoutParams(dp(84f), dp(84f))
        })

        col.addView(label("Welcome to Provito", TEXT, 26f, true).apply {
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(16f) }
        })

        col.addView(label("Private by default.\nNothing saved, nothing sent.", MUTED, 14f).apply {
            gravity = Gravity.CENTER
            setLineSpacing(0f, 1.25f)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(10f) }
        })

        // Search engine
        col.addView(label("SEARCH ENGINE", ACCENT, 11f, true).apply {
            letterSpacing = 0.12f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(28f) }
        })

        val chipRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        for ((id, name) in ENGINES) {
            val chip = TextView(context).apply {
                text = name
                textSize = 14f
                setPadding(dp(18f), dp(10f), dp(18f), dp(10f))
                setOnClickListener {
                    selectedEngine = id
                    restyleChips()
                }
            }
            chipViews[id] = chip
            chipRow.addView(
                chip,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginStart = dp(5f); marginEnd = dp(5f) }
            )
        }
        col.addView(
            chipRow,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(10f) }
        )
        restyleChips()

        // Options card
        col.addView(label("PREFERENCES", ACCENT, 11f, true).apply {
            letterSpacing = 0.12f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(26f) }
        })

        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = pill(CARD, BORDER)
        }
        blockSwitch = addSwitchRow(card, "Block ads and trackers",
            "Local rule engine with open-source blocklists", true)
        unloadSwitch = addSwitchRow(card, "Unload inactive tabs",
            "Keeps tab memory around 1 GB; tabs reopen instantly", true)
        updatesSwitch = addSwitchRow(card, "Auto-check for updates",
            "Anonymous GitHub check; you always approve installs", true)
        col.addView(
            card,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(10f) }
        )

        col.addView(label("Onion mode (Tor), proxies and data wipe can be\nenabled anytime in Settings.", MUTED, 11f).apply {
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(18f) }
        })

        scroll.addView(col)
        addView(
            scroll,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        )

        // Continue button, pinned at the bottom, always visible.
        val continueBtn = TextView(context).apply {
            text = "Continue"
            textSize = 16f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            setTextColor(0xFF17122E.toInt())
            gravity = Gravity.CENTER
            background = pill(ACCENT, ACCENT)
            setOnClickListener { finish() }
            val pad = dp(16f)
            setPadding(0, pad, 0, pad)
        }
        val btnWrap = FrameLayout(context)
        btnWrap.addView(
            continueBtn,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
                marginStart = dp(28f); marginEnd = dp(28f); bottomMargin = dp(28f)
            }
        )
        btnWrap.background = GradientDrawable().apply {
            setColor(BG)
        }
        addView(
            btnWrap,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM)
        )
    }

    private fun addSwitchRow(
        parent: LinearLayout,
        title: String,
        desc: String,
        initial: Boolean
    ): Switch {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16f), dp(12f), dp(16f), dp(12f))
        }
        val info = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        info.addView(label(title, TEXT, 15f, true))
        info.addView(label(desc, MUTED, 11f).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(2f) }
        })
        row.addView(
            info,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(10f)
            }
        )
        val sw = Switch(context).apply { isChecked = initial }
        row.addView(sw, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        parent.addView(
            row,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
        // separator
        parent.addView(
            View(context).apply { setBackgroundColor(0xFF2C2C45.toInt()) },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(1f)
            )
        )
        return sw
    }

    private fun restyleChips() {
        for ((id, chip) in chipViews) {
            if (id == selectedEngine) {
                chip.background = pill(ACCENT, ACCENT)
                chip.setTextColor(0xFF17122E.toInt())
            } else {
                chip.background = pill(CARD, BORDER)
                chip.setTextColor(TEXT)
            }
        }
    }

    /** Slides the whole setup up smoothly. Call after layout. */
    fun start() {
        translationY = height * 0.22f
        alpha = 0f
        animate()
            .translationY(0f)
            .alpha(1f)
            .setDuration(560L)
            .setInterpolator(OvershootInterpolator(0.85f))
            .withLayer()
            .start()
    }

    private fun finish() {
        val e = prefs.edit()
        e.putString(MainActivity.KEY_SEARCH_ENGINE, selectedEngine)
        e.putBoolean(MainActivity.KEY_BLOCK_ADS, blockSwitch?.isChecked ?: true)
        e.putBoolean(MainActivity.KEY_UNLOAD_TABS, unloadSwitch?.isChecked ?: true)
        e.putBoolean(Updater.PREF_UPDATE_CHECK, updatesSwitch?.isChecked ?: true)
        e.putBoolean(KEY_SETUP_DONE, true)
        e.apply()

        animate()
            .translationY(-height * 0.16f)
            .alpha(0f)
            .setDuration(420L)
            .setInterpolator(AccelerateInterpolator(0.8f))
            .withLayer()
            .withEndAction {
                (parent as? ViewGroup)?.removeView(this)
                onFinished?.invoke()
            }
            .start()
    }
}
