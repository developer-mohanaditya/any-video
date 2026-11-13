package io.anyvideo.downloader.net

import io.anyvideo.downloader.data.FileNames
import io.anyvideo.downloader.data.MediaCandidate
import io.anyvideo.downloader.util.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Client for a cobalt-compatible resolver API (https://github.com/imputnet/cobalt).
 *
 * The API is one endpoint, `POST <base>/`, answering JSON whose `status` decides the shape:
 * `tunnel`/`redirect` (one file), `picker` (a carousel), `local-processing` (files the server
 * refused to merge) or `error`. Keeping this generic means the app works with the managed
 * cobalt API, a self-hosted instance, or any instance owner who issues a key.
 */
object MediaApi {

    const val UNCONFIGURED = "no resolver API is configured (Settings → Resolver API)"

    /**
     * [retryable] marks failures that are worth another attempt without bothering the user:
     * the instance is busy, or the upstream service handed back nothing this time.
     */
    data class Outcome(
        val candidates: List<MediaCandidate>,
        val error: String? = null,
        val retryable: Boolean = false,
        val code: String? = null,
    )

    fun isConfigured(): Boolean = Prefs.resolverApiBase.isNotBlank()

    /**
     * Identifies the exact reply a request would produce. Cached answers are keyed by this as
     * well as the link: resolving a video and then switching to audio must not hand back the
     * first answer, which is a `.mp4` whose tunnel URL may already be spent.
     */
    fun variant(pageUrl: String): String = if (audioRequest(pageUrl)) {
        "audio/${Prefs.youtubeAudioFormat}/${Prefs.youtubeAudioBitrate}"
    } else {
        "auto/${Prefs.youtubeQuality}"
    }

    suspend fun resolve(pageUrl: String): Outcome = withContext(Dispatchers.IO) {
        val base = Prefs.resolverApiBase
        if (base.isEmpty()) return@withContext Outcome(emptyList(), UNCONFIGURED)

        // YouTube is the one service where cobalt lets the caller choose: a height such as
        // "1080" (or "max"), and `downloadMode=audio` to get just the soundtrack. Both come
        // from the chooser on the home screen.
        val audioOnly = audioRequest(pageUrl)
        val payload = JSONObject()
            .put("url", pageUrl)
            // Ask for the best file the instance can serve; the picker path then hands us
            // every carousel child instead of a single merged file.
            .put("videoQuality", if (audioOnly) "max" else Prefs.youtubeQuality)
            .put("downloadMode", if (audioOnly) "audio" else "auto")
            .put("filenameStyle", "basic")
            .put("alwaysProxy", true)
            .apply {
                if (audioOnly) {
                    put("audioFormat", Prefs.youtubeAudioFormat)
                    // Without this the instance falls back to 128 kbps for every conversion,
                    // whatever the chooser said.
                    put("audioBitrate", Prefs.youtubeAudioBitrate)
                }
            }
            .toString()

        val headers = mutableMapOf("Accept" to "application/json")
        Prefs.resolverApiKey.takeIf { it.isNotEmpty() }?.let { headers["Authorization"] = authorize(it) }

        val reply = Http.postJson("$base/", payload, headers)
            ?: return@withContext Outcome(
                emptyList(),
                "the resolver API at $base could not be reached",
                retryable = true,
            )
        val body = reply.body?.trim().orEmpty()
        if (body.isEmpty()) {
            return@withContext Outcome(
                emptyList(),
                "the resolver API answered HTTP ${reply.code} with an empty body",
                retryable = reply.code >= 500,
            )
        }
        parse(body, pageUrl, audioOnly)
    }

    private fun isYouTube(pageUrl: String): Boolean =
        hostOf(pageUrl).let { it.contains("youtube") || it.contains("youtu.be") }

    /** True when this link will be resolved as a soundtrack rather than a video. */
    private fun audioRequest(pageUrl: String): Boolean =
        Prefs.youtubeAudioOnly && isYouTube(pageUrl)

    /** Accepts a bare key, `Api-Key <key>` or a ready-made `Bearer <token>` (turnstile sessions). */
    private fun authorize(value: String): String = when {
        value.startsWith("Bearer ", true) || value.startsWith("Api-Key ", true) -> value
        else -> "Api-Key $value"
    }

    private fun parse(body: String, pageUrl: String, audioOnly: Boolean): Outcome {
        val root = try {
            JSONObject(body)
        } catch (t: Throwable) {
            return Outcome(emptyList(), "the resolver API answered something that is not JSON")
        }

        return when (root.optString("status")) {
            "tunnel", "redirect" -> {
                val url = root.optString("url")
                if (url.isBlank()) return Outcome(emptyList(), "the resolver API returned no file URL")
                val isVideo = isVideoFile(url) || isVideoFile(root.optString("filename"))
                Outcome(listOf(candidate(url, root.optString("filename"), pageUrl, 0, isVideo, audioOnly = audioOnly)))
            }

            "picker" -> {
                val picker = root.optJSONArray("picker") ?: JSONArray()
                val items = (0 until picker.length()).mapNotNull { index ->
                    val entry = picker.optJSONObject(index) ?: return@mapNotNull null
                    val url = entry.optString("url")
                    if (url.isBlank()) return@mapNotNull null
                    val type = entry.optString("type")
                    val isVideo = type == "video" || (type.isEmpty() && isVideoFile(url))
                    candidate(
                        url = url,
                        apiName = null,
                        pageUrl = pageUrl,
                        index = index,
                        isVideo = isVideo,
                        thumbnail = entry.optString("thumb").takeIf { it.isNotBlank() },
                        audioOnly = audioOnly,
                    )
                }
                if (items.isEmpty()) Outcome(emptyList(), "the resolver API found nothing in that link")
                else Outcome(items)
            }

            "local-processing" -> {
                // The instance offered to tunnel raw streams and let us merge them. Without a
                // bundled muxer a merge reply is not usable, so say so instead of saving half a file.
                if (root.optString("type") == "merge") {
                    return Outcome(
                        emptyList(),
                        "that instance returns separate video/audio streams (local processing); " +
                            "ask the owner to disable it or use another instance",
                    )
                }
                val tunnel = root.optJSONArray("tunnel")
                val url = if (tunnel != null && tunnel.length() > 0) tunnel.optString(0) else ""
                if (url.isBlank()) return Outcome(emptyList(), "the resolver API returned no tunnel URL")
                val output = root.optJSONObject("output")
                val mime = output?.optString("type").orEmpty()
                val isVideo = mime.startsWith("video/") || isVideoFile(url)
                Outcome(
                    listOf(
                        candidate(url, output?.optString("filename"), pageUrl, 0, isVideo, audioOnly = audioOnly),
                    ),
                )
            }

            "error" -> {
                val code = errorCode(root.opt("error"))
                Outcome(
                    emptyList(),
                    describeError(code),
                    retryable = isRetryable(code),
                    code = code,
                )
            }

            else -> Outcome(emptyList(), "the resolver API answered an unknown status")
        }
    }

    /** cobalt's machine-readable code, e.g. `error.api.rate_exceeded`. */
    private fun errorCode(error: Any?): String = when (error) {
        is JSONObject -> error.optString("code")
        is String -> error
        else -> ""
    }.ifBlank { "unknown" }

    /**
     * Failures that are usually gone a moment later: back-pressure from the instance itself
     * (rate limiting), an empty answer from the upstream service, or a temporary outage.
     */
    private val RETRYABLE_CODES = listOf(
        "rate_exceeded",
        "rate_limit",
        "fetch.empty",
        "temporarily_disabled",
        "timeout",
        "internal",
        "unknown_error",
    )

    private fun isRetryable(code: String): Boolean =
        RETRYABLE_CODES.any { code.contains(it, true) }

    /** Turns cobalt's machine-readable codes into something a person can act on. */
    private fun describeError(code: String): String {
        return when {
            code.contains("auth") && code.contains("missing") ->
                "the instance requires a key: add it under Settings → Resolver API ($code)"
            code.contains("rate_exceeded") || code.contains("rate_limit") ->
                "the instance is busy right now ($code), give it a minute"
            code.contains("fetch.empty") ->
                "that service returned nothing this time ($code), try the link again"
            code.contains("link.unsupported") -> "that link is not supported by the instance ($code)"
            code.contains("link.invalid") -> "that link is not valid ($code)"
            code.contains("content.unavailable") -> "the media is unavailable or private ($code)"
            code.contains("content.too_long") -> "that media is too long for the instance's limit ($code)"
            code.contains("service.disabled") -> "the instance has this service disabled ($code)"
            code.contains("service.temporarily_disabled") -> "the instance service is temporarily down ($code)"
            else -> "the resolver API returned `$code`"
        }
    }

    private fun candidate(
        url: String,
        apiName: String?,
        pageUrl: String,
        index: Int,
        isVideo: Boolean,
        thumbnail: String? = null,
        audioOnly: Boolean = false,
    ): MediaCandidate {
        // An audio reply's tunnel URL carries no extension, so fall back to the format we asked
        // for instead of naming a soundtrack .jpg. The request value is not an extension:
        // "best" means the untouched m4a stream, and naming a file `.best` makes it unplayable.
        val extension = extensionOf(apiName) ?: extensionOf(url)
            ?: if (audioOnly) audioExtensionFor(Prefs.youtubeAudioFormat)
            else if (isVideo) "mp4" else "jpg"
        val user = userFor(pageUrl)
        // A link whose id cannot be parsed still gets a stable name, so resolving it twice does
        // not look like two different files.
        val id = idFor(pageUrl) ?: Integer.toHexString(pageUrl.hashCode())
        val suffix = if (index > 0) "${id}_$index" else id
        // YouTube hands back the real video title, which is far more useful in the gallery than
        // an opaque video id. The id stays as a short suffix: two different videos can share a
        // title, and a title-only name made the second one look like it was already saved.
        // Other services keep the original app's username-id convention.
        val name = if (isYouTube(pageUrl)) {
            val title = titleFileName(apiName)
            if (title != null) {
                val base = title.substringBeforeLast('.').ifBlank { title }
                "$base [$id].${title.substringAfterLast('.')}"
            } else {
                FileNames.build(user, suffix, extension)
            }
        } else {
            FileNames.build(user, suffix, extension)
        }
        return MediaCandidate(
            url = url,
            fileName = name,
            // YouTube exposes a stable poster for every video id. Without this the resolver
            // reply has no thumb and every YouTube card showed only the platform mark.
            thumbnailUrl = thumbnail ?: if (isYouTube(pageUrl)) {
                youTubeThumbnail(pageUrl)
            } else if (isVideo || audioOnly) {
                null
            } else {
                url
            },
            isVideo = !audioOnly && (isVideo || extension in VIDEO_EXTENSIONS),
            sourceUrl = pageUrl,
            // Tunnel URLs are self-authorising; a redirect points at the origin CDN. Sending the
            // instance key to either would leak it, so downloads carry no API credentials.
            headers = emptyMap(),
        )
    }

    /** The public YouTube poster for a watch, Shorts, live or youtu.be link. */
    internal fun youTubeThumbnail(pageUrl: String): String? {
        if (!isYouTube(pageUrl)) return null
        val id = idFor(pageUrl) ?: return null
        return "https://i.ytimg.com/vi/$id/hqdefault.jpg"
    }

    /**
     * The title cobalt returns in `filename`, made safe for a gallery entry: path separators and
     * reserved characters become spaces, and the name is kept short enough for MediaStore.
     */
    private fun titleFileName(apiName: String?): String? {
        val raw = apiName?.trim().orEmpty()
        if (raw.isEmpty()) return null
        val extension = extensionOf(raw) ?: return null
        val base = raw.substringBeforeLast('.').trim()
        if (base.isEmpty()) return null
        val cleaned = base
            .replace(Regex("""[\\/:*?"<>|\r\n\t]"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
            .trimEnd('.')
        if (cleaned.isEmpty()) return null
        // cobalt appends the chosen quality, as in "Title (720p, h264)". The resolution is
        // already implied by the file, so the gallery keeps just the title.
        val withoutQuality = cleaned.replace(
            Regex("""\s*\([^()]*?\b\d{3,4}p\b[^()]*\)\s*$"""),
            "",
        ).trim().trimEnd('.')
        val finalName = withoutQuality.ifEmpty { cleaned }
        // Gallery names are bytes, not characters: 100 emoji or CJK characters can pass 255
        // bytes once the id suffix is added, and MediaStore then refuses the file.
        return "${clampTitle(finalName)}.$extension"
    }

    /** Keeps a title within the byte budget a gallery entry can hold, without splitting a glyph. */
    private fun clampTitle(title: String, maxBytes: Int = 170): String {
        if (title.toByteArray(Charsets.UTF_8).size <= maxBytes) return title
        var end = title.length
        while (end > 0 && title.substring(0, end).toByteArray(Charsets.UTF_8).size > maxBytes) {
            end--
            if (end > 0 && title[end - 1].isLowSurrogate()) end--
        }
        return title.substring(0, end).trimEnd()
    }

    /** cobalt's `audioFormat` values mapped to the extension the file really has. */
    private fun audioExtensionFor(format: String): String = when (format.lowercase()) {
        "mp3" -> "mp3"
        "ogg" -> "ogg"
        "opus" -> "opus"
        "wav" -> "wav"
        // "best" is the original muxed stream, which is always m4a on YouTube.
        else -> "m4a"
    }

    private val VIDEO_EXTENSIONS = setOf("mp4", "m4v", "mov", "webm", "mkv")
    private val AUDIO_EXTENSIONS = setOf("mp3", "m4a", "aac", "opus", "ogg", "wav", "flac")
    private val KNOWN_EXTENSIONS = VIDEO_EXTENSIONS + AUDIO_EXTENSIONS +
        setOf("jpg", "jpeg", "png", "webp", "gif", "heic", "avif")

    private fun extensionOf(value: String?): String? {
        if (value.isNullOrBlank()) return null
        val path = value.substringBefore('?').substringBefore('#')
        val extension = path.substringAfterLast('.', "").lowercase()
        return extension.takeIf { it in KNOWN_EXTENSIONS }
    }

    private fun isVideoFile(value: String?): Boolean =
        extensionOf(value)?.let { it in VIDEO_EXTENSIONS } ?: false

    /** Username shown in the filename and in the repost screen. */
    private fun userFor(pageUrl: String): String {
        val host = hostOf(pageUrl)
        return when {
            host.contains("instagram") -> Instagram.usernameFrom(pageUrl) ?: "Instagram"
            host.contains("youtube") || host.contains("youtu.be") -> "YouTube"
            host.contains("twitter") || host == "x.com" || host.endsWith(".x.com") -> "X"
            host.contains("pinterest") -> "Pinterest"
            host.contains("tiktok") -> "TikTok"
            host.contains("facebook") || host.contains("fb.watch") -> "Facebook"
            host.contains("vimeo") -> "Vimeo"
            else -> host.substringAfter("www.").substringBefore('.').ifBlank { "User" }
        }
    }

    /** The post/tweet/video id, so filenames stay recognisable and unique per link. */
    private fun idFor(pageUrl: String): String? {
        val host = hostOf(pageUrl)
        val path = pageUrl.substringBefore('?').substringBefore('#')
        return when {
            host.contains("instagram") -> Instagram.shortcode(pageUrl)
            // youtu.be links, watch?v= links, Shorts, live and embed pages all carry the same
            // eleven character id, including when the share link ends in a slash.
            host.contains("youtu.be") || host.contains("youtube") ->
                Regex("""(?:[?&]v=|/shorts/|/live/|/embed/|youtu\.be/)([A-Za-z0-9_-]{6,})""")
                    .find(pageUrl)?.groupValues?.get(1)
            host.contains("twitter") || host == "x.com" -> Regex("/status/(\\d+)").find(path)?.groupValues?.get(1)
            host.contains("pinterest") -> Regex("/pin/(\\d+)").find(path)?.groupValues?.get(1)
            host.contains("tiktok") -> Regex("/video/(\\d+)").find(path)?.groupValues?.get(1)
            else -> null
        }
    }

    private fun hostOf(url: String): String =
        url.substringAfter("://", url).substringBefore('/').substringBefore('?').lowercase()
}
