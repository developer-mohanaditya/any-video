package io.anyvideo.downloader.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import io.anyvideo.downloader.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The app is not on Play, so it updates from its own GitHub releases. This checks the latest
 * release, downloads its APK, proves the file is this app signed with the same key, and then
 * hands it to Android's installer, which does the replacing.
 */
object Updates {

    private const val API = "https://api.github.com/repos/developer-mohanaditya/any-video/releases/latest"

    data class Release(val name: String, val notes: String, val url: String, val size: Long)

    sealed interface State {
        data object Idle : State
        data object Checking : State
        data object UpToDate : State
        data class Available(val release: Release) : State
        data class Downloading(val received: Long, val total: Long) : State
        data class Ready(val release: Release, val file: File) : State
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
        if (_state.value !is State.Downloading) _state.value = State.Idle
    }

    suspend fun check(context: Context) = withContext(Dispatchers.IO) {
        _state.value = State.Checking
        val result = runCatching {
            val request = Request.Builder()
                .url(API)
                .header("Accept", "application/vnd.github+json")
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) error("GitHub answered ${response.code}")
                val body = response.body?.string() ?: error("GitHub sent an empty answer")
                val root = JSONObject(body)
                val tag = root.optString("tag_name")
                if (tag.isBlank()) error("The release has no version tag")
                if (!isNewer(tag, BuildConfig.VERSION_NAME)) return@runCatching null
                val assets = root.optJSONArray("assets") ?: error("The release has no files")
                var apkUrl = ""
                var apkSize = 0L
                for (index in 0 until assets.length()) {
                    val asset = assets.getJSONObject(index)
                    val name = asset.optString("name")
                    if (name.endsWith(".apk", true)) {
                        apkUrl = asset.optString("browser_download_url")
                        apkSize = asset.optLong("size")
                        break
                    }
                }
                if (apkUrl.isBlank()) error("The release has no APK")
                Release(
                    name = tag.removePrefix("v"),
                    notes = root.optString("body").trim(),
                    url = apkUrl,
                    size = apkSize,
                )
            }
        }
        _state.value = result.fold(
            onSuccess = { release -> if (release == null) State.UpToDate else State.Available(release) },
            onFailure = { error -> State.Failed(error.message ?: "Could not reach GitHub") },
        )
    }

    suspend fun download(context: Context, release: Release) = withContext(Dispatchers.IO) {
        val folder = File(context.cacheDir, "updates").apply { mkdirs() }
        val file = File(folder, "AnyVideo-${release.name}.apk")
        val result = runCatching {
            val request = Request.Builder()
                .url(release.url)
                .header("Accept", "application/octet-stream")
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) error("The download answered ${response.code}")
                val body = response.body ?: error("The download was empty")
                val total = if (release.size > 0) release.size else body.contentLength().coerceAtLeast(0L)
                file.outputStream().use { output ->
                    body.byteStream().use { input ->
                        val buffer = ByteArray(64 * 1024)
                        var received = 0L
                        var lastPost = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read <= 0) break
                            output.write(buffer, 0, read)
                            received += read
                            val now = System.currentTimeMillis()
                            if (now - lastPost > 120) {
                                lastPost = now
                                _state.value = State.Downloading(received, total)
                            }
                        }
                        _state.value = State.Downloading(received, total)
                    }
                }
            }
            if (file.length() <= 0L) error("The downloaded file is empty")
            if (!isOurApp(context, file)) {
                file.delete()
                error("That file is not AnyVideo signed with this app's key")
            }
            file
        }
        _state.value = result.fold(
            onSuccess = { State.Ready(release, it) },
            onFailure = { State.Failed(it.message ?: "The download failed") },
        )
    }

    /** The archive must carry this package name and this app's signing certificate. */
    private fun isOurApp(context: Context, file: File): Boolean {
        val manager = context.packageManager
        val archive = manager.getPackageArchiveInfo(file.path, PackageManager.GET_SIGNING_CERTIFICATES)
            ?: return false
        if (archive.packageName != context.packageName) return false
        val theirs = archive.signingInfo?.apkContentsSigners?.firstOrNull()?.toByteArray()
            ?: return false
        val ours = runCatching {
            manager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                .signingInfo?.apkContentsSigners?.firstOrNull()?.toByteArray()
        }.getOrNull() ?: return false
        return ours.contentEquals(theirs)
    }

    fun canInstall(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

    /** Opens the one-time "install unknown apps" switch for this app. */
    fun openInstallPermission(context: Context) {
        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
            .setData(Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    /**
     * Hands the APK to Android. The installer replaces the running app in place because the
     * package name and signature match and the version is higher, so settings and downloads stay.
     */
    fun install(context: Context, file: File): Boolean {
        if (!canInstall(context)) {
            openInstallPermission(context)
            return false
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { context.startActivity(intent) }.isSuccess
    }
}
