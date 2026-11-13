package io.anyvideo.downloader.net

import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebSettings
import io.anyvideo.downloader.util.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * The Instagram account the in-app browser is signed in with.
 *
 * The app has no account system of its own: the WebView's cookie jar *is* the session. Signing
 * in on instagram.com in the Browser tab is what makes private content reachable, and every
 * caller that needs the session reads it from here rather than juggling cookies itself.
 */
object IgSession {

    // Without the auth_switcher source Instagram redirects a mobile WebView to its phone-signup
    // page instead of showing the username and password form.
    const val LOGIN_URL = "https://www.instagram.com/accounts/login/?source=auth_switcher"
    private const val DOMAIN = "https://www.instagram.com"

    /** Last known display name, cached so Settings can render instantly. */
    var username: String?
        get() = Prefs.igUsername.takeIf { it.isNotBlank() }
        set(value) {
            Prefs.igUsername = value.orEmpty()
        }

    suspend fun cookie(): String? = withContext(Dispatchers.Main) {
        // Debug builds pointed at the local smoke harness cannot sign in for real, so a fixed
        // cookie stands in for the session. Release builds never set the mock base.
        val mock = runCatching { Prefs.instagramMockBase }.getOrDefault("")
        if (mock.isNotEmpty()) return@withContext Prefs.igMockCookie.ifBlank { null }
        runCatching { CookieManager.getInstance().getCookie(DOMAIN) }.getOrNull()
    }

    /** True when the jar holds a login session. No request, so it is safe to call on screen open. */
    suspend fun signedIn(): Boolean = cookie()?.contains("sessionid=") == true

    /**
     * Asks Instagram who this session belongs to, refreshing the cached name. Returns the
     * username, or null when signed out (or when the cookie is stale).
     */
    /**
     * Outcome of a session check, with the answer Instagram gave for each account lookup.
     * [detail] is what Settings prints, so a failed private download can be traced to the
     * request that actually broke rather than to a vague "not signed in".
     */
    data class Check(
        val signedIn: Boolean,
        val username: String? = null,
        val detail: String,
    )

    suspend fun refreshAccount(context: Context): String? = check(context).username

    suspend fun check(context: Context): Check {
        val cookie = cookie()
            ?: return Check(false, null, "No cookie for instagram.com in the browser.")
        if (!cookie.contains("sessionid=")) {
            username = null
            return Check(false, null, "No sessionid cookie. The browser is not signed in.")
        }

        val trace = ArrayList<String>()
        if (csrfToken(cookie) == null) trace += "csrftoken missing"
        // The web session lives on www; the app-scoped host rejects cookies minted by the web
        // login. Every host is tried so a signed-in user sees their handle, and the trace says
        // which one answered when they do not.
        for ((label, url, key) in ACCOUNT_LOOKUPS) {
            val reply = withContext(Dispatchers.IO) { Http.getReply(url, apiHeaders(context, cookie)) }
            if (reply == null) {
                trace += "$label unreachable"
                continue
            }
            if (!reply.isSuccess) {
                trace += "$label ${reply.code}"
                continue
            }
            val name = runCatching {
                JSONObject(reply.body.orEmpty())
                    .optJSONObject(key)?.optString("username")?.ifBlank { null }
            }.getOrNull()
            if (name == null) {
                trace += "$label 200, no username"
                continue
            }
            username = name
            trace += "$label 200"
            return Check(true, name, trace.joinToString(" · "))
        }
        return Check(true, username, trace.joinToString(" · ").ifBlank { "no lookup answered" })
    }

    private val ACCOUNT_LOOKUPS = listOf(
        Triple(
            "web_form_data",
            "https://www.instagram.com/api/v1/accounts/edit/web_form_data/",
            "form_data",
        ),
        Triple(
            "current_user",
            "https://www.instagram.com/api/v1/accounts/current_user/?edit=false",
            "user",
        ),
        Triple(
            "app API",
            "https://i.instagram.com/api/v1/accounts/current_user/?edit=false",
            "user",
        ),
    )

    suspend fun signOut() {
        withContext(Dispatchers.Main) {
            runCatching {
                CookieManager.getInstance().removeAllCookies(null)
                CookieManager.getInstance().flush()
            }
        }
        username = null
    }

    /**
     * Headers the logged-in v1 API expects. The user agent is the WebView's own: a session used
     * from a different UA than the one that created it is exactly the mismatch Instagram flags.
     */
    fun apiHeaders(context: Context, cookie: String?): Map<String, String> = buildMap {
        put("User-Agent", userAgent(context))
        put("X-IG-App-ID", Http.INSTAGRAM_APP_ID)
        put("Accept", "*/*")
        if (!cookie.isNullOrBlank()) {
            put("Cookie", cookie)
            // The web API rejects session calls without the token that matches the cookie's
            // csrftoken value; it is not a secret, it is the same value the cookie carries.
            csrfToken(cookie)?.let { put("X-CSRFToken", it) }
            put("X-Requested-With", "XMLHttpRequest")
            put("Referer", "https://www.instagram.com/")
        }
    }

    private fun csrfToken(cookie: String): String? = cookie.split(';')
        .map { it.trim() }
        .firstOrNull { it.startsWith("csrftoken=") }
        ?.substringAfter('=')
        ?.ifBlank { null }

    fun userAgent(context: Context): String = appUserAgent ?: runCatching {
        WebSettings.getDefaultUserAgent(context)
    }.getOrDefault(Http.INSTAGRAM_UA).also { appUserAgent = it }

    @Volatile
    private var appUserAgent: String? = null
}
