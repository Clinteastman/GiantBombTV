package com.giantbomb.tv.util

/**
 * Friendly publishDate formatting used by both the mobile and TV browse paths.
 * Accepts both legacy "YYYY-MM-DD HH:MM:SS" and ISO 8601 "YYYY-MM-DDTHH:MM:SS[.sss]Z"
 * and renders them as "Mmm d, yyyy" — e.g. "May 8, 2026". Falls back to the
 * original string if anything looks off so we never show a worse value than
 * the raw API response.
 */
object DateFormat {
    private val MONTHS = arrayOf(
        "Jan", "Feb", "Mar", "Apr", "May", "Jun",
        "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"
    )

    fun formatPublishDate(dateStr: String): String {
        if (dateStr.isEmpty()) return dateStr
        return try {
            // Replace 'T' with space so ISO 8601 falls into the same split path
            // as the legacy GB date format. Then take the date part before any
            // time / timezone bits.
            val parts = dateStr.replace('T', ' ').split(" ")[0].split("-")
            if (parts.size == 3) {
                val month = parts[1].toIntOrNull()?.let { MONTHS.getOrNull(it - 1) } ?: parts[1]
                val day = parts[2].toIntOrNull()?.toString() ?: parts[2]
                "$month $day, ${parts[0]}"
            } else {
                dateStr
            }
        } catch (_: Exception) {
            dateStr
        }
    }
}
