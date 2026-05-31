package com.giantbomb.tv

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.FragmentActivity
import com.giantbomb.tv.data.UpdateChecker
import com.giantbomb.tv.mobile.MobileBrowseFragment
import com.giantbomb.tv.mobile.MobileShowGridFragment
import com.giantbomb.tv.util.DeviceUtil
import com.google.android.material.bottomnavigation.BottomNavigationView
import kotlinx.coroutines.*

class MainActivity : FragmentActivity(), CoroutineScope by MainScope() {

    companion object {
        const val SETUP_REQUEST = 1001

        // Tags for the three mobile bottom-nav tabs. We add all three
        // fragments on first launch and show/hide rather than replace, so
        // each tab keeps its scroll/load state when the user switches away
        // and back.
        private const val TAG_HOME = "mobile.home"
        private const val TAG_SHOWS = "mobile.shows"
        private const val TAG_PODCASTS = "mobile.podcasts"
    }

    private var isTv = false
    private var exitOverlay: View? = null
    private var updateOverlay: View? = null

    // Activity-level long-press detection for the TV side menu. Leanback's
    // HeadersSupportFragment installs its own click listener and BaseGridView
    // short-circuits DPAD_CENTER to performClick() — the View framework's
    // long-press logic never gets to run. So we intercept here: schedule a
    // runnable on the first ACTION_DOWN, fire the context menu if it's still
    // pending after the long-press timeout, otherwise treat it as a normal
    // click and call performClick on whatever was focused.
    private val longPressHandler = Handler(Looper.getMainLooper())
    private var pendingHeaderLongPress: Runnable? = null
    private var headerLongPressFired = false

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val isSelectKey = event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
                event.keyCode == KeyEvent.KEYCODE_ENTER ||
                event.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER ||
                event.keyCode == KeyEvent.KEYCODE_BUTTON_A

        // While a modal overlay (exit / update) is up, never intercept select
        // keys — the BrowseFragment is still "showing headers" underneath, so
        // without this guard the long-press logic below swallows the press and
        // the dialog's own buttons never get clicked (a held press past the
        // long-press timeout clears pendingHeaderLongPress, so ACTION_UP skips
        // performClick entirely). Let the framework dispatch select to the
        // focused button normally.
        if (isTv && isSelectKey && exitOverlay == null && updateOverlay == null) {
            val browse = supportFragmentManager
                .findFragmentById(R.id.main_fragment_container) as? BrowseFragment
            // Only intercept while the side menu is up — in rows we want
            // ordinary click behaviour (open detail / navigate).
            if (browse != null && browse.isShowingHeaders) {
                when (event.action) {
                    KeyEvent.ACTION_DOWN -> {
                        if (event.repeatCount == 0 && pendingHeaderLongPress == null) {
                            headerLongPressFired = false
                            val r = Runnable {
                                pendingHeaderLongPress = null
                                headerLongPressFired = browse.tryShowFocusedHeaderMenu()
                            }
                            pendingHeaderLongPress = r
                            longPressHandler.postDelayed(
                                r, ViewConfiguration.getLongPressTimeout().toLong()
                            )
                        }
                        return true
                    }
                    KeyEvent.ACTION_UP -> {
                        val pending = pendingHeaderLongPress
                        if (pending != null) {
                            longPressHandler.removeCallbacks(pending)
                            pendingHeaderLongPress = null
                            if (!headerLongPressFired) {
                                currentFocus?.performClick()
                            }
                        }
                        headerLongPressFired = false
                        return true
                    }
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    // Cancel any in-flight header long-press. dispatchKeyEvent stops intercepting
    // select keys once an overlay is up, so the ACTION_UP that would normally
    // remove this callback never runs — without this, a press held while an
    // overlay appears would later pop the header context menu under the dialog
    // and leave pendingHeaderLongPress non-null, breaking the next long-press.
    private fun cancelPendingHeaderLongPress() {
        pendingHeaderLongPress?.let { longPressHandler.removeCallbacks(it) }
        pendingHeaderLongPress = null
        headerLongPressFired = false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        isTv = DeviceUtil.isTv(this)
        if (isTv) {
            setTheme(R.style.Theme_GiantBombTV)
        }
        super.onCreate(savedInstanceState)

        if (!isTv) {
            enableEdgeToEdge()
        }

        setContentView(R.layout.activity_main)

        if (savedInstanceState == null) {
            if (isTv) {
                supportFragmentManager.beginTransaction()
                    .replace(R.id.main_fragment_container, BrowseFragment())
                    .commit()
            } else {
                setUpMobileTabs()
            }

            // Check for updates silently (sideload builds only)
            if (BuildConfig.ENABLE_SELF_UPDATE) {
                checkForUpdate()
            }
        } else if (!isTv) {
            // Recreated activity: re-attach the bottom-nav listener; the
            // fragments themselves are restored by FragmentManager.
            wireMobileBottomNav()
        }
    }

    private fun setUpMobileTabs() {
        val fm = supportFragmentManager
        val tx = fm.beginTransaction()

        val home = MobileBrowseFragment()
        tx.add(R.id.main_fragment_container, home, TAG_HOME)

        val shows = MobileShowGridFragment.newInstance(MobileShowGridFragment.Mode.SHOWS)
        tx.add(R.id.main_fragment_container, shows, TAG_SHOWS).hide(shows)

        val podcasts = MobileShowGridFragment.newInstance(MobileShowGridFragment.Mode.PODCASTS)
        tx.add(R.id.main_fragment_container, podcasts, TAG_PODCASTS).hide(podcasts)

        tx.commit()

        wireMobileBottomNav()
    }

    private fun wireMobileBottomNav() {
        val nav = findViewById<BottomNavigationView>(R.id.bottom_nav) ?: return
        nav.visibility = View.VISIBLE

        // Edge-to-edge is on for phones, so the nav would otherwise sit under the
        // gesture pill / 3-button bar. Pad it down by the bottom system-bar inset.
        // Capture the base padding once so repeated inset passes don't accumulate.
        val basePadBottom = nav.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(nav) { v, insets ->
            val bottom = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom
            v.updatePadding(bottom = basePadBottom + bottom)
            insets
        }

        nav.setOnItemSelectedListener { item ->
            val tag = when (item.itemId) {
                R.id.nav_home -> TAG_HOME
                R.id.nav_shows -> TAG_SHOWS
                R.id.nav_podcasts -> TAG_PODCASTS
                else -> return@setOnItemSelectedListener false
            }
            showOnlyMobileTab(tag)
            true
        }
        nav.setOnItemReselectedListener { /* no-op for now; could scroll to top */ }
    }

    private fun showOnlyMobileTab(activeTag: String) {
        val fm = supportFragmentManager
        val tx = fm.beginTransaction()
        listOf(TAG_HOME, TAG_SHOWS, TAG_PODCASTS).forEach { tag ->
            val frag = fm.findFragmentByTag(tag) ?: return@forEach
            if (tag == activeTag) tx.show(frag) else tx.hide(frag)
        }
        tx.commit()
    }

    private fun checkForUpdate() {
        launch {
            val update = UpdateChecker(this@MainActivity).checkForUpdate()
            if (update != null) {
                showUpdateDialog(update)
            }
        }
    }

    @Suppress("DEPRECATION", "MissingSuperCall")
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // If update overlay is showing, dismiss it
        if (updateOverlay != null) {
            dismissUpdateOverlay()
            return
        }

        // If exit overlay is showing, dismiss it
        if (exitOverlay != null) {
            dismissExitOverlay()
            return
        }

        // On TV, show exit confirmation instead of immediately closing —
        // but only when we're already on the side-menu (headers) view. If the
        // user has dived into a row's cards, Back should slide the side menu
        // back in via Leanback's headers transition, not exit the app.
        if (isTv) {
            val fragment = supportFragmentManager.findFragmentById(R.id.main_fragment_container)
            if (fragment is BrowseFragment) {
                if (!fragment.isShowingHeaders) {
                    fragment.startHeadersTransition(true)
                    return
                }
                showExitConfirmation()
                return
            }
        }

        // On mobile, Back from a secondary tab (Shows / Podcasts) returns to the
        // Home tab first rather than finishing the activity — standard bottom-nav
        // behaviour. Setting selectedItemId fires the listener, which swaps the
        // visible fragment via showOnlyMobileTab.
        if (!isTv) {
            val nav = findViewById<BottomNavigationView>(R.id.bottom_nav)
            if (nav != null && nav.visibility == View.VISIBLE && nav.selectedItemId != R.id.nav_home) {
                nav.selectedItemId = R.id.nav_home
                return
            }
        }

        super.onBackPressed()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if ((keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_ESCAPE)) {
            if (updateOverlay != null) {
                dismissUpdateOverlay()
                return true
            }
            if (exitOverlay != null) {
                dismissExitOverlay()
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun showExitConfirmation() {
        if (exitOverlay != null) return
        cancelPendingHeaderLongPress()

        val density = resources.displayMetrics.density
        fun Int.dp() = (this * density).toInt()

        val rootLayout = findViewById<FrameLayout>(R.id.main_fragment_container)

        // Block focus from reaching views behind the overlay
        val fragmentView = supportFragmentManager.findFragmentById(R.id.main_fragment_container)?.view
        fragmentView?.visibility = View.INVISIBLE

        val overlay = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(0xDD000000.toInt())
            isClickable = true
            isFocusable = true
            descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        }

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = FrameLayout.LayoutParams(
                340.dp(),
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
            background = GradientDrawable().apply {
                setColor(0xE6222230.toInt())
                cornerRadius = 16f * density
            }
            setPadding(32.dp(), 28.dp(), 32.dp(), 24.dp())
        }

        // Logo
        val logo = ImageView(this).apply {
            setImageResource(R.drawable.giant_bomb_logo)
            layoutParams = LinearLayout.LayoutParams(80.dp(), 80.dp()).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = 16.dp()
            }
        }
        panel.addView(logo)

        // Title
        val title = TextView(this).apply {
            text = "Exit Giant Bomb?"
            setTextColor(Color.WHITE)
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 24.dp() }
        }
        panel.addView(title)

        // Buttons row
        val buttonRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val btnStyle = { text: String, filled: Boolean ->
            TextView(this).apply {
                this.text = text
                setTextColor(if (filled) Color.WHITE else 0xCCFFFFFF.toInt())
                textSize = 16f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                isFocusable = true
                isFocusableInTouchMode = true
                setPadding(24.dp(), 12.dp(), 24.dp(), 12.dp())
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = 6.dp()
                    marginEnd = 6.dp()
                }
                background = GradientDrawable().apply {
                    if (filled) {
                        setColor(0xFFE53935.toInt())
                    } else {
                        setColor(0x33FFFFFF)
                    }
                    cornerRadius = 8f * density
                }
                setOnFocusChangeListener { v, hasFocus ->
                    val bg = v.background as? GradientDrawable
                    if (filled) {
                        bg?.setColor(if (hasFocus) 0xFFFF5252.toInt() else 0xFFE53935.toInt())
                    } else {
                        bg?.setColor(if (hasFocus) 0x55FFFFFF else 0x33FFFFFF)
                    }
                }
            }
        }

        val cancelBtn = btnStyle("Cancel", false)
        cancelBtn.setOnClickListener { dismissExitOverlay() }

        val exitBtn = btnStyle("Exit", true)
        exitBtn.setOnClickListener { finish() }

        // Wire focus between buttons and trap it within the dialog
        cancelBtn.id = View.generateViewId()
        exitBtn.id = View.generateViewId()
        cancelBtn.nextFocusRightId = exitBtn.id
        cancelBtn.nextFocusLeftId = exitBtn.id
        cancelBtn.nextFocusUpId = cancelBtn.id
        cancelBtn.nextFocusDownId = cancelBtn.id
        exitBtn.nextFocusLeftId = cancelBtn.id
        exitBtn.nextFocusRightId = cancelBtn.id
        exitBtn.nextFocusUpId = exitBtn.id
        exitBtn.nextFocusDownId = exitBtn.id

        buttonRow.addView(cancelBtn)
        buttonRow.addView(exitBtn)
        panel.addView(buttonRow)

        overlay.addView(panel)
        rootLayout.addView(overlay)
        exitOverlay = overlay

        // Focus Cancel by default so accidental presses don't exit
        cancelBtn.post { cancelBtn.requestFocus() }
    }

    private fun dismissExitOverlay() {
        exitOverlay?.let {
            val rootLayout = findViewById<FrameLayout>(R.id.main_fragment_container)
            rootLayout.removeView(it)
            exitOverlay = null
            // Restore the fragment view
            val fragmentView = supportFragmentManager.findFragmentById(R.id.main_fragment_container)?.view
            fragmentView?.visibility = View.VISIBLE
        }
    }

    private fun showUpdateDialog(update: UpdateChecker.UpdateInfo) {
        if (updateOverlay != null) return
        cancelPendingHeaderLongPress()

        val density = resources.displayMetrics.density
        fun Int.dp() = (this * density).toInt()

        val rootLayout = findViewById<FrameLayout>(R.id.main_fragment_container)

        val overlay = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(0xDD000000.toInt())
            isClickable = true
            isFocusable = true
            descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        }

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = FrameLayout.LayoutParams(
                380.dp(),
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
            background = GradientDrawable().apply {
                setColor(0xE6222230.toInt())
                cornerRadius = 16f * density
            }
            setPadding(32.dp(), 28.dp(), 32.dp(), 24.dp())
        }

        // Title
        val title = TextView(this).apply {
            text = "Update Available"
            setTextColor(Color.WHITE)
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }
        panel.addView(title)

        // Version info
        val versionText = TextView(this).apply {
            text = "Version ${update.versionName} is ready to install"
            setTextColor(0xCCFFFFFF.toInt())
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(0, 8.dp(), 0, 0)
        }
        panel.addView(versionText)

        // Release notes (truncated)
        if (update.releaseNotes.isNotEmpty()) {
            val notes = TextView(this).apply {
                text = update.releaseNotes.lines()
                    .filter { it.isNotBlank() }
                    .take(5)
                    .joinToString("\n")
                setTextColor(0x99FFFFFF.toInt())
                textSize = 12f
                maxLines = 5
                setPadding(0, 12.dp(), 0, 0)
            }
            panel.addView(notes)
        }

        // Progress bar (hidden initially)
        val progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = 16.dp()
                bottomMargin = 4.dp()
            }
            max = 100
            progress = 0
            visibility = View.GONE
            progressDrawable.setColorFilter(
                0xFFE53935.toInt(), android.graphics.PorterDuff.Mode.SRC_IN
            )
        }
        panel.addView(progressBar)

        // Progress text (hidden initially)
        val progressText = TextView(this).apply {
            text = "Downloading..."
            setTextColor(0xCCFFFFFF.toInt())
            textSize = 13f
            gravity = Gravity.CENTER
            visibility = View.GONE
        }
        panel.addView(progressText)

        // Buttons row
        val buttonRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 20.dp() }
        }

        val makeBtnStyle = { text: String, filled: Boolean ->
            TextView(this).apply {
                this.text = text
                setTextColor(if (filled) Color.WHITE else 0xCCFFFFFF.toInt())
                textSize = 16f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                isFocusable = true
                isFocusableInTouchMode = true
                setPadding(24.dp(), 12.dp(), 24.dp(), 12.dp())
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = 6.dp()
                    marginEnd = 6.dp()
                }
                background = GradientDrawable().apply {
                    if (filled) setColor(0xFFE53935.toInt()) else setColor(0x33FFFFFF)
                    cornerRadius = 8f * density
                }
                setOnFocusChangeListener { v, hasFocus ->
                    val bg = v.background as? GradientDrawable
                    if (filled) {
                        bg?.setColor(if (hasFocus) 0xFFFF5252.toInt() else 0xFFE53935.toInt())
                    } else {
                        bg?.setColor(if (hasFocus) 0x55FFFFFF else 0x33FFFFFF)
                    }
                }
            }
        }

        val laterBtn = makeBtnStyle("Later", false)
        laterBtn.setOnClickListener { dismissUpdateOverlay() }

        val updateBtn = makeBtnStyle("Update", true)
        updateBtn.setOnClickListener {
            // Switch to download mode
            buttonRow.visibility = View.GONE
            progressBar.visibility = View.VISIBLE
            progressText.visibility = View.VISIBLE

            launch {
                val checker = UpdateChecker(this@MainActivity)
                val apkFile = checker.downloadUpdate(update.downloadUrl) { percent ->
                    progressBar.progress = percent
                    progressText.text = "Downloading... $percent%"
                }
                if (apkFile != null) {
                    progressText.text = "Installing..."
                    try {
                        startActivity(checker.getInstallIntent(apkFile))
                        // Dismiss overlay - system installer takes over
                        dismissUpdateOverlay()
                    } catch (e: Exception) {
                        Toast.makeText(this@MainActivity,
                            "Could not install update. You may need to enable 'Install unknown apps' in Settings.",
                            Toast.LENGTH_LONG).show()
                        dismissUpdateOverlay()
                    }
                } else {
                    Toast.makeText(this@MainActivity,
                        "Download failed. Please try again later.",
                        Toast.LENGTH_LONG).show()
                    dismissUpdateOverlay()
                }
            }
        }

        // Wire focus
        laterBtn.id = View.generateViewId()
        updateBtn.id = View.generateViewId()
        laterBtn.nextFocusRightId = updateBtn.id
        laterBtn.nextFocusLeftId = updateBtn.id
        laterBtn.nextFocusUpId = laterBtn.id
        laterBtn.nextFocusDownId = laterBtn.id
        updateBtn.nextFocusLeftId = laterBtn.id
        updateBtn.nextFocusRightId = laterBtn.id
        updateBtn.nextFocusUpId = updateBtn.id
        updateBtn.nextFocusDownId = updateBtn.id

        buttonRow.addView(laterBtn)
        buttonRow.addView(updateBtn)
        panel.addView(buttonRow)

        overlay.addView(panel)
        rootLayout.addView(overlay)
        updateOverlay = overlay

        // Focus Update button by default
        updateBtn.post { updateBtn.requestFocus() }
    }

    private fun dismissUpdateOverlay() {
        updateOverlay?.let {
            val rootLayout = findViewById<FrameLayout>(R.id.main_fragment_container)
            rootLayout.removeView(it)
            updateOverlay = null
        }
    }

    @Deprecated("Use Activity Result API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == SETUP_REQUEST && resultCode == Activity.RESULT_OK) {
            if (isTv) {
                val tvFrag = supportFragmentManager.findFragmentById(R.id.main_fragment_container)
                (tvFrag as? BrowseFragment)?.loadContent()
            } else {
                // Mobile path now has three fragments in the container; look up
                // Home by tag rather than findFragmentById, which returns
                // whichever fragment was attached most recently.
                val home = supportFragmentManager.findFragmentByTag(TAG_HOME)
                (home as? MobileBrowseFragment)?.loadContent()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cancel()
    }
}
