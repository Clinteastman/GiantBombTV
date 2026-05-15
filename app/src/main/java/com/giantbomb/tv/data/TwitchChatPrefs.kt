package com.giantbomb.tv.data

import android.webkit.CookieManager
import android.webkit.WebStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val TWITCH_HOSTS = listOf("www.twitch.tv", "twitch.tv", "m.twitch.tv")
private val TWITCH_ORIGINS = TWITCH_HOSTS.map { "https://$it" }

// Process-lived so an opt-out always finishes even if the user immediately
// rotates or backs out of the screen that triggered the toggle. A fragment-
// scoped launch would get cancelled mid-clear and leave cookies on disk.
private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

/**
 * Flip the show-Twitch-chat pref. When turning it off, also clear any
 * twitch.tv cookies and DOM storage the chat embed left behind, so opting
 * out actually drops the tracking state. Returns the new pref value.
 *
 * The cookie wipe runs on Dispatchers.IO; the DOM-storage delete runs back
 * on Main because WebView APIs (including WebStorage) must be called from
 * the thread that initialised the WebView, which is the UI thread.
 *
 * Shared between TV and mobile so the two surfaces can't drift.
 */
fun PrefsManager.toggleTwitchChatPref(): Boolean {
    val nowShown = !showTwitchChat
    showTwitchChat = nowShown
    if (!nowShown) {
        cleanupScope.launch {
            withContext(Dispatchers.IO) { clearTwitchChatCookies() }
            clearTwitchChatDomStorage()
        }
    }
    return nowShown
}

private const val COOKIE_EXPIRY_HEADER =
    "Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT"

private fun clearTwitchChatCookies() {
    val cookieManager = CookieManager.getInstance()
    for (url in TWITCH_ORIGINS) {
        val cookieStr = cookieManager.getCookie(url) ?: continue
        val host = url.removePrefix("https://")
        for (cookie in cookieStr.split(";")) {
            val name = cookie.substringBefore("=").trim()
            if (name.isEmpty()) continue
            // Cover both the wildcard-domain variant (Set-Cookie with Domain=.twitch.tv)
            // and host-only cookies that were set without a Domain attribute. Pair
            // Max-Age=0 with an explicit past Expires for older WebView builds
            // (notably some Fire TV pre-Chromium-parity versions) that don't
            // honour Max-Age via CookieManager.setCookie.
            cookieManager.setCookie(url, "$name=; $COOKIE_EXPIRY_HEADER; Path=/; Domain=.twitch.tv")
            cookieManager.setCookie(url, "$name=; $COOKIE_EXPIRY_HEADER; Path=/; Domain=$host")
            cookieManager.setCookie(url, "$name=; $COOKIE_EXPIRY_HEADER; Path=/")
        }
    }
    cookieManager.flush()
}

private fun clearTwitchChatDomStorage() {
    val storage = WebStorage.getInstance()
    for (origin in TWITCH_ORIGINS) {
        storage.deleteOrigin(origin)
    }
}
