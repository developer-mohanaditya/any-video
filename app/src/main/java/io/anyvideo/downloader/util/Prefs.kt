package io.anyvideo.downloader.util

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import io.anyvideo.downloader.BuildConfig

object Prefs {
    private const val FILE = "any_video_prefs"
    const val DEFAULT_EXTRACTOR = "https://snapsave.app/action.php"

    private lateinit var sp: SharedPreferences
    private var debuggable = false

    fun init(context: Context) {
        sp = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        debuggable = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    }

    /**
     * Debug-only mirror for the Instagram hosts, set by the emulator smoke test: when it is
     * non-empty, `https://i.instagram.com/x` and `https://www.instagram.com/x` are requested from
     * `<base>/ig/i/x` and `<base>/ig/www/x` so canned API payloads can stand in for the live site.
     * Release builds always return an empty string.
     */
    var instagramMockBase: String
        get() = if (!debuggable || !::sp.isInitialized) "" else {
            (sp.getString("instagram_mock_base", "") ?: "").trim().trimEnd('/')
        }
        set(value) = sp.edit().putString("instagram_mock_base", value.trim()).apply()

    /** Appearance: "auto" follows the system, otherwise "light" or "dark". */
    var themeMode: String
        get() = sp.getString("theme_mode", "auto") ?: "auto"
        set(value) = sp.edit().putString("theme_mode", value).apply()

    var showDownloadNotifications: Boolean
        get() = sp.getBoolean("notifications", true)
        set(value) = sp.edit().putBoolean("notifications", value).apply()

    /**
     * FastSave behaviour: opening the app with a supported link on the clipboard resolves it and
     * enqueues the files straight away, instead of stopping at the catalogue for a Save tap.
     */
    var autoDownloadClipboard: Boolean
        get() = sp.getBoolean("auto_download_clipboard", true)
        set(value) = sp.edit().putBoolean("auto_download_clipboard", value).apply()

    /** Last clipboard text already auto-downloaded, so a cold start does not fetch it again. */
    var lastAutoClipboard: String
        get() = sp.getString("last_auto_clipboard", "") ?: ""
        set(value) = sp.edit().putString("last_auto_clipboard", value).apply()

    /** YouTube: "max" or a height like "1080"; passed through to the resolver as videoQuality. */
    var youtubeQuality: String
        get() = sp.getString("youtube_quality", "max") ?: "max"
        set(value) = sp.edit().putString("youtube_quality", value).apply()

    /** YouTube: save the soundtrack only (resolver downloadMode=audio). */
    var youtubeAudioOnly: Boolean
        get() = sp.getBoolean("youtube_audio_only", false)
        set(value) = sp.edit().putBoolean("youtube_audio_only", value).apply()

    var youtubeAudioFormat: String
        get() = sp.getString("youtube_audio_format", "mp3") ?: "mp3"
        set(value) = sp.edit().putString("youtube_audio_format", value).apply()

    /**
     * Debug-only stand-in for a signed-in session when [instagramMockBase] points at the local
     * smoke harness, which cannot perform a real login.
     */
    var igMockCookie: String
        get() = if (!debuggable || !::sp.isInitialized) "" else {
            sp.getString("ig_mock_cookie", "") ?: ""
        }
        set(value) {
            // A release install never stores a stand-in session.
            if (debuggable && ::sp.isInitialized) sp.edit().putString("ig_mock_cookie", value).apply()
        }

    /**
     * Instagram display name last seen for the browser session, so Settings can show who is
     * signed in without a network round trip on every open. Empty means unknown/signed out.
     */
    var igUsername: String
        get() = sp.getString("ig_username", "") ?: ""
        set(value) = sp.edit().putString("ig_username", value).apply()

    /** YouTube audio: converter bitrate in kbps, passed to the resolver as audioBitrate. */
    var youtubeAudioBitrate: String
        get() = sp.getString("youtube_audio_bitrate", "320") ?: "320"
        set(value) = sp.edit().putString("youtube_audio_bitrate", value).apply()

    /** What the user typed in Settings. Empty means "use the built-in instance". */
    var resolverApiOverride: String
        get() = (sp.getString("resolver_api", "") ?: "").trim().trimEnd('/')
        set(value) = sp.edit().putString("resolver_api", value.trim().trimEnd('/')).apply()

    /** True when the build was given a default instance (`-Panyvideo.resolver=…`). */
    val hasBuiltInResolver: Boolean
        get() = BuildConfig.DEFAULT_RESOLVER_API.isNotBlank()

    /**
     * Base URL of a cobalt-compatible resolver API, without a trailing slash. Falls back to the
     * instance baked in at build time, which is empty unless the build supplied one.
     */
    var resolverApiBase: String
        get() = resolverApiOverride.ifEmpty { BuildConfig.DEFAULT_RESOLVER_API.trim().trimEnd('/') }
        set(value) {
            resolverApiOverride = value
        }

    /** Optional credential sent as `Authorization: Api-Key <key>` to the resolver API. */
    var resolverApiKey: String
        get() = (sp.getString("resolver_api_key", "") ?: "").trim()
        set(value) = sp.edit().putString("resolver_api_key", value.trim()).apply()

    /**
     * Whether Settings shows the extractor and fallback-proxy URLs. Off by default so the screen
     * exposes no third-party endpoints; the values themselves still exist either way.
     */
    var showAdvancedEndpoints: Boolean
        get() = sp.getBoolean("show_advanced_endpoints", false)
        set(value) = sp.edit().putBoolean("show_advanced_endpoints", value).apply()

    var extractorEndpoint: String
        get() = sp.getString("extractor", DEFAULT_EXTRACTOR)?.takeIf { it.isNotBlank() } ?: DEFAULT_EXTRACTOR
        set(value) = sp.edit().putString("extractor", value.trim()).apply()

    /** Optional first-party fallback used when a direct download fails, mirroring the original app. */
    var fallbackProxy: String
        get() = sp.getString("fallback_proxy", "") ?: ""
        set(value) = sp.edit().putString("fallback_proxy", value.trim()).apply()

}
