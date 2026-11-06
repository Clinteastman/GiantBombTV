package com.giantbomb.tv.benchmark.harness

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Configurator
import androidx.test.uiautomator.UiDevice
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream

/**
 * File output for the screen harness (scripts/harness). Everything lands in the
 * test app's external media dir so the host can `adb pull` it without root.
 *
 * Privacy: the setup screen shows the saved API key in an EditText. Every
 * EditText is blanked in the hierarchy dump and painted over in the screenshot
 * before anything is written, so the key never reaches disk on the host.
 */
internal object HarnessIo {
    val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    val device: UiDevice by lazy {
        // The countdown cards tick every second, so the UI is never "idle".
        // Without this every query would stall for the default 10 s.
        Configurator.getInstance().waitForIdleTimeout = 300
        Configurator.getInstance().actionAcknowledgmentTimeout = 300
        UiDevice.getInstance(instrumentation)
    }

    fun outDir(): File {
        val base = instrumentation.context.externalMediaDirs.first()
        return File(base, "harness").apply { mkdirs() }
    }

    fun arg(name: String, default: String? = null): String? =
        InstrumentationRegistry.getArguments().getString(name) ?: default

    /** Writes `<name>.png` and `<name>.xml` and returns the png file. */
    fun capture(name: String): File {
        val dir = outDir()
        // Screenshot first: short-lived UI (the player controls hide after
        // 3 s) must be in the picture; the dump that follows is slower.
        val shot = instrumentation.uiAutomation.takeScreenshot()
            ?: error("Screenshot failed for $name")
        val xml = dumpHierarchy()
        File(dir, "$name.xml").writeText(redactXml(xml))
        val masked = maskEditTexts(shot)
        FileOutputStream(File(dir, "$name.png")).use {
            masked.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        return File(dir, "$name.png")
    }

    fun dumpHierarchy(): String {
        val out = ByteArrayOutputStream()
        device.dumpWindowHierarchy(out)
        return out.toString("UTF-8")
    }

    private val editTextNode = Regex("""<node[^>]*class="android\.widget\.EditText"[^>]*>""")
    private val textAttr = Regex(""" text="[^"]*"""")

    fun redactXml(xml: String): String = editTextNode.replace(xml) { m ->
        textAttr.replace(m.value, """ text="[redacted]"""")
    }

    private fun maskEditTexts(src: Bitmap): Bitmap {
        val fields = device.findObjects(By.clazz("android.widget.EditText"))
        if (fields.isEmpty()) return src
        val bmp = src.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(bmp)
        val fill = Paint().apply { color = Color.rgb(40, 40, 40) }
        val label = Paint().apply {
            color = Color.WHITE
            textSize = 28f
            isAntiAlias = true
        }
        for (f in fields) {
            val r: Rect = f.visibleBounds
            canvas.drawRect(r, fill)
            canvas.drawText("[field hidden by harness]", r.left + 16f, r.exactCenterY() + 10f, label)
        }
        return bmp
    }
}
