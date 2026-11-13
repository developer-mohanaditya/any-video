package io.anyvideo.downloader.net

import android.content.Context
import io.anyvideo.downloader.data.FileNames
import io.anyvideo.downloader.data.MediaCandidate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigInteger

/**
 * Instagram resolver rebuilt from the observed behaviour of the original app:
 * it tries the private v1 API first, then the web `__a=1` payload, then the
 * embed page, always replaying the WebView session cookie so logged-in private
 * content resolves the same way.
 */
object Instagram {

    private val postRegex = Regex("instagram\\.com/(?:p|reel|reels|tv)/([A-Za-z0-9_-]+)")
    private val userRegex = Regex("instagram\\.com/([A-Za-z0-9._]{1,40})/?$")
    private const val QUERY_HASH_TIMELINE = "44efc15d3c13342d02df0b5a9fa3d33f"

    fun isInstagram(url: String): Boolean = url.contains("instagram.com") || url.contains("instagr.am")

    fun shortcode(url: String): String? = postRegex.find(url)?.groupValues?.getOrNull(1)

    fun usernameFrom(url: String): String? {
        val candidate = userRegex.find(url.substringBefore('?'))?.groupValues?.getOrNull(1) ?: return null
        if (candidate in setOf("p", "reel", "reels", "tv", "stories", "explore", "accounts")) return null
        return candidate
    }

    /** Instagram media ids are the shortcode decoded from its URL-safe base64 alphabet. */
    fun mediaId(shortcode: String): String {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
        var result = BigInteger.ZERO
        for (char in shortcode) {
            val index = alphabet.indexOf(char)
            if (index < 0) return shortcode
            result = result.multiply(BigInteger.valueOf(64)).add(BigInteger.valueOf(index.toLong()))
        }
        return result.toString()
    }

    /** The browser session, shared by every Instagram request. */
    suspend fun cookies(): String? = IgSession.cookie()

    private fun igHeaders(context: Context, cookie: String?): Map<String, String> =
        IgSession.apiHeaders(context, cookie)

    private fun webHeaders(cookie: String?): Map<String, String> = buildMap {
        put("User-Agent", Http.DESKTOP_UA)
        put("Accept", "text/html,application/json")
        if (!cookie.isNullOrBlank()) put("Cookie", cookie)
    }

    suspend fun resolvePost(context: Context, url: String): List<MediaCandidate> {
        val code = shortcode(url) ?: return emptyList()
        val cookie = cookies()
        val id = mediaId(code)

        val attempts = listOf(
            "https://i.instagram.com/api/v1/media/$id/info/" to igHeaders(context, cookie),
            "https://www.instagram.com/p/$code/?__a=1&__d=dis" to webHeaders(cookie),
        )
        for ((endpoint, headers) in attempts) {
            val body = withContext(Dispatchers.IO) { Http.get(endpoint, headers) } ?: continue
            val parsed = parseMediaPayload(body, url, cookie)
            if (parsed.isNotEmpty()) return parsed
        }
        return scrapeEmbed(url, code, cookie)
    }

    private fun parseMediaPayload(body: String, sourceUrl: String, cookie: String?): List<MediaCandidate> = try {
        val root = JSONObject(body)
        val items = root.optJSONArray("items") ?: run {
            val graphql = root.optJSONObject("graphql")
            if (graphql != null) JSONArray().put(graphql.optJSONObject("shortcode_media") ?: JSONObject()) else null
        }
        if (items == null || items.length() == 0) emptyList() else itemsToCandidates(items, sourceUrl, cookie)
    } catch (t: Throwable) {
        emptyList()
    }

    /**
     * [cookie] is attached to every Instagram-hosted media URL, so a signed-in session can fetch
     * media that is not public. It travels with the DownloadManager request as a header.
     */
    private fun itemsToCandidates(
        items: JSONArray,
        sourceUrl: String,
        cookie: String? = null,
    ): List<MediaCandidate> {
        val out = ArrayList<MediaCandidate>()
        for (i in 0 until items.length()) {
            val item = items.optJSONObject(i) ?: continue
            val user = item.optJSONObject("user")?.optString("username")
                ?: item.optJSONObject("owner")?.optString("username")
            val nodeId = item.optString("id").ifBlank { item.optString("pk") }
            val carousel = item.optJSONArray("carousel_media") ?: item.optJSONObject("edge_sidecar_to_children")?.optJSONArray("edges")

            if (carousel != null && carousel.length() > 0) {
                for (c in 0 until carousel.length()) {
                    val child = carousel.optJSONObject(c) ?: continue
                    val node = child.optJSONObject("node") ?: child
                    out += candidateFromNode(node, user, nodeId.ifBlank { node.optString("id") }, sourceUrl, c, cookie)
                }
            } else {
                out += candidateFromNode(item, user, nodeId, sourceUrl, 0, cookie)
            }
        }
        return out
    }

    private fun candidateFromNode(
        node: JSONObject,
        user: String?,
        parentId: String,
        sourceUrl: String,
        index: Int,
        cookie: String?,
    ): MediaCandidate {
        val video = bestVideoUrl(node)
        val image = bestImageUrl(node)
        val isVideo = video != null || node.optBoolean("is_video") || node.optString("media_type") == "2"
        val url = video ?: image ?: node.optString("display_url")
        val nodeId = node.optString("id").ifBlank { node.optString("pk").ifBlank { parentId } }
        val suffix = if (index > 0) "_$index" else ""
        val ext = FileNames.extensionFromUrl(url, isVideo)
        return MediaCandidate(
            url = url,
            fileName = FileNames.build(user, "$nodeId$suffix", ext),
            thumbnailUrl = image ?: video,
            isVideo = isVideo,
            sourceUrl = sourceUrl,
            headers = mediaHeaders(url, cookie),
        )
    }

    /** Headers for fetching the media itself; the cookie is what makes private media downloadable. */
    private fun mediaHeaders(url: String, cookie: String?): Map<String, String> {
        if (!url.contains("instagram", true) && !url.contains("fbcdn", true)) return emptyMap()
        return buildMap {
            put("User-Agent", Http.INSTAGRAM_UA)
            put("Referer", "https://www.instagram.com/")
            if (!cookie.isNullOrBlank()) put("Cookie", cookie)
        }
    }

    private fun bestVideoUrl(node: JSONObject): String? {
        node.optJSONArray("video_versions")?.let { versions ->
            if (versions.length() > 0) return versions.optJSONObject(0)?.optString("url")
        }
        val direct = node.optString("video_url")
        if (direct.isNotBlank()) return direct
        return null
    }

    private fun bestImageUrl(node: JSONObject): String? {
        node.optJSONArray("image_versions2")?.let { versions ->
            val candidates = versions.optJSONObject(0)?.optJSONArray("candidates")
            if (candidates != null && candidates.length() > 0) {
                return candidates.optJSONObject(0)?.optString("url")
            }
        }
        val display = node.optString("display_url").ifBlank { node.optString("thumbnail_src") }
        if (display.isNotBlank()) return display
        node.optJSONArray("display_resources")?.let { resources ->
            if (resources.length() > 0) return resources.optJSONObject(0)?.optString("src")
        }
        return null
    }

    private suspend fun scrapeEmbed(url: String, code: String, cookie: String?): List<MediaCandidate> {
        val html = withContext(Dispatchers.IO) {
            Http.get("https://www.instagram.com/p/$code/embed/captioned/", webHeaders(cookie))
        } ?: return emptyList()
        val user = Regex("\"owner\":\\{\"username\":\"([^\"]+)\"").find(html)?.groupValues?.get(1)
            ?: Regex("@([A-Za-z0-9._]+)").find(html)?.groupValues?.get(1)
        val image = Regex("\"display_url\":\"([^\"]+)\"").find(html)?.groupValues?.get(1)?.replace("\\/", "/")
        val video = Regex("\"video_url\":\"([^\"]+)\"").find(html)?.groupValues?.get(1)?.replace("\\/", "/")
        val url = video ?: image ?: return emptyList()
        val isVideo = video != null
        return listOf(
            MediaCandidate(
                url = url,
                fileName = FileNames.build(user, code, FileNames.extensionFromUrl(url, isVideo)),
                thumbnailUrl = image,
                isVideo = isVideo,
                sourceUrl = url,
                headers = mediaHeaders(url, cookie),
            ),
        )
    }

    /**
     * Outcome of a profile request. [note] is the user-facing reason a request stopped short,
     * and [signedIn] lets the caller offer sign-in instead of a dead end.
     */
    data class Feed(
        val items: List<MediaCandidate> = emptyList(),
        val note: String? = null,
        val signedIn: Boolean = false,
    )

    /**
     * Timeline of a profile via the v1 feed endpoint, paged by `next_max_id`.
     *
     * Requests are spaced deliberately: a burst of page loads is exactly what turns a working
     * session into a rate-limited or checkpointed one. The cookies of a signed-in Browser
     * session are replayed, and carried onto the media URLs so private files can be fetched.
     */
    suspend fun profileFeed(context: Context, username: String, limit: Int = 60): Feed {
        val cookie = cookies()
        val signedIn = cookie?.contains("sessionid=") == true
        val reply = withContext(Dispatchers.IO) {
            Http.getReply(
                "https://i.instagram.com/api/v1/users/web_profile_info/?username=$username",
                igHeaders(context, cookie),
            )
        } ?: return Feed(
            note = "Could not reach Instagram. Check your connection and try again.",
            signedIn = signedIn,
        )
        if (!reply.isSuccess) return Feed(note = statusNote(reply.code, signedIn), signedIn = signedIn)
        val profile = reply.body.orEmpty()

        apiBlock(profile)?.let { return Feed(note = it, signedIn = signedIn) }

        val user = runCatching {
            JSONObject(profile).getJSONObject("data").getJSONObject("user")
        }.getOrNull()
        val userId = user?.optString("id")?.ifBlank { null }
            ?: return Feed(
                note = if (signedIn) {
                    "Instagram returned no profile for @$username. Check the spelling; the account " +
                        "may also have blocked this session."
                } else {
                    "No profile returned. If the account is private, sign in first."
                },
                signedIn = signedIn,
            )

        val private = user.optBoolean("is_private")
        val out = ArrayList<MediaCandidate>()
        var maxId: String? = null
        var blocked: String? = null
        var page = 0

        while (page < MAX_PAGES) {
            if (out.size >= limit) break
            // Space the pages out; the first page needs no wait.
            if (page > 0) delay(PAGE_DELAY_MS)
            val endpoint = buildString {
                append("https://i.instagram.com/api/v1/feed/user/")
                append(userId)
                append("/?count=33")
                if (!maxId.isNullOrBlank()) append("&max_id=").append(maxId)
            }
            val pageReply = withContext(Dispatchers.IO) { Http.getReply(endpoint, igHeaders(context, cookie)) }
            if (pageReply == null) {
                blocked = "Instagram stopped answering part-way through. Try again in a minute."
                break
            }
            if (!pageReply.isSuccess) {
                blocked = statusNote(pageReply.code, signedIn)
                break
            }
            val body = pageReply.body.orEmpty()
            val block = apiBlock(body)
            if (block != null) {
                blocked = block
                break
            }
            val root = runCatching { JSONObject(body) }.getOrNull()
            val items = root?.optJSONArray("items")
            if (items == null) break
            out += itemsToCandidates(items, "https://www.instagram.com/$username", cookie)
            maxId = root.optString("next_max_id").ifBlank { null }
            if (maxId == null) break
            page++
        }

        val note = blocked ?: when {
            out.isNotEmpty() -> null
            private && !signedIn -> PRIVATE_SIGNED_OUT
            private -> "This account is private. Instagram only returns its posts to an approved " +
                "follower, so follow it from the signed-in account first."
            else -> "No posts returned for @$username."
        }
        return Feed(out, note, signedIn)
    }

    /**
     * Instagram gates its profile endpoints: without a session it answers 404/403 rather than an
     * error, so the status is what tells the user to sign in instead of "no connection".
     */
    private fun statusNote(code: Int, signedIn: Boolean): String = when {
        code == 429 -> "Instagram is rate-limiting this account. Wait a few minutes before loading again."
        code == 401 || code == 403 || code == 404 -> if (signedIn) {
            "Instagram would not return that profile for this session. Check the spelling; if it " +
                "keeps failing, sign in again; the session may have expired."
        } else {
            "Instagram only returns profiles to a logged-in session. Sign in from Settings, " +
                "then load again."
        }
        else -> "Instagram answered $code. Try again in a minute."
    }

    /**
     * Instagram reports soft blocks in the body, often with HTTP 200. Recognising them here is
     * what stops the app from hammering an account that is already being throttled.
     */
    private fun apiBlock(body: String): String? = when {
        body.contains("checkpoint_required") ->
            "Instagram wants a security check for this account. Open the Browser tab, finish " +
                "the check, then try again."
        body.contains("login_required") || body.contains("user_has_logged_out") ->
            "Instagram no longer accepts this session. Sign in again from Settings."
        body.contains("Please wait a few minutes", true) || body.contains("rate limit", true) ->
            "Instagram is rate-limiting this account. Wait a few minutes before loading again."
        else -> null
    }

    /** GraphQL timeline used by the original bulk downloader when the v1 feed is unavailable. */
    suspend fun timelineViaGraphql(context: Context, userId: String, cursor: String? = null): List<MediaCandidate> {
        val variables = JSONObject()
            .put("id", userId)
            .put("first", 24)
            .put("include_reel", true)
            .put("fetch_mutual", false)
            .put("after", cursor ?: "")
        val endpoint = "https://www.instagram.com/graphql/query/?query_hash=$QUERY_HASH_TIMELINE&variables=${variables}"
        val body = withContext(Dispatchers.IO) { Http.get(endpoint, webHeaders(cookies())) } ?: return emptyList()
        val edges = try {
            JSONObject(body).getJSONObject("data").getJSONObject("user")
                .getJSONObject("edge_owner_to_timeline_media").getJSONArray("edges")
        } catch (t: Throwable) {
            return emptyList()
        }
        val nodes = JSONArray()
        for (i in 0 until edges.length()) {
            edges.optJSONObject(i)?.optJSONObject("node")?.let { nodes.put(it) }
        }
        return itemsToCandidates(nodes, "https://www.instagram.com", cookies())
    }

    /** Pages per profile request; enough for a screenful of posts without a burst. */
    private const val MAX_PAGES = 4

    /** Spacing between page requests, which is what keeps a session under Instagram's limits. */
    private const val PAGE_DELAY_MS = 1_200L

    private const val PRIVATE_SIGNED_OUT =
        "That account is private. Sign in from Settings with an account that follows it, " +
            "then load again."
}
