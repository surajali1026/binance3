package com.example.config

import com.example.BuildConfig

/**
 * Central application version & GitHub release configuration for Binance Pro.
 */
object AppVersionConfig {
    val CURRENT_VERSION: String = BuildConfig.VERSION_NAME
    const val GITHUB_OWNER: String = "surajali1026"
    const val GITHUB_REPO: String = "binance3"
    const val LATEST_RELEASE_URL: String =
        "https://api.github.com/repos/$GITHUB_OWNER/$GITHUB_REPO/releases/latest"
    const val RELEASES_PAGE_URL: String =
        "https://github.com/$GITHUB_OWNER/$GITHUB_REPO/releases"

    /**
     * Normalizes version tags like "v1.2.3-beta.1" or "release-2.0" into integer parts [1, 2, 3].
     */
    fun normalizeVersionParts(raw: String): List<Int> {
        val cleaned = raw.trim()
            .removePrefix("v")
            .removePrefix("V")
            .substringBefore('-')
            .substringBefore('+')
            .trim()
        if (cleaned.isEmpty()) return emptyList()
        return cleaned.split('.')
            .mapNotNull { segment ->
                val digits = segment.filter { it.isDigit() }
                digits.toIntOrNull()
            }
    }

    /**
     * Returns true strictly when [remoteTag] represents a newer semantic version than [localVersion].
     */
    fun isNewerVersion(remoteTag: String, localVersion: String = CURRENT_VERSION): Boolean {
        val remoteParts = normalizeVersionParts(remoteTag)
        val localParts = normalizeVersionParts(localVersion)
        if (remoteParts.isEmpty()) return false
        if (localParts.isEmpty()) return true
        val maxLen = maxOf(remoteParts.size, localParts.size)
        for (i in 0 until maxLen) {
            val r = remoteParts.getOrElse(i) { 0 }
            val l = localParts.getOrElse(i) { 0 }
            if (r > l) return true
            if (r < l) return false
        }
        // Same numeric parts: stable release beats pre-release suffix on local
        val localHasPreRelease = localVersion.contains('-')
        val remoteHasPreRelease = remoteTag.contains('-')
        return localHasPreRelease && !remoteHasPreRelease
    }
}
