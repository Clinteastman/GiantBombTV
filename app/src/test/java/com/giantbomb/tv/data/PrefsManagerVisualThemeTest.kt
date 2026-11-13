package com.giantbomb.tv.data

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Default theme rules: a fresh install (empty prefs file) starts in Simple,
 * an install from before Simple became the default (prefs already hold data
 * but no theme) keeps Frosted, and a saved choice is never changed.
 */
class PrefsManagerVisualThemeTest {

    @Test
    fun `fresh install reads Simple`() {
        val prefs = FakeSharedPreferences()
        assertEquals(PrefsManager.THEME_SIMPLE, PrefsManager(prefs).visualTheme)
    }

    @Test
    fun `fresh install stays Simple after the user signs in`() {
        val prefs = FakeSharedPreferences()
        PrefsManager(prefs).visualTheme
        PrefsManager(prefs).apiKey = "test-key"
        assertEquals(PrefsManager.THEME_SIMPLE, PrefsManager(prefs).visualTheme)
    }

    @Test
    fun `existing install with no saved theme reads Frosted`() {
        val prefs = FakeSharedPreferences(mutableMapOf("api_key" to "test-key"))
        assertEquals(PrefsManager.THEME_FROSTED, PrefsManager(prefs).visualTheme)
    }

    @Test
    fun `existing install keeps Frosted on later launches`() {
        val prefs = FakeSharedPreferences(mutableMapOf("show_twitch_chat" to false))
        repeat(3) {
            assertEquals(PrefsManager.THEME_FROSTED, PrefsManager(prefs).visualTheme)
        }
        assertEquals(PrefsManager.THEME_FROSTED, prefs.getString("visual_theme", null))
    }

    @Test
    fun `existing install keeps Frosted after other prefs change`() {
        val prefs = FakeSharedPreferences(mutableMapOf("api_key" to "test-key"))
        PrefsManager(prefs).visualTheme
        PrefsManager(prefs).apiKey = null
        assertEquals(PrefsManager.THEME_FROSTED, PrefsManager(prefs).visualTheme)
    }

    @Test
    fun `saved theme of each value is kept`() {
        for (theme in PrefsManager.VISUAL_THEMES) {
            val prefs = FakeSharedPreferences(
                mutableMapOf("api_key" to "test-key", "visual_theme" to theme)
            )
            assertEquals(theme, PrefsManager(prefs).visualTheme)
            assertEquals(theme, prefs.getString("visual_theme", null))
        }
    }

    @Test
    fun `saved theme of each value is kept on an otherwise empty prefs file`() {
        for (theme in PrefsManager.VISUAL_THEMES) {
            val prefs = FakeSharedPreferences(mutableMapOf("visual_theme" to theme))
            assertEquals(theme, PrefsManager(prefs).visualTheme)
        }
    }

    @Test
    fun `picking a theme saves it`() {
        val prefs = FakeSharedPreferences(mutableMapOf("api_key" to "test-key"))
        val manager = PrefsManager(prefs)
        manager.visualTheme = PrefsManager.THEME_NEON
        assertEquals(PrefsManager.THEME_NEON, manager.visualTheme)
    }

    @Test
    fun `unknown saved value reads as Simple and is not overwritten`() {
        val prefs = FakeSharedPreferences(
            mutableMapOf("api_key" to "test-key", "visual_theme" to "holographic")
        )
        assertEquals(PrefsManager.THEME_SIMPLE, PrefsManager(prefs).visualTheme)
        assertEquals("holographic", prefs.getString("visual_theme", null))
    }

    @Test
    fun `setting an unknown value saves Simple`() {
        val prefs = FakeSharedPreferences()
        val manager = PrefsManager(prefs)
        manager.visualTheme = "holographic"
        assertEquals(PrefsManager.THEME_SIMPLE, prefs.getString("visual_theme", null))
    }

    @Test
    fun `label for an unknown value is Simple`() {
        assertEquals("Simple", PrefsManager.visualThemeLabel("holographic"))
    }
}

/** Minimal in-memory SharedPreferences for JVM unit tests. */
private class FakeSharedPreferences(
    private val values: MutableMap<String, Any?> = mutableMapOf(),
) : SharedPreferences {

    override fun getAll(): MutableMap<String, *> = values.toMutableMap()
    override fun getString(key: String, defValue: String?): String? =
        values[key] as String? ?: defValue
    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? =
        values[key] as MutableSet<String>? ?: defValues
    override fun getInt(key: String, defValue: Int): Int = values[key] as Int? ?: defValue
    override fun getLong(key: String, defValue: Long): Long = values[key] as Long? ?: defValue
    override fun getFloat(key: String, defValue: Float): Float = values[key] as Float? ?: defValue
    override fun getBoolean(key: String, defValue: Boolean): Boolean =
        values[key] as Boolean? ?: defValue
    override fun contains(key: String): Boolean = key in values
    override fun edit(): SharedPreferences.Editor = Editor()
    override fun registerOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?
    ) = Unit
    override fun unregisterOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?
    ) = Unit

    private inner class Editor : SharedPreferences.Editor {
        private val pending = mutableMapOf<String, Any?>()
        private val removed = mutableSetOf<String>()
        private var clear = false

        override fun putString(key: String, value: String?) = put(key, value)
        override fun putStringSet(key: String, values: MutableSet<String>?) = put(key, values)
        override fun putInt(key: String, value: Int) = put(key, value)
        override fun putLong(key: String, value: Long) = put(key, value)
        override fun putFloat(key: String, value: Float) = put(key, value)
        override fun putBoolean(key: String, value: Boolean) = put(key, value)
        override fun remove(key: String): SharedPreferences.Editor { removed += key; return this }
        override fun clear(): SharedPreferences.Editor { clear = true; return this }
        override fun commit(): Boolean {
            apply()
            return true
        }
        override fun apply() {
            if (clear) values.clear()
            removed.forEach { values.remove(it) }
            // Like the real editor, a null value removes the key.
            pending.forEach { (key, value) ->
                if (value == null) values.remove(key) else values[key] = value
            }
        }

        private fun put(key: String, value: Any?): SharedPreferences.Editor { pending[key] = value; return this }
    }
}
