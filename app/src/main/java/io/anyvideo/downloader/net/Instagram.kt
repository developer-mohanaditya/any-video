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
    private val storyRegex = Regex("""instagram\.com/stories/([A-Za-z0-9._]{1,40})(?:/(\d+))?/?""")
    private val highlightRegex = Regex("""instagram\.com/stories/highlights/(\d+)""")
    private const val QUERY_HASH_TIMELINE = "44efc15d3c13342d02df0b5a9fa3d33f"

    fun isInstagram(url: String): Boolean = url.contains("instagram.com") || url.contains("instagr.am")

    fun shortcode(url: String): String? = postRegex.find(url)?.groupValues?.getOrNull(1)

    fun usernameFrom(url: String): String? {
        val candidate = userRegex.find(url.substringBefore('?'))?.groupValues?.getOrNull(1) ?: return null
        if (candidate in setOf("p", "reel", "reels", "tv", "stories", "explore", "accounts")) return null
        return candidate
    }

    /** A story URL names either a profile reel or a highlight reel. */
    sealed interface StoryTarget {
        data class Profile(val username: String, val mediaId: String? = null) : StoryTarget
        data class Highlight(val id: String) : StoryTarget
    }

    fun storyTarget(url: String): StoryTarget? {
        val clean = url.substringBefore('?').substringBefore('#')
        highlightRegex.find(clean)?.let {
            return StoryTarget.Highlight(it.groupValues[1])
        }
        val match = storyRegex.find(clean) ?: return null
        val username = match.groupValues[1]
        if (username == "highlights") return null
        return StoryTarget.Profile(
            username = username,
            mediaId = match.groupValues[2].ifBlank { null },
        )
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

    /** The web fallback must use the same identity as the browser session, not a desktop UA. */
    private fun webHeaders(context: Context, cookie: String?): Map<String, String> =
        IgSession.webHeaders(context, cookie, accept = "text/html,application/json")

    /** Outcome of a post lookup; [note] explains a refusal instead of leaving an empty screen. */
    internal data class PostMedia(
        val items: List<MediaCandidate> = emptyList(),
        val note: String? = null,
    )

    /** What the public pages say a post is, which decides whether one image is an honest answer. */
    internal enum class PostShape { PHOTO, VIDEO, CAROUSEL, UNKNOWN }

    internal suspend fun resolvePost(
        context: Context,
        url: String,
        allowImageFallback: Boolean = true,
    ): PostMedia {
        val code = shortcode(url) ?: return PostMedia()
        val cookie = cookies()
        val id = mediaId(code)

        val attempts = listOf(
            "https://i.instagram.com/api/v1/media/$id/info/" to igHeaders(context, cookie),
            // A web login is more likely to be accepted by the same host that owns the cookie.
            "https://www.instagram.com/api/v1/media/$id/info/" to webHeaders(context, cookie),
            "https://www.instagram.com/p/$code/?__a=1&__d=dis" to webHeaders(context, cookie),
        )
        for ((endpoint, headers) in attempts) {
            val body = withContext(Dispatchers.IO) { Http.get(endpoint, headers) } ?: continue
            val tally = parseMediaPayload(body, url, cookie) ?: continue
            if (tally.items.isEmpty()) continue
            // Handing over 2 of 3 slides looks like a working carousel until the gallery shows
            // the gap, so an incomplete reply is explained instead of saved.
            if (tally.incomplete) return PostMedia(note = CAROUSEL_PARTIAL)
            return PostMedia(tally.items)
        }
        val embed = scrapeEmbed(context, url, code, cookie)
        // A carousel that lost a slide is reported, not handed over as if it were whole.
        if (embed.incomplete) return PostMedia(note = CAROUSEL_PARTIAL)
        if (embed.items.isNotEmpty()) return PostMedia(embed.items)
        // The embed names the post's shape even when it withholds the files. A video's cover
        // downloads exactly like a photo, and a carousel's first slide looks like a complete
        // post, so neither is accepted as what the link points at.
        when (embed.shape) {
            PostShape.VIDEO -> return PostMedia(note = COVER_ONLY)
            PostShape.CAROUSEL -> return PostMedia(note = CAROUSEL_PARTIAL)
            PostShape.PHOTO, PostShape.UNKNOWN -> Unit
        }
        if (!allowImageFallback) return PostMedia()
        // oEmbed answers with exactly one image, so it is only honest for a single-photo post.
        // When the embed did not say, the post page's og: tags do, where a video served under
        // /p/ still keeps its /reel/ route.
        if (embed.shape == PostShape.UNKNOWN && scrapePostShape(code) == PostShape.VIDEO) {
            return PostMedia(note = COVER_ONLY)
        }
        return PostMedia(scrapeOEmbed(context, url, code, cookie))
    }

    internal fun parseMediaItems(body: String): JSONArray? = try {
        val root = JSONObject(body)
        root.optJSONArray("items")?.takeIf { it.length() > 0 } ?: run {
            val holder = root.optJSONObject("graphql")
                ?: root.optJSONObject("data")
                ?: root
            val node = holder.optJSONObject("shortcode_media")
                ?: holder.optJSONObject("xdt_shortcode_media")
                ?: return null
            JSONArray().put(node)
        }
    } catch (t: Throwable) {
        null
    }

    private fun parseMediaPayload(body: String, sourceUrl: String, cookie: String?): ItemTally? =
        parseMediaItems(body)?.let { tallyItems(it, sourceUrl, cookie) }

    /**
     * Instagram's embed page wraps the post in a JSON string named `contextJSON`. A flat
     * `"display_url"` scrape misses it because every quote is escaped, which made single-image
     * posts (and image-only carousels) look empty even when the page contained the media.
     *
     * The page states the shape of a post even when it withholds the files: `isSidecar` marks a
     * carousel, a `GraphVideo` context a video, and a null context a plain photo. Both matter,
     * because saving a cover or the first slide downloads exactly like a successful photo.
     */
    internal data class EmbedProbe(
        val items: List<MediaCandidate> = emptyList(),
        val shape: PostShape = PostShape.UNKNOWN,
        val incomplete: Boolean = false,
    )

    internal fun parseEmbed(
        html: String,
        sourceUrl: String,
        cookie: String? = null,
    ): EmbedProbe {
        val encoded = Regex("\"contextJSON\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"")
            .find(html)?.groupValues?.get(1)
        val context = encoded?.let { value ->
            runCatching { JSONObject("{\"v\":\"$value\"}").getString("v") }
                .getOrNull()
                ?.let { decoded -> runCatching { JSONObject(decoded) }.getOrNull() }
        } ?: return EmbedProbe(shape = shapeOfHtml(html))

        context.optJSONArray("items")?.takeIf { it.length() > 0 }?.let { items ->
            val tally = tallyItems(items, sourceUrl, cookie)
            return EmbedProbe(
                items = tally.items,
                shape = items.optJSONObject(0)?.let { shapeOfNode(it) } ?: PostShape.UNKNOWN,
                incomplete = tally.incomplete,
            )
        }
        val holder = context.optJSONObject("gql_data")
            ?: context.optJSONObject("data")
            ?: context
        val node = holder.optJSONObject("shortcode_media")
            ?: holder.optJSONObject("xdt_shortcode_media")
            ?: return EmbedProbe(shape = shapeOfHtml(html))
        val tally = tallyItems(JSONArray().put(node), sourceUrl, cookie)
        return EmbedProbe(
            items = tally.items,
            shape = shapeOfNode(node),
            incomplete = tally.incomplete,
        )
    }

    /** A node that lists children is a carousel even when its own `is_video` is false. */
    internal fun shapeOfNode(node: JSONObject): PostShape = when {
        node.optInt("media_type") == 8 -> PostShape.CAROUSEL
        (carouselChildren(node)?.length() ?: 0) > 0 -> PostShape.CAROUSEL
        isVideoNode(node) -> PostShape.VIDEO
        // The sources bestImageUrl() accepts are what makes a node a usable photo.
        bestImageUrl(node) != null -> PostShape.PHOTO
        else -> PostShape.UNKNOWN
    }

    /**
     * Shape from a page's own markers, for replies that carry no parsed node. The JSON inside
     * `contextJSON` is escaped, so the quotes are normalised before matching.
     */
    internal fun shapeOfHtml(html: String): PostShape {
        val flat = html.replace("\\\"", "\"")
        return when {
            flat.contains("\"isSidecar\":true") ||
                flat.contains("edge_sidecar_to_children") ||
                flat.contains("\"carousel_media\"") ||
                flat.contains("\"media_type\":8") -> PostShape.CAROUSEL
            flat.contains("\"is_video\":true") ||
                flat.contains("\"type\":\"GraphVideo\"") ||
                flat.contains("\"video_url\"") ||
                flat.contains("\"media_type\":2") -> PostShape.VIDEO
            flat.contains("\"contextJSON\":null") ||
                flat.contains("\"isSidecar\":false") ||
                flat.contains("\"display_url\"") -> PostShape.PHOTO
            else -> PostShape.UNKNOWN
        }
    }

    /** The post page names the route in `og:url`, so a video served under `/p/` is still caught. */
    internal fun shapeOfPage(html: String): PostShape = when {
        html.contains("property=\"og:video\"") -> PostShape.VIDEO
        OG_VIDEO_ROUTE.containsMatchIn(html) -> PostShape.VIDEO
        html.contains("property=\"og:image\"") -> PostShape.PHOTO
        else -> PostShape.UNKNOWN
    }

    internal fun candidatesFromOEmbed(
        body: String,
        sourceUrl: String,
        code: String,
        cookie: String? = null,
    ): List<MediaCandidate> = try {
        val root = JSONObject(body)
        val image = mediaUrl(root.optString("thumbnail_url")) ?: return emptyList()
        val user = root.optString("author_name").ifBlank { null }
        val id = root.optString("media_id").substringBefore('_').ifBlank { code }
        listOf(
            MediaCandidate(
                url = image,
                fileName = FileNames.build(user, id, FileNames.extensionFromUrl(image, false)),
                thumbnailUrl = image,
                isVideo = false,
                sourceUrl = sourceUrl,
                headers = mediaHeaders(image, cookie),
            ),
        )
    } catch (t: Throwable) {
        emptyList()
    }

    /**
     * [expected] is how many files the reply described. [incomplete] is true only when a
     * multi-item post lost one of them: a single unusable item is a different failure, and
     * calling it a missing slide would give the user the wrong explanation.
     */
    internal data class ItemTally(val items: List<MediaCandidate>, val expected: Int) {
        val incomplete: Boolean get() = expected > 1 && items.size < expected
    }

    /**
     * [cookie] is attached to every Instagram-hosted media URL, so a signed-in session can fetch
     * media that is not public. It travels with the DownloadManager request as a header.
     */
    internal fun itemsToCandidates(
        items: JSONArray,
        sourceUrl: String,
        cookie: String? = null,
    ): List<MediaCandidate> = tallyItems(items, sourceUrl, cookie).items

    /** Both reply shapes name a carousel's slides differently; this reads either. */
    private fun carouselChildren(node: JSONObject): JSONArray? =
        node.optJSONArray("carousel_media")
            ?: node.optJSONObject("edge_sidecar_to_children")?.optJSONArray("edges")

    internal fun tallyItems(
        items: JSONArray,
        sourceUrl: String,
        cookie: String? = null,
    ): ItemTally {
        val out = ArrayList<MediaCandidate>()
        var expected = 0
        for (i in 0 until items.length()) {
            val item = items.optJSONObject(i) ?: continue
            val user = item.optJSONObject("user")?.optString("username")
                ?: item.optJSONObject("owner")?.optString("username")
            val nodeId = item.optString("id").ifBlank { item.optString("pk") }
            val carousel = carouselChildren(item)

            if (carousel != null && carousel.length() > 0) {
                expected += carousel.length()
                for (c in 0 until carousel.length()) {
                    val child = carousel.optJSONObject(c) ?: continue
                    val node = child.optJSONObject("node") ?: child
                    candidateFromNode(
                        node = node,
                        user = user,
                        parentId = nodeId.ifBlank { node.optString("id") },
                        sourceUrl = sourceUrl,
                        index = c,
                        cookie = cookie,
                    )?.let { out += it }
                }
            } else {
                expected++
                candidateFromNode(item, user, nodeId, sourceUrl, 0, cookie)?.let { out += it }
            }
        }
        return ItemTally(out, expected)
    }

    /**
     * Instagram sometimes leaves a carousel entry with an empty image or video candidate but
     * still supplies `display_url`. Ignore unusable values so the caller can fall back instead of
     * creating a blank card and handing an empty URL to OkHttp.
     */
    private fun mediaUrl(value: String?): String? {
        val raw = value?.trim().orEmpty()
        if (raw.isEmpty()) return null
        val normalized = if (raw.startsWith("//")) "https:$raw" else raw
        return normalized.takeIf {
            it.startsWith("https://", ignoreCase = true) ||
                it.startsWith("http://", ignoreCase = true)
        }
    }

    private fun candidateFromNode(
        node: JSONObject,
        user: String?,
        parentId: String,
        sourceUrl: String,
        index: Int,
        cookie: String?,
    ): MediaCandidate? {
        val video = bestVideoUrl(node)
        val image = bestImageUrl(node)
        // A cover is not the media. When the node says video and the stream is missing, skip it
        // so the caller can retry or explain, instead of saving the still as a photo.
        if (video == null && isVideoNode(node)) return null
        val url = video ?: image ?: return null
        val isVideo = video != null
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

    /** True when a node describes a video, even if the reply left its stream URL out. */
    internal fun isVideoNode(node: JSONObject): Boolean =
        node.optBoolean("is_video") ||
            node.optInt("media_type") == 2 ||
            node.optString("__typename").contains("Video", true) ||
            node.optString("video_url").isNotBlank() ||
            node.has("video_versions") ||
            node.optString("product_type").lowercase() in VIDEO_PRODUCT_TYPES ||
            node.optDouble("video_duration", 0.0) > 0.0

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
            for (i in 0 until versions.length()) {
                mediaUrl(versions.optJSONObject(i)?.optString("url"))?.let { return it }
            }
        }
        return mediaUrl(node.optString("video_url"))
    }

    private fun bestImageUrl(node: JSONObject): String? {
        node.optJSONArray("image_versions2")?.let { versions ->
            val candidates = versions.optJSONObject(0)?.optJSONArray("candidates")
            if (candidates != null) {
                for (i in 0 until candidates.length()) {
                    mediaUrl(candidates.optJSONObject(i)?.optString("url"))?.let { return it }
                }
            }
        }
        val display = node.optString("display_url").ifBlank { node.optString("thumbnail_src") }
        mediaUrl(display)?.let { return it }
        node.optJSONArray("display_resources")?.let { resources ->
            for (i in 0 until resources.length()) {
                mediaUrl(resources.optJSONObject(i)?.optString("src"))?.let { return it }
            }
        }
        return null
    }

    private suspend fun scrapeEmbed(
        context: Context,
        url: String,
        code: String,
        cookie: String?,
    ): EmbedProbe {
        // The embed fills its context JSON only for a crawler identity; a desktop browser gets
        // the empty app shell, which is why image carousels used to arrive as a single picture.
        // With a signed-in cookie, keep the browser identity that created the session instead.
        val headers = if (cookie.isNullOrBlank()) {
            IgSession.webHeadersFor(
                Http.CRAWLER_UA,
                null,
                accept = "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            )
        } else {
            webHeaders(context, cookie)
        }
        val html = withContext(Dispatchers.IO) {
            Http.get("https://www.instagram.com/p/$code/embed/captioned/", headers)
        } ?: return EmbedProbe()

        val probe = parseEmbed(html, url, cookie)
        // A video or a carousel is never answered from the flat scrape below: that would save a
        // cover, or the first slide in place of the whole carousel.
        if (probe.items.isNotEmpty() ||
            probe.incomplete ||
            probe.shape == PostShape.VIDEO ||
            probe.shape == PostShape.CAROUSEL
        ) {
            return probe
        }

        // Older embed pages exposed the photo directly instead of inside contextJSON.
        val user = Regex("\"owner\":\\{\"username\":\"([^\"]+)\"").find(html)?.groupValues?.get(1)
            ?: Regex("@([A-Za-z0-9._]+)").find(html)?.groupValues?.get(1)
        val image = mediaUrl(
            Regex("\"display_url\":\"([^\"]+)\"").find(html)?.groupValues?.get(1)?.replace("\\/", "/"),
        ) ?: return probe
        return EmbedProbe(
            items = listOf(
                MediaCandidate(
                    url = image,
                    fileName = FileNames.build(user, code, FileNames.extensionFromUrl(image, false)),
                    thumbnailUrl = image,
                    isVideo = false,
                    sourceUrl = url,
                    headers = mediaHeaders(image, cookie),
                ),
            ),
            shape = PostShape.PHOTO,
        )
    }

    private suspend fun scrapeOEmbed(
        context: Context,
        url: String,
        code: String,
        cookie: String?,
    ): List<MediaCandidate> {
        val target = java.net.URLEncoder.encode("https://www.instagram.com/p/$code/", "UTF-8")
        val body = withContext(Dispatchers.IO) {
            Http.get(
                "https://www.instagram.com/api/v1/oembed/?url=$target",
                IgSession.webHeaders(context, cookie, accept = "application/json"),
            )
        } ?: return emptyList()
        return candidatesFromOEmbed(body, url, code, cookie)
    }

    /** Last type check before a single image is saved: the post page says which route it is. */
    private suspend fun scrapePostShape(code: String): PostShape {
        val headers = IgSession.webHeadersFor(
            Http.CRAWLER_UA,
            null,
            accept = "text/html,application/xhtml+xml",
        )
        val html = withContext(Dispatchers.IO) {
            Http.get("https://www.instagram.com/p/$code/", headers)
        } ?: return PostShape.UNKNOWN
        return shapeOfPage(html)
    }

    /**
     * Resolves one story, every active story of a profile, or a highlight. The reel endpoints
     * return the same item shape as posts, so the existing candidate parser handles the media.
     */
    suspend fun resolveStory(context: Context, url: String, target: StoryTarget): Feed {
        val cookie = cookies()
        if (cookie?.contains("sessionid=") != true) {
            return Feed(
                note = "Instagram stories need a signed-in session. Sign in from Settings, then try again.",
                signedIn = false,
            )
        }

        val reelId: String
        val mediaId: String?
        when (target) {
            is StoryTarget.Highlight -> {
                reelId = "highlight:${target.id}"
                mediaId = null
            }
            is StoryTarget.Profile -> {
                reelId = lookupUserId(context, target.username, cookie) ?: return Feed(
                    note = "Instagram returned no profile for @${target.username}. Check the spelling, " +
                        "or sign in again if the session expired.",
                    signedIn = true,
                )
                mediaId = target.mediaId
            }
        }

        val endpoints = if (target is StoryTarget.Highlight) {
            listOf(reelsMedia(reelId))
        } else {
            // The per-user endpoint is the original app's story path; reels_media is the
            // fallback for sessions where that path is unavailable.
            listOf(
                "https://i.instagram.com/api/v1/feed/user/$reelId/reel_media/",
                reelsMedia(reelId),
            )
        }

        var note: String? = null
        for (endpoint in endpoints) {
            val reply = withContext(Dispatchers.IO) {
                Http.getReply(endpoint, igHeaders(context, cookie))
            }
            if (reply == null) {
                note = "Could not reach Instagram. Check your connection and try again."
                continue
            }
            if (!reply.isSuccess) {
                note = storyStatusNote(reply.code)
                continue
            }
            val body = reply.body.orEmpty()
            apiBlock(body)?.let { return Feed(note = it, signedIn = true) }
            val items = storyItems(body, reelId) ?: continue
            val selected = if (mediaId == null) items else selectStory(items, mediaId)
            if (selected == null || selected.length() == 0) {
                note = "That story is no longer available. Stories disappear after 24 hours."
                continue
            }
            val out = itemsToCandidates(selected, url, cookie)
            if (out.isNotEmpty()) return Feed(out, signedIn = true)
            note = "Instagram returned a story without a downloadable file."
        }

        return Feed(note = note ?: noStoryNote(target), signedIn = true)
    }

    private fun reelsMedia(reelId: String): String =
        "https://i.instagram.com/api/v1/feed/reels_media/?reel_ids=$reelId"

    private fun noStoryNote(target: StoryTarget): String = when (target) {
        is StoryTarget.Highlight -> "That highlight is no longer available."
        is StoryTarget.Profile -> if (target.mediaId != null) {
            "That story is no longer available. Stories disappear after 24 hours."
        } else {
            "@${target.username} has no active stories right now."
        }
    }

    private suspend fun lookupUserId(context: Context, username: String, cookie: String?): String? {
        val reply = withContext(Dispatchers.IO) {
            Http.getReply(
                "https://i.instagram.com/api/v1/users/web_profile_info/?username=$username",
                igHeaders(context, cookie),
            )
        } ?: return null
        if (!reply.isSuccess) return null
        return runCatching {
            JSONObject(reply.body.orEmpty())
                .getJSONObject("data").getJSONObject("user")
                .optString("id").ifBlank { null }
        }.getOrNull()
    }

    /** The story endpoints put their items at the root, or inside a `reels` object. */
    private fun storyItems(body: String, reelId: String): JSONArray? {
        val root = runCatching { JSONObject(body) }.getOrNull() ?: return null
        root.optJSONArray("items")?.takeIf { it.length() > 0 }?.let { return it }
        val reels = root.optJSONObject("reels") ?: return null
        reels.optJSONObject(reelId)?.optJSONArray("items")
            ?.takeIf { it.length() > 0 }?.let { return it }
        val keys = reels.keys()
        while (keys.hasNext()) {
            val items = reels.optJSONObject(keys.next())?.optJSONArray("items") ?: continue
            if (items.length() > 0) return items
        }
        return null
    }

    private fun selectStory(items: JSONArray, mediaId: String): JSONArray? {
        val selected = JSONArray()
        for (i in 0 until items.length()) {
            val item = items.optJSONObject(i) ?: continue
            val id = item.optString("id").substringBefore('_')
            if (id == mediaId || item.optString("pk") == mediaId) selected.put(item)
        }
        return selected.takeIf { it.length() > 0 }
    }

    private fun storyStatusNote(code: Int): String = when {
        code == 429 -> "Instagram is rate-limiting this account. Wait a few minutes before loading again."
        code == 401 || code == 403 || code == 404 ->
            "Instagram would not return that story for this session. Check that you follow the " +
                "account, then sign in again if the session expired."
        else -> "Instagram answered $code. Try again in a minute."
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
        val body = withContext(Dispatchers.IO) {
            Http.get(endpoint, webHeaders(context, cookies()))
        } ?: return emptyList()
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

    private val VIDEO_PRODUCT_TYPES = setOf("clips", "reel", "igtv")

    /** `og:url` keeps the media route, so a reel or TV video is recognisable from its page. */
    private val OG_VIDEO_ROUTE = Regex("og:url\"\\s+content=\"[^\"]*/(?:reel|reels|tv)/")

    private const val COVER_ONLY =
        "Instagram returned only the cover image for that video, so nothing was saved. Sign in " +
            "to Instagram from Settings, then try the link again."

    private const val CAROUSEL_PARTIAL =
        "Instagram did not return every slide of that carousel, so nothing was saved. Try the " +
            "link again; if it keeps happening, sign in from Settings."

    private const val PRIVATE_SIGNED_OUT =
        "That account is private. Sign in from Settings with an account that follows it, " +
            "then load again."
}
