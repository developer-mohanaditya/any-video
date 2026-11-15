package io.anyvideo.downloader.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import io.anyvideo.downloader.BuildConfig
import io.anyvideo.downloader.util.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * The app is not on Play, so it updates from its own GitHub releases. This checks the latest
 * release, downloads its APK inside the app, verifies the size and checksum, proves the archive is
 * this package signed with the same key, and then hands it to Android's installer.
 */
object Updates {

    private const val API = "https://api.github.com/repos/developer-mohanaditya/any-video/releases/latest"
    private const val CHECK_INTERVAL_MS = 24L * 60L * 60L * 1000L

    data class Release(
        val name: String,
        val notes: String,
        val pageUrl: String,
        val downloadUrl: String,
        val apkName: String,
        val size: Long,
        val sha256: String,
    )

    sealed interface State {
        data object Idle : State
        data object Checking : State
        data object UpToDate : State
        data class Available(val release: Release) : State
        data class Downloading(val release: Release, val received: Long, val total: Long) : State
        data class Ready(val release: Release, val file: File) : State
        data class Failed(val message: String, val release: Release? = null) : State
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    // The update continues if the settings screen or the activity that started it goes away.
    private val work = CoroutineScope(SupervisorJob() + Dispatchers.IO)

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

    /** Starts a background check only if a day has passed since the last successful one. */
    fun autoCheck(context: Context) {
        val now = System.currentTimeMillis()
        if (now - Prefs.lastUpdateCheck < CHECK_INTERVAL_MS) return
        startCheck(context)
    }

    /** Manual checks always run. */
    fun startCheck(context: Context) {
        if (_state.value is State.Checking || _state.value is State.Downloading) return
        _state.value = State.Checking
        val appContext = context.applicationContext
        work.launch { check(appContext) }
    }

    /**
     * Checks the latest release. A same-version asset is offered only when its APK differs from the
     * installed one, which lets a 1.3.8 hotfix reach people already on 1.3.8.
     */
    private suspend fun check(context: Context) {
        val localHash = if (BuildConfig.DEBUG) "" else localApkSha256(context)
        val result = runCatching {
            val request = Request.Builder()
                .url(API)
                .header("Accept", "application/vnd.github+json")
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) error("GitHub answered ${response.code}")
                val body = response.body?.string() ?: error("GitHub sent an empty answer")
                parseLatest(JSONObject(body), BuildConfig.VERSION_NAME, localHash)
            }
        }
        _state.value = result.fold(
            onSuccess = { release ->
                Prefs.lastUpdateCheck = System.currentTimeMillis()
                if (release == null) State.UpToDate else State.Available(release)
            },
            onFailure = { error -> State.Failed(error.message ?: "Could not reach GitHub") },
        )
    }

    /** Parses the latest-release payload without touching the network. */
    internal fun parseLatest(
        root: JSONObject,
        localVersion: String,
        localApkSha256: String = "",
    ): Release? {
        val tag = root.optString("tag_name")
        if (tag.isBlank()) error("The release has no version tag")
        val newer = isNewer(tag, localVersion)
        val sameVersion = parseVersion(tag) == parseVersion(localVersion)
        if (!newer && !sameVersion) return null

        val assets = root.optJSONArray("assets") ?: error("The release has no files")
        var apkSize = 0L
        var apkName = ""
        var apkUrl = ""
        var apkSha256 = ""
        for (index in 0 until assets.length()) {
            val asset = assets.getJSONObject(index)
            val name = asset.optString("name")
            if (name.endsWith(".apk", true)) {
                apkSize = asset.optLong("size")
                apkName = name
                apkUrl = asset.optString("browser_download_url")
                apkSha256 = asset.optString("digest").substringAfter("sha256:", "")
                break
            }
        }
        if (apkUrl.isBlank()) error("The release has no APK file")
        if (!newer && (localApkSha256.isBlank() || apkSha256.isBlank() || apkSha256 == localApkSha256)) {
            return null
        }
        val pageUrl = root.optString("html_url").ifBlank {
            "https://github.com/developer-mohanaditya/any-video/releases/tag/$tag"
        }
        return Release(
            name = tag.removePrefix("v"),
            notes = root.optString("body").trim(),
            pageUrl = pageUrl,
            downloadUrl = apkUrl,
            apkName = apkName,
            size = apkSize,
            sha256 = apkSha256,
        )
    }

    /** Downloads the release APK in the app, verifies it, and leaves it ready to install. */
    fun startDownload(context: Context, release: Release) {
        if (_state.value is State.Downloading) return
        _state.value = State.Downloading(release, 0, release.size)
        val appContext = context.applicationContext
        work.launch { download(appContext, release) }
    }

    private suspend fun download(context: Context, release: Release) {
        val folder = File(context.cacheDir, "updates").apply { mkdirs() }
        val file = File(folder, "AnyVideo-${release.name}.apk")
        val part = File(folder, "${file.name}.part")
        val result = runCatching {
            part.delete()
            val request = Request.Builder()
                .url(release.downloadUrl)
                .header("Accept", "application/octet-stream")
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) error("The download answered ${response.code}")
                val body = response.body ?: error("The download was empty")
                val total = if (release.size > 0) release.size else body.contentLength().coerceAtLeast(0L)
                part.outputStream().use { output ->
                    body.byteStream().use { input ->
                        val buffer = ByteArray(64 * 1024)
                        var received = 0L
                        var lastPost = 0L
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val read = input.read(buffer)
                            if (read <= 0) break
                            output.write(buffer, 0, read)
                            received += read
                            val now = System.currentTimeMillis()
                            if (now - lastPost > 120) {
                                lastPost = now
                                _state.value = State.Downloading(release, received, total)
                            }
                        }
                        _state.value = State.Downloading(release, received, total)
                    }
                }
            }
            if (part.length() <= 0L) error("The downloaded file is empty")
            if (release.size > 0 && part.length() != release.size) {
                error("The downloaded file is the wrong size")
            }
            if (release.sha256.isNotBlank() && !sha256(part).equals(release.sha256, ignoreCase = true)) {
                error("The downloaded file does not match GitHub's checksum")
            }
            if (!isOurApp(context, part)) {
                error("That file is not AnyVideo signed with this app's key")
            }
            if (file.exists()) file.delete()
            if (!part.renameTo(file)) error("Could not finish the update download")
            file
        }
        part.delete()
        _state.value = result.fold(
            onSuccess = { State.Ready(release, it) },
            onFailure = { State.Failed(it.message ?: "The download failed", release) },
        )
    }

    /** The archive must carry this package name and this app's signing certificate. */
    private fun isOurApp(context: Context, file: File): Boolean {
        val manager = context.packageManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
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
        @Suppress("DEPRECATION")
        val archive = manager.getPackageArchiveInfo(file.path, PackageManager.GET_SIGNATURES)
            ?: return false
        if (archive.packageName != context.packageName) return false
        @Suppress("DEPRECATION")
        val theirs = archive.signatures?.firstOrNull()?.toByteArray() ?: return false
        @Suppress("DEPRECATION")
        val ours = runCatching {
            manager.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
                .signatures?.firstOrNull()?.toByteArray()
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
     * package name and signature match, so settings and downloads stay.
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

    private fun localApkSha256(context: Context): String = runCatching {
        val source = context.applicationInfo?.sourceDir ?: return ""
        sha256(File(source))
    }.getOrDefault("")

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
