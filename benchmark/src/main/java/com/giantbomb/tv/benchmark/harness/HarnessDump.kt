package com.giantbomb.tv.benchmark.harness

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Captures whatever is on screen now (screenshot plus hierarchy), without
 * touching the app. Used by scripts/harness for ad-hoc captures and debugging:
 *
 *   adb shell am instrument -w -e class com.giantbomb.tv.benchmark.harness.HarnessDump \
 *     -e harness true -e name my_shot com.giantbomb.tv.benchmark/androidx.test.runner.AndroidJUnitRunner
 */
@RunWith(AndroidJUnit4::class)
class HarnessDump {
    @Test
    fun dump() {
        HarnessIo.assumeHarnessRun()
        HarnessIo.capture(HarnessIo.arg("name", "dump")!!)
    }
}
