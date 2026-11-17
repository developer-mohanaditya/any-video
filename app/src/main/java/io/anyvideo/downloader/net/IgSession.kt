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

    /**
     * The browser and the API must use one identity: Instagram binds a web session to the user
     * agent that established it. Use the device's real WebView UA, which is a mobile UA but names
     * the actual phone instead of a fabricated model in login alerts.
     */
    fun webUserAgent(context: Context): String = webAgent ?: runCatching {
        WebSettings.getDefaultUserAgent(context)
    }.getOrDefault(Http.DESKTOP_UA).also { webAgent = it }

    @Volatile
    private var webAgent: String? = null

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
        // which one answered when they do not. No redirects: a 302 to the login page means the
        // session was not accepted, not that the network is down.
        for (lookup in ACCOUNT_LOOKUPS) {
            val reply = withContext(Dispatchers.IO) {
                Http.getReply(
                    lookup.url,
                    if (lookup.mobile) apiHeaders(context, cookie) else webHeaders(context, cookie),
                    followRedirects = false,
                )
            }
            if (reply == null) {
                trace += "${lookup.label} unreachable"
                continue
            }
            if (!reply.isSuccess) {
                trace += "${lookup.label} ${reply.code}"
                continue
            }
            val name = runCatching {
                JSONObject(reply.body.orEmpty())
                    .optJSONObject(lookup.key)?.optString("username")?.ifBlank { null }
            }.getOrNull()
            if (name == null) {
                trace += "${lookup.label} 200, no username"
                continue
            }
            username = name
            trace += "${lookup.label} 200"
            return Check(true, name, trace.joinToString(" · "))
        }
        return Check(true, username, trace.joinToString(" · ").ifBlank { "no lookup answered" })
    }

    private data class AccountLookup(
        val label: String,
        val url: String,
        val key: String,
        val mobile: Boolean = false,
    )

    private val ACCOUNT_LOOKUPS = listOf(
        AccountLookup(
            "web_form_data",
            "https://www.instagram.com/api/v1/accounts/edit/web_form_data/",
            "form_data",
        ),
        AccountLookup(
            "current_user",
            "https://www.instagram.com/api/v1/accounts/current_user/?edit=false",
            "user",
        ),
        AccountLookup(
            "app API",
            "https://i.instagram.com/api/v1/accounts/current_user/?edit=false",
            "user",
            mobile = true,
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
        webAgent = null
    }

    /**
     * Headers for the app-scoped host. The Instagram app UA and app id are a pair; a web UA on
     * this host makes Instagram answer with a user-agent mismatch.
     */
    fun apiHeaders(context: Context, cookie: String?): Map<String, String> = buildMap {
        put("User-Agent", Http.INSTAGRAM_UA)
        put("X-IG-App-ID", Http.INSTAGRAM_APP_ID)
        put("Accept", "*/*")
        if (!cookie.isNullOrBlank()) {
            val safe = headerSafe(cookie)
            put("Cookie", safe)
            csrfToken(safe)?.let { put("X-CSRFToken", it) }
            put("X-Requested-With", "XMLHttpRequest")
            put("Referer", "https://www.instagram.com/")
        }
    }

    /** Headers for the web endpoints, using the same UA as the WebView that signed in. */
    fun webHeaders(
        context: Context,
        cookie: String?,
        accept: String = "*/*",
    ): Map<String, String> = webHeadersFor(webUserAgent(context), cookie, accept)

    internal fun webHeadersFor(
        userAgent: String,
        cookie: String?,
        accept: String = "*/*",
    ): Map<String, String> = buildMap {
        put("User-Agent", userAgent)
        put("X-IG-App-ID", Http.INSTAGRAM_APP_ID)
        put("Accept", accept)
        put("Accept-Language", "en-US,en;q=0.9")
        if (!cookie.isNullOrBlank()) {
            val safe = headerSafe(cookie)
            put("Cookie", safe)
            csrfToken(safe)?.let { put("X-CSRFToken", it) }
            put("X-Requested-With", "XMLHttpRequest")
            put("Referer", "https://www.instagram.com/")
            put("Origin", "https://www.instagram.com")
            put("Sec-Fetch-Site", "same-origin")
            put("Sec-Fetch-Mode", "cors")
            put("Sec-Fetch-Dest", "empty")
            put("X-ASBD-ID", "129477")
            put("X-IG-WWW-Claim", cookieValue(safe, "__ig_www_claim") ?: "0")
        }
    }

    /**
     * CookieManager can return values containing control bytes. OkHttp rejects those for the
     * whole request, which previously made every account lookup look unreachable. Browser cookie
     * values are ASCII, so dropping only invalid header bytes is safe.
     */
    private fun headerSafe(value: String): String =
        value.filter { it.code in 0x20..0x7e }

    private fun csrfToken(cookie: String): String? = cookieValue(cookie, "csrftoken")

    private fun cookieValue(cookie: String, name: String): String? = cookie.split(';')
        .map { it.trim() }
        .firstOrNull { it.startsWith("$name=") }
        ?.substringAfter('=')
        ?.ifBlank { null }
}
