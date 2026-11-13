package com.giantbomb.tv.benchmark.harness

import android.os.SystemClock
import android.view.KeyEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import org.junit.Test
import org.junit.runner.RunWith
import java.util.regex.Pattern

private const val PKG = "com.giantbomb.tv"
private fun res(id: String): BySelector = By.res(PKG, id)
private fun resAny(vararg ids: String): BySelector =
    By.res(Pattern.compile("${Pattern.quote(PKG)}:id/(${ids.joinToString("|")})"))

/**
 * The fixed screen list for scripts/harness. Each call starts the app from
 * scratch, walks to one screen the same way every time, puts focus on a known
 * element and writes `<name>.png` + `<name>.xml` (see [HarnessIo]).
 *
 *   adb shell am instrument -w -e class com.giantbomb.tv.benchmark.harness.ScreenTour#capture \
 *     -e harness true -e screen tv_home -e name simple_tv_home \
 *     com.giantbomb.tv.benchmark/androidx.test.runner.AndroidJUnitRunner
 *
 * The theme, font scale and API key are set from the host before each call.
 */
@RunWith(AndroidJUnit4::class)
class ScreenTour {
    private val d get() = HarnessIo.device

    @Test
    fun capture() {
        HarnessIo.assumeHarnessRun()
        val screen = HarnessIo.arg("screen") ?: error("Pass -e screen <name>")
        val name = HarnessIo.arg("name", screen)!!
        when (screen) {
            "tv_home" -> tvHome()
            "tv_show_grid" -> tvShowGrid()
            "tv_detail" -> tvDetail()
            "tv_playback" -> tvPlayback()
            "tv_search" -> tvSearch()
            "tv_downloads" -> tvSettingsCard("Downloads", open = true)
            "tv_settings" -> tvSettings()
            "tv_theme_picker" -> tvThemePicker()
            "tv_setup" -> { tvSettingsCard("API Key", open = true); hideKeyboard() }
            "phone_home" -> phoneHome()
            "phone_show_grid" -> phoneShowGrid()
            // The phone app has no detail screen: a tap on a video card opens
            // the player straight away, so the phone list captures that.
            "phone_playback" -> phonePlayback()
            "phone_downloads" -> phoneDownloads()
            else -> error("Unknown screen '$screen'")
        }
        HarnessIo.capture(name)
    }

    /**
     * Browses the home rows the same way the scroll benchmark does, then saves
     * `dumpsys meminfo` to the harness folder (read by scripts/harness/perf.py).
     * A Macrobenchmark memory trace needs more RAM than a TV emulator has.
     */
    @Test
    fun browse() {
        HarnessIo.assumeHarnessRun()
        launch()
        repeat(2) {
            if (d.hasObject(res("browse_recycler"))) {
                d.findObject(res("browse_recycler"))?.fling(Direction.DOWN)
                pause(800)
                d.findObject(res("browse_recycler"))?.fling(Direction.UP)
                pause(800)
            } else {
                key(KeyEvent.KEYCODE_DPAD_RIGHT, 300)
                repeat(6) { key(KeyEvent.KEYCODE_DPAD_DOWN, 300) }
                repeat(4) { key(KeyEvent.KEYCODE_DPAD_RIGHT, 300) }
                repeat(6) { key(KeyEvent.KEYCODE_DPAD_UP, 300) }
            }
        }
        pause(2_000) // let image loads settle
        // Read memory here: the app does not outlive the instrumentation run.
        val meminfo = d.executeShellCommand("dumpsys meminfo $PKG")
        java.io.File(HarnessIo.outDir(), "meminfo.txt").writeText(meminfo)
    }

    // ---- shared -----------------------------------------------------------

    private fun pause(ms: Long) = SystemClock.sleep(ms)

    private fun launch() {
        d.executeShellCommand("am force-stop $PKG")
        d.executeShellCommand("am start -W -n $PKG/.MainActivity")
        // Any loaded row: the top rows can be live/upcoming cards, with video
        // cards further down.
        val ok = d.wait(
            Until.hasObject(resAny("video_title", "small_title", "card_title", "section_title", "upcoming_title")),
            25_000
        )
        check(ok) {
            "No video cards after launch. Is a working API key saved on this device? " +
                "(scripts/harness/harness.py seed-key)"
        }
        // Let the first images and the backdrop land.
        pause(2_500)
    }

    private fun focused(): UiObject2? = d.findObject(By.focused(true))

    private fun waitFor(sel: BySelector, ms: Long, what: String) {
        check(d.wait(Until.hasObject(sel), ms)) { "Timed out waiting for $what" }
    }

    private fun key(code: Int, settleMs: Long = 450) {
        d.pressKeyCode(code)
        pause(settleMs)
    }

    // ---- TV ---------------------------------------------------------------

    private fun focusedHeader(): String? =
        focused()?.findObject(res("row_header"))?.text
            ?: focused()?.takeIf { it.resourceName?.endsWith(":id/row_header") == true }?.text

    private fun headerMatches(text: String?, prefix: String): Boolean {
        val t = text ?: return false
        return t.replace("☆", "").replace("★", "").trim()
            .startsWith(prefix, ignoreCase = true)
    }

    /** Puts focus on the side-menu header that starts with [prefix]. */
    private fun tvHeader(prefix: String) {
        repeat(3) { if (focusedHeader() == null) key(KeyEvent.KEYCODE_DPAD_LEFT, 600) }
        checkNotNull(focusedHeader()) { "Could not reach the row headers" }
        repeat(60) {
            if (headerMatches(focusedHeader(), prefix)) return
            // Far from the target (not on screen yet): move in bursts, since
            // each focus query costs a few hundred ms. Rows only go down from
            // the top after a fresh launch, so a burst can't skip past it
            // unless it is on screen, where we step one at a time.
            val onScreen = d.findObjects(res("row_header")).any { headerMatches(it.text, prefix) }
            val steps = if (onScreen) 1 else 4
            repeat(steps) { d.pressKeyCode(KeyEvent.KEYCODE_DPAD_DOWN); pause(120) }
            pause(300)
        }
        error("No row header starting with '$prefix'")
    }

    /** From a header, moves into its row: focus lands on the first card. */
    private fun tvEnterRow() {
        key(KeyEvent.KEYCODE_DPAD_RIGHT, 900)
        check(focusedHeader() == null) { "Focus stayed on the headers" }
    }

    private fun tvHome() {
        launch()
        tvHeader("Recent Videos")
        tvEnterRow()
        pause(1_200) // backdrop crossfade
    }

    private fun tvShowGrid() {
        launch()
        tvHeader("Browse Shows")
        tvEnterRow()
        key(KeyEvent.KEYCODE_DPAD_CENTER, 0)
        waitFor(res("browse_grid"), 15_000, "the show grid")
        waitFor(resAny("card_title", "card_thumbnail"), 15_000, "show episodes")
        pause(2_500)
    }

    private fun openFirstRecentVideo() {
        launch()
        tvHeader("Recent Videos")
        tvEnterRow()
        key(KeyEvent.KEYCODE_DPAD_CENTER, 0)
        waitFor(By.clazz("android.widget.Button").pkg(PKG), 15_000, "the detail buttons")
        pause(2_000)
    }

    private fun tvDetail() = openFirstRecentVideo()

    private fun tvPlayback() {
        openFirstRecentVideo()
        // Focus starts on Watch Now / Resume.
        key(KeyEvent.KEYCODE_DPAD_CENTER, 0)
        waitFor(res("exo_content_frame"), 20_000, "the player")
        pause(7_000) // first segments
        key(KeyEvent.KEYCODE_MEDIA_PAUSE, 800) // freeze the frame
        key(KeyEvent.KEYCODE_DPAD_DOWN, 700) // show the controls overlay
    }

    private fun tvSearch() {
        launch()
        tvHeader("") // any header: top of the list after launch
        // Up from the first header reaches the search orb in the title bar.
        repeat(3) { key(KeyEvent.KEYCODE_DPAD_UP, 400) }
        key(KeyEvent.KEYCODE_DPAD_CENTER, 0)
        waitFor(res("lb_search_text_editor"), 10_000, "the search screen")
        pause(800)
        // Focus starts on the mic orb; the text field is to its right.
        key(KeyEvent.KEYCODE_DPAD_RIGHT, 800)
        d.executeShellCommand("input text quick%slook")
        pause(600)
        key(KeyEvent.KEYCODE_ENTER, 4_000) // submit: hides the keyboard, loads results
        hideKeyboard()
        key(KeyEvent.KEYCODE_DPAD_DOWN, 1_500) // first result
    }

    private fun keyboardShown(): Boolean =
        d.executeShellCommand("dumpsys input_method").contains("mInputShown=true")

    /** Closes the on-screen keyboard if it is up, so it doesn't cover the screen. */
    private fun hideKeyboard() {
        if (keyboardShown()) key(KeyEvent.KEYCODE_BACK, 800)
    }

    private fun tvSettings() {
        launch()
        tvHeader("Settings")
        tvEnterRow()
        pause(800)
    }

    private fun tvSettingsCard(title: String, open: Boolean) {
        tvSettings()
        repeat(15) {
            if (focused()?.hasObject(By.textStartsWith(title)) == true) {
                if (open) {
                    key(KeyEvent.KEYCODE_DPAD_CENTER, 3_000)
                }
                return
            }
            key(KeyEvent.KEYCODE_DPAD_RIGHT, 450)
        }
        error("No settings card '$title'")
    }

    private fun tvThemePicker() {
        tvSettingsCard("Visual Theme", open = true)
        waitFor(By.text("✓"), 5_000, "the theme picker")
        pause(600)
    }

    // ---- Phone / tablet ---------------------------------------------------

    private fun phoneHome() {
        launch()
        // Fresh launch is already at the top; make sure nothing has focus
        // styling from a key event.
        pause(500)
    }

    private fun phoneShowGrid() {
        launch()
        d.findObject(res("nav_shows"))?.click() ?: error("No Shows tab")
        waitFor(res("show_poster"), 15_000, "the shows tab")
        pause(1_500)
        val first = d.findObject(res("show_poster")) ?: error("No show on the Shows tab")
        first.click()
        // Phones and tablets have their own show page (MobileShowFragment).
        waitFor(res("show_grid"), 15_000, "the show page")
        waitFor(resAny("video_title", "video_thumbnail"), 15_000, "show episodes")
        pause(2_500)
    }

    private fun phonePlayback() {
        launch()
        // First full-size video card (Recent videos); rows above it vary.
        var thumb = d.findObject(res("video_thumbnail"))
        repeat(8) {
            if (thumb == null) {
                d.findObject(res("browse_recycler"))?.scroll(Direction.DOWN, 0.5f)
                pause(700)
                thumb = d.findObject(res("video_thumbnail"))
            }
        }
        (thumb ?: error("No video card")).click()
        waitFor(res("exo_content_frame"), 20_000, "the player")
        pause(7_000) // first segments
        key(KeyEvent.KEYCODE_MEDIA_PAUSE, 800) // freeze the frame
        val w = d.displayWidth
        val h = d.displayHeight
        d.click(w / 2, h / 2) // show the controls overlay
        pause(700)
    }

    private fun phoneDownloads() {
        launch()
        d.findObject(res("nav_downloads"))?.click() ?: error("No Downloads tab")
        pause(2_500)
    }
}
