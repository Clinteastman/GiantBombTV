package com.giantbomb.tv.data

import android.webkit.CookieManager
import android.webkit.WebStorage

private val TWITCH_ORIGINS = listOf(
    "https://www.twitch.tv",
    "https://twitch.tv",
    "https://m.twitch.tv",
)

/**
 * Flip the show-Twitch-chat pref. When turning it off, also clear any
 * twitch.tv cookies and DOM storage the chat embed left behind, so opting
 * out actually drops the tracking state. Returns the new pref value.
 *
 * Shared between TV and mobile so the two surfaces can't drift.
 */
fun PrefsManager.toggleTwitchChatPref(): Boolean {
    val nowShown = !showTwitchChat
    showTwitchChat = nowShown
    if (!nowShown) clearTwitchChatStorage()
    return nowShown
}

private fun clearTwitchChatStorage() {
    val cookieManager = CookieManager.getInstance()
    for (url in TWITCH_ORIGINS) {
        val cookieStr = cookieManager.getCookie(url) ?: continue
        for (cookie in cookieStr.split(";")) {
            val name = cookie.substringBefore("=").trim()
            if (name.isNotEmpty()) {
                cookieManager.setCookie(url, "$name=; Max-Age=0; Path=/; Domain=.twitch.tv")
            }
        }
    }
    cookieManager.flush()
    val storage = WebStorage.getInstance()
    for (origin in TWITCH_ORIGINS) {
        storage.deleteOrigin(origin)
    }
}
