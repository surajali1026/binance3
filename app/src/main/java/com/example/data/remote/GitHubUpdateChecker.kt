package com.example.data.remote

import com.example.config.AppVersionConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException

data class GitHubReleaseInfo(
    val tagName: String,
    val version: String,
    val releaseName: String,
    val notesSummary: String,
    val downloadUrl: String,
    val htmlUrl: String,
    val publishedAt: String? = null
)

sealed interface UpdateCheckState {
    data object Idle : UpdateCheckState
    data object Checking : UpdateCheckState
    data class UpToDate(val currentVersion: String) : UpdateCheckState
    data class UpdateAvailable(val release: GitHubReleaseInfo) : UpdateCheckState
    data class Error(val message: String) : UpdateCheckState
}

/**
 * Fetches latest GitHub release metadata from surajali1026/binance3 with safe offline/error handling.
 * Opens release/APK download URL via standard browser intent (no dynamic code loading).
 */
class GitHubUpdateChecker(
    private val okHttpClient: OkHttpClient
) {
    private val _state = MutableStateFlow<UpdateCheckState>(UpdateCheckState.Idle)
    val state: StateFlow<UpdateCheckState> = _state.asStateFlow()

    private val _dismissedTag = MutableStateFlow<String?>(null)
    val dismissedTag: StateFlow<String?> = _dismissedTag.asStateFlow()

    @Volatile
    private var hasCheckedOnStartup = false

    suspend fun checkOnStartupIfNeeded() {
        if (hasCheckedOnStartup) return
        hasCheckedOnStartup = true
        checkForUpdates(isManual = false)
    }

    suspend fun checkForUpdates(isManual: Boolean = true): UpdateCheckState {
        if (isManual) {
            _dismissedTag.value = null
        }
        _state.value = UpdateCheckState.Checking

        val result = withContext(Dispatchers.IO) {
            fetchLatestReleaseSafe()
        }

        val nextState = result.fold(
            onSuccess = { release ->
                if (release == null) {
                    if (isManual) {
                        UpdateCheckState.UpToDate(AppVersionConfig.CURRENT_VERSION)
                    } else {
                        UpdateCheckState.Idle
                    }
                } else if (AppVersionConfig.isNewerVersion(release.tagName, AppVersionConfig.CURRENT_VERSION)) {
                    UpdateCheckState.UpdateAvailable(release)
                } else {
                    UpdateCheckState.UpToDate(AppVersionConfig.CURRENT_VERSION)
                }
            },
            onFailure = { err ->
                if (isManual) {
                    UpdateCheckState.Error(err.message ?: "Unable to check GitHub updates right now")
                } else {
                    UpdateCheckState.Idle
                }
            }
        )
        _state.value = nextState
        return nextState
    }

    fun dismissBanner(tagName: String) {
        _dismissedTag.value = tagName
    }

    private fun fetchLatestReleaseSafe(): Result<GitHubReleaseInfo?> {
        val request = Request.Builder()
            .url(AppVersionConfig.LATEST_RELEASE_URL)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "BinancePro-Android/${AppVersionConfig.CURRENT_VERSION}")
            .get()
            .build()

        return try {
            okHttpClient.newCall(request).execute().use { response ->
                when (response.code) {
                    200 -> {
                        val body = response.body?.string().orEmpty()
                        if (body.isBlank()) {
                            Result.success(null)
                        } else {
                            Result.success(parseReleaseJson(body))
                        }
                    }
                    404 -> {
                        // Repository has no published releases yet
                        Result.success(null)
                    }
                    403 -> {
                        Result.failure(IOException("GitHub API rate limit reached. Try again later."))
                    }
                    else -> {
                        Result.failure(IOException("Update check failed (HTTP ${response.code})"))
                    }
                }
            }
        } catch (_: IOException) {
            Result.failure(IOException("Offline or unable to reach GitHub right now"))
        } catch (_: Exception) {
            Result.failure(IOException("Unable to parse release details"))
        }
    }

    companion object {
        internal fun parseReleaseJson(json: String): GitHubReleaseInfo? {
            val obj = JSONObject(json)
            val tagName = obj.optString("tag_name", "").trim()
            if (tagName.isEmpty()) return null
            val htmlUrl = obj.optString("html_url", AppVersionConfig.RELEASES_PAGE_URL)
                .ifBlank { AppVersionConfig.RELEASES_PAGE_URL }
            val releaseName = obj.optString("name", tagName).ifBlank { tagName }
            val rawBody = obj.optString("body", "").trim()
            val publishedAt = obj.optString("published_at", "").takeIf { it.isNotBlank() }

            var apkUrl: String? = null
            var firstAssetUrl: String? = null
            val assets = obj.optJSONArray("assets")
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val asset = assets.optJSONObject(i) ?: continue
                    val name = asset.optString("name", "")
                    val dl = asset.optString("browser_download_url", "").trim()
                    if (dl.isNotEmpty()) {
                        if (firstAssetUrl == null) firstAssetUrl = dl
                        if (name.endsWith(".apk", ignoreCase = true)) {
                            apkUrl = dl
                            break
                        }
                    }
                }
            }

            val cleanVersion = tagName.removePrefix("v").removePrefix("V")
            val summary = summarizeReleaseNotes(rawBody)

            return GitHubReleaseInfo(
                tagName = tagName,
                version = cleanVersion,
                releaseName = releaseName,
                notesSummary = summary,
                downloadUrl = apkUrl ?: firstAssetUrl ?: htmlUrl,
                htmlUrl = htmlUrl,
                publishedAt = publishedAt
            )
        }

        internal fun summarizeReleaseNotes(raw: String): String {
            if (raw.isBlank()) return "Bug fixes, chart upgrades, and stability improvements."
            val lines = raw.lineSequence()
                .map { it.trim().trimStart('#', '*', '-', '•', ' ') }
                .filter { it.isNotBlank() }
                .take(4)
                .toList()
            val joined = lines.joinToString("\n• ", prefix = if (lines.size > 1) "• " else "")
            return if (joined.length > 260) joined.take(257).trimEnd() + "..." else joined
        }
    }
}
