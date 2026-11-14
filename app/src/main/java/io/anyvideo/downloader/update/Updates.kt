package io.anyvideo.downloader.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import io.anyvideo.downloader.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * The app is not on Play, so it checks its own GitHub releases. It deliberately does not download
 * or install APKs: that needs REQUEST_INSTALL_PACKAGES and makes Play Protect treat the app as a
 * self-updater. The APK asset opens in the browser instead, and Android or the browser handles
 * the download and the normal installer prompt.
 */
object Updates {

    private const val API = "https://api.github.com/repos/developer-mohanaditya/any-video/releases/latest"

    data class Release(
        val name: String,
        val notes: String,
        val pageUrl: String,
        val downloadUrl: String,
        val apkName: String,
        val size: Long,
    )

    sealed interface State {
        data object Idle : State
        data object Checking : State
        data object UpToDate : State
        data class Available(val release: Release) : State
        data class Failed(val message: String) : State
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    /** Tags look like v1.2.1; each number is compared in order, so 1.10 beats 1.9. */
    fun parseVersion(text: String): List<Int> =
        text.trim().removePrefix("v").split('.', '-', '+')
            .mapNotNull { part -> part.takeWhile { it.isDigit() }.toIntOrNull() }

    fun isNewer(remote: String, local: String): Boolean {
        val a = parseVersion(remote)
        val b = parseVersion(local)
        if (a.isEmpty()) return false
        for (index in 0 until maxOf(a.size, b.size)) {
            val left = a.getOrElse(index) { 0 }
            val right = b.getOrElse(index) { 0 }
            if (left != right) return left > right
        }
        return false
    }

    fun reset() {
        if (_state.value !is State.Checking) _state.value = State.Idle
    }

    suspend fun check() = withContext(Dispatchers.IO) {
        _state.value = State.Checking
        val result = runCatching {
            val request = Request.Builder()
                .url(API)
                .header("Accept", "application/vnd.github+json")
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) error("GitHub answered ${response.code}")
                val body = response.body?.string() ?: error("GitHub sent an empty answer")
                parseLatest(JSONObject(body), BuildConfig.VERSION_NAME)
            }
        }
        _state.value = result.fold(
            onSuccess = { release -> if (release == null) State.UpToDate else State.Available(release) },
            onFailure = { error -> State.Failed(error.message ?: "Could not reach GitHub") },
        )
    }

    /** Parses the latest-release payload without touching the network. */
    internal fun parseLatest(root: JSONObject, localVersion: String): Release? {
        val tag = root.optString("tag_name")
        if (tag.isBlank()) error("The release has no version tag")
        if (!isNewer(tag, localVersion)) return null
        val assets = root.optJSONArray("assets") ?: error("The release has no files")
        var apkSize = 0L
        var apkName = ""
        var apkUrl = ""
        for (index in 0 until assets.length()) {
            val asset = assets.getJSONObject(index)
            val name = asset.optString("name")
            if (name.endsWith(".apk", true)) {
                apkSize = asset.optLong("size")
                apkName = name
                apkUrl = asset.optString("browser_download_url")
                break
            }
        }
        val pageUrl = root.optString("html_url").ifBlank {
            "https://github.com/developer-mohanaditya/any-video/releases/tag/$tag"
        }
        return Release(
            name = tag.removePrefix("v"),
            notes = root.optString("body").trim(),
            pageUrl = pageUrl,
            // GitHub's release page can be handled by the GitHub app or land on the repository
            // root. The asset URL starts the APK download in the browser.
            downloadUrl = apkUrl.ifBlank { pageUrl },
            apkName = apkName,
            size = apkSize,
        )
    }

    /**
     * Opens the APK asset so the browser, not the app, downloads it. The fallback is the release
     * page when a release has no APK attachment.
     */
    fun open(context: Context, release: Release) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(release.downloadUrl))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }
}
