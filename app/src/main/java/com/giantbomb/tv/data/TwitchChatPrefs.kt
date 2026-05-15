package com.giantbomb.tv.data

import android.webkit.CookieManager
import android.webkit.WebStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val TWITCH_HOSTS = listOf("www.twitch.tv", "twitch.tv", "m.twitch.tv")
private val TWITCH_ORIGINS = TWITCH_HOSTS.map { "https://$it" }

/**
 * Flip the show-Twitch-chat pref. When turning it off, also clear any
 * twitch.tv cookies and DOM storage the chat embed left behind, so opting
 * out actually drops the tracking state. Returns the new pref value.
 *
 * The clear runs through [scope] (cookies on IO, DOM storage on Main per
 * WebStorage's threading contract) so the UI thread isn't blocked by disk
 * I/O when the user toggles after a long chat session.
 *
 * Shared between TV and mobile so the two surfaces can't drift.
 */
fun PrefsManager.toggleTwitchChatPref(scope: CoroutineScope): Boolean {
    val nowShown = !showTwitchChat
    showTwitchChat = nowShown
    if (!nowShown) {
        scope.launch {
            withContext(Dispatchers.IO) { clearTwitchChatCookies() }
            // WebStorage APIs are documented as main-thread-only.
            clearTwitchChatDomStorage()
        }
    }
    return nowShown
}

private fun clearTwitchChatCookies() {
    val cookieManager = CookieManager.getInstance()
    for (url in TWITCH_ORIGINS) {
        val cookieStr = cookieManager.getCookie(url) ?: continue
        val host = url.removePrefix("https://")
        for (cookie in cookieStr.split(";")) {
            val name = cookie.substringBefore("=").trim()
            if (name.isEmpty()) continue
            // Cover both the wildcard-domain variant (Set-Cookie with Domain=.twitch.tv)
            // and host-only cookies that were set without a Domain attribute.
            cookieManager.setCookie(url, "$name=; Max-Age=0; Path=/; Domain=.twitch.tv")
            cookieManager.setCookie(url, "$name=; Max-Age=0; Path=/; Domain=$host")
            cookieManager.setCookie(url, "$name=; Max-Age=0; Path=/")
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
