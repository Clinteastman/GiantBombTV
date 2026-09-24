package com.giantbomb.tv.benchmark

import android.view.KeyEvent
import androidx.benchmark.macro.ExperimentalMetricApi
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.regex.Pattern

private const val TARGET_PACKAGE = "com.giantbomb.tv"
private const val BROWSE_TIMEOUT_MS = 10_000L
// Match the list only once it has rows, not when the empty container inflates
// while content is still loading. Phone: browse_recycler. TV: Leanback's rows
// grid (container_list), whose library id merges into the app package.
private val BROWSE_SELECTOR = By.res(
    Pattern.compile("${Pattern.quote(TARGET_PACKAGE)}:id/(browse_recycler|container_list)")
).hasChild(By.pkg(TARGET_PACKAGE))

/** The browse screen that was found: phone RecyclerView or Leanback TV frame. */
private sealed interface BrowseUi {
    data class Phone(val list: UiObject2) : BrowseUi
    object Tv : BrowseUi
}

/**
 * Waits for the real browse screen. Fails loudly instead of silently passing
 * when the app is sitting on onboarding (no API key saved on the device), so a
 * profile or benchmark run can never report success without browsing.
 */
private fun MacrobenchmarkScope.awaitBrowse(): BrowseUi {
    // Wait for whichever layout this device uses, in one poll.
    val found = device.wait(Until.findObject(BROWSE_SELECTOR), BROWSE_TIMEOUT_MS)
    if (found != null) {
        return if (found.resourceName.endsWith(":id/browse_recycler")) {
            BrowseUi.Phone(found)
        } else {
            BrowseUi.Tv
        }
    }
    error(
        "Browse screen not shown. Open the app on this device and enter a " +
            "Giant Bomb API key before running benchmarks or generating a profile."
    )
}

private fun MacrobenchmarkScope.scrollBrowse(ui: BrowseUi) {
    when (ui) {
        is BrowseUi.Phone -> {
            ui.list.fling(Direction.DOWN)
            device.waitForIdle()
            ui.list.fling(Direction.UP)
        }
        BrowseUi.Tv -> {
            // Leave the side menu, then walk down and across the rows.
            device.pressKeyCode(KeyEvent.KEYCODE_DPAD_RIGHT)
            repeat(6) { device.pressDPadDown(); device.waitForIdle() }
            repeat(4) { device.pressDPadRight(); device.waitForIdle() }
            repeat(6) { device.pressDPadUp(); device.waitForIdle() }
        }
    }
    device.waitForIdle()
}

@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun startupAndBrowse() = rule.collect(
        packageName = TARGET_PACKAGE,
        includeInStartupProfile = true
    ) {
        pressHome()
        startActivityAndWait()
        scrollBrowse(awaitBrowse())
    }
}

@RunWith(AndroidJUnit4::class)
class StartupAndScrollBenchmark {
    @get:Rule
    val rule = MacrobenchmarkRule()

    @Test
    fun coldStartup() = rule.measureRepeated(
        packageName = TARGET_PACKAGE,
        metrics = listOf(StartupTimingMetric()),
        iterations = 5,
        startupMode = StartupMode.COLD,
        setupBlock = { pressHome() }
    ) {
        startActivityAndWait()
        awaitBrowse()
    }

    @OptIn(ExperimentalMetricApi::class)
    @Test
    fun homeScroll() = rule.measureRepeated(
        packageName = TARGET_PACKAGE,
        metrics = listOf(FrameTimingMetric()),
        iterations = 5,
        setupBlock = {
            pressHome()
            startActivityAndWait()
            // Wait for browse here so load time isn't counted as scroll frames.
            awaitBrowse()
        }
    ) {
        scrollBrowse(awaitBrowse())
    }
}
