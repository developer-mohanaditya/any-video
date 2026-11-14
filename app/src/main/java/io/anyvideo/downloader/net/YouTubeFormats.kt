package io.anyvideo.downloader.net

import io.anyvideo.downloader.data.MediaCandidate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Reads the qualities a YouTube video really has before the format sheet is shown.
 *
 * The Android player endpoint returns one entry per available stream. We only need the video
 * heights and the best source audio bitrate: the download still goes through the configured
 * resolver, so this probe makes no download URL and keeps no media. Asking for a video height
 * that is not in this reply is what made a 4K choice fail on a 720p video.
 */
object YouTubeFormats {

    /** YouTube's 8K height. Nothing above this is offered to the resolver. */
    const val MAX_HEIGHT = 4320

    /** What the pending link can actually deliver. Empty heights mean the reply had no video. */
    data class Availability(
        val heights: List<Int>,
        val audioKbps: Int?,
    )

    /**
     * The player endpoint rejects old client versions with HTTP 400, and versions at 20.45 and
     * newer stop returning every direct format. These two are a small fallback pair rather than
     * one brittle pinned version. No API key is needed for this metadata request.
     */
    private val CLIENT_VERSIONS = listOf("20.29.38", "20.10.38")
    private const val VISIONOS_UA =
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 15_7_3) AppleWebKit/605.1.15 " +
            "(KHTML, like Gecko) Version/26.0 Safari/605.1.15"

    /** Qualities actually offered for [pageUrl], or null when the probe itself failed. */
    suspend fun available(pageUrl: String): Availability? = withContext(Dispatchers.IO) {
        val videoId = videoId(pageUrl) ?: return@withContext null
        for (version in CLIENT_VERSIONS) {
            val reply = playerReply(videoId, version) ?: continue
            val availability = parseAvailability(reply.body) ?: continue
            return@withContext availability
        }
        null
    }

    /**
     * A H.264 video plus an AAC audio track from YouTube's visionOS player. The Android player
     * hands out adaptive URLs that answer 403 without a proof-of-origin token; the visionOS
     * client returns full length URLs that download normally. The caller merges the two tracks.
     */
    suspend fun direct(pageUrl: String, quality: String): MediaCandidate? =
        withContext(Dispatchers.IO) {
            val requested = quality.trim().toIntOrNull()
            if (requested != null && requested > 1080) return@withContext null
            val body = visionosPlayer(pageUrl) ?: return@withContext null
            parseDirect(body, pageUrl, quality)
        }

    /** The original AAC track as M4A, for when the resolver cannot produce an audio file. */
    suspend fun directAudio(pageUrl: String): MediaCandidate? =
        withContext(Dispatchers.IO) {
            val body = visionosPlayer(pageUrl) ?: return@withContext null
            parseAudio(body, pageUrl)
        }

    private suspend fun visionosPlayer(pageUrl: String): String? {
        val videoId = videoId(pageUrl) ?: return null
        val watch = Http.get(
            "https://www.youtube.com/watch?v=$videoId&bpctr=9999999999&has_verified=1",
            headers = mapOf(
                "Accept-Language" to "en-us,en;q=0.5",
                "Cookie" to "PREF=hl=en&tz=UTC; SOCS=CAI",
                "User-Agent" to Http.DESKTOP_UA,
            ),
        ) ?: return null
        val visitor = visitorData(watch) ?: return null
        val reply = Http.postJson(
            url = "https://www.youtube.com/youtubei/v1/player?prettyPrint=false",
            json = visionosRequest(videoId, visitor),
            headers = mapOf(
                "Accept" to "application/json",
                "Origin" to "https://www.youtube.com",
                "Cookie" to "PREF=hl=en&tz=UTC; SOCS=CAI",
                "X-Goog-Visitor-Id" to visitor,
                "X-Youtube-Client-Name" to "101",
                "X-Youtube-Client-Version" to "1.02",
                "User-Agent" to VISIONOS_UA,
            ),
        ) ?: return null
        return reply.body?.takeIf { reply.isSuccess }
    }

    private fun playerReply(videoId: String, version: String): Http.Reply? {
        val reply = Http.postJson(
            url = "https://www.youtube.com/youtubei/v1/player",
            json = request(videoId, version),
            headers = mapOf(
                "Accept" to "application/json",
                "User-Agent" to
                    "com.google.android.youtube/$version (Linux; U; Android 13; en_US) gzip",
                "X-Youtube-Client-Name" to "3",
                "X-Youtube-Client-Version" to version,
            ),
        )
        return reply?.takeIf { it.isSuccess }
    }

    /**
     * Keeps a saved or selected quality within the range YouTube can serve. A stored stale value
     * such as 8000 would otherwise reach the resolver even though the picker never offers it.
     */
    internal fun cappedQuality(value: String): String {
        val height = value.trim().toIntOrNull() ?: return "max"
        if (height <= 0) return "max"
        return height.coerceAtMost(MAX_HEIGHT).toString()
    }

    private fun visionosRequest(videoId: String, visitor: String): String = JSONObject()
        .put(
            "context",
            JSONObject().put(
                "client",
                JSONObject()
                    .put("clientName", "VISIONOS")
                    .put("clientVersion", "1.02")
                    .put("deviceMake", "Apple")
                    .put("deviceModel", "RealityDevice17,1")
                    .put("osName", "visionOS")
                    .put("osVersion", "26.5.23O471")
                    .put("userAgent", VISIONOS_UA)
                    .put("visitorData", visitor)
                    .put("hl", "en")
                    .put("gl", "US"),
            ),
        )
        .put("videoId", videoId)
        .put("contentCheckOk", true)
        .put("racyCheckOk", true)
        .toString()

    private fun request(videoId: String, version: String): String = JSONObject()
        .put(
            "context",
            JSONObject().put(
                "client",
                JSONObject()
                    .put("clientName", "ANDROID")
                    .put("clientVersion", version)
                    .put("androidSdkVersion", 33)
                    .put("hl", "en")
                    .put("gl", "US"),
            ),
        )
        .put("videoId", videoId)
        .put("contentCheckOk", true)
        .put("racyCheckOk", true)
        .toString()

    /**
     * Pulls the video heights and the highest source audio bitrate from `streamingData`. A reply
     * without streaming data is still a successful probe: live, private or region-locked videos
     * can answer with no formats, and the picker then falls back to "Best".
     */
    internal fun parseAvailability(body: String?): Availability? {
        if (body.isNullOrBlank()) return null
        val root = runCatching { JSONObject(body) }.getOrNull() ?: return null
        // A login wall, region block or removed video is a failed probe, not a video with no
        // formats. The picker then keeps its permissive fallback instead of disabling audio.
        if (root.optJSONObject("playabilityStatus")?.optString("status") != "OK") return null
        val streaming = root.optJSONObject("streamingData")
            ?: return Availability(emptyList(), null)
        val formats = formats(streaming)
        val heights = formats.asSequence()
            .map { it.optInt("height", 0) }
            // Audio-only entries have height 0. 144 is the smallest video format YouTube
            // publishes. Anything above 4320 is capped to 8K rather than dropped.
            .filter { it >= 144 }
            .map { it.coerceAtMost(MAX_HEIGHT) }
            .toSortedSet()
            .sortedDescending()
        val audioKbps = formats.asSequence()
            .filter { it.optInt("height", 0) == 0 }
            .filter { it.optString("mimeType").startsWith("audio/") }
            .map { it.optLong("bitrate", 0L) }
            .filter { it > 0L }
            .maxOrNull()
            ?.div(1000L)
            ?.toInt()
            ?.takeIf { it > 0 }
        return Availability(heights, audioKbps)
    }

    /** Picks an H.264 video and the default AAC track from one visionOS player reply. */
    internal fun parseDirect(body: String?, pageUrl: String, quality: String): MediaCandidate? {
        if (body.isNullOrBlank()) return null
        val root = runCatching { JSONObject(body) }.getOrNull() ?: return null
        if (root.optJSONObject("playabilityStatus")?.optString("status") != "OK") return null
        val streaming = root.optJSONObject("streamingData") ?: return null
        val id = videoId(pageUrl) ?: return null
        val target = (quality.trim().toIntOrNull() ?: 1080).coerceIn(144, 1080)
        val videos = objects(streaming.optJSONArray("adaptiveFormats"))
            .filter {
                val mime = it.optString("mimeType")
                mime.startsWith("video/mp4") &&
                    mime.contains("avc1") &&
                    it.optInt("height", 0) >= 144 &&
                    it.optString("url").isNotBlank()
            }
        // With no stream at or below the target there is nothing this path can serve: the
        // resolver must handle the higher-quality file instead of silently saving a larger one
        // when the caller asked for a 1080p cap.
        val video = videos.filter { it.optInt("height", 0) <= target }
            .maxByOrNull { it.optInt("height", 0) }
            ?: return null
        // A 720 choice must not quietly become a 360 file. Let the resolver try instead.
        val requested = quality.trim().toIntOrNull()
        if (requested != null && video.optInt("height", 0) < requested) return null
        val audio = defaultAudio(streaming) ?: return null
        val title = root.optJSONObject("videoDetails")?.optString("title").orEmpty()
        val height = video.optInt("height", 0)
        return MediaCandidate(
            url = video.optString("url"),
            audioUrl = audio.optString("url"),
            fileName = directName(title, id, height),
            thumbnailUrl = "https://i.ytimg.com/vi/$id/hqdefault.jpg",
            isVideo = true,
            sourceUrl = pageUrl,
        )
    }

    /** The original AAC track as M4A, used when the resolver cannot save the audio. */
    internal fun parseAudio(body: String?, pageUrl: String): MediaCandidate? {
        if (body.isNullOrBlank()) return null
        val root = runCatching { JSONObject(body) }.getOrNull() ?: return null
        if (root.optJSONObject("playabilityStatus")?.optString("status") != "OK") return null
        val streaming = root.optJSONObject("streamingData") ?: return null
        val id = videoId(pageUrl) ?: return null
        val audio = defaultAudio(streaming) ?: return null
        val title = root.optJSONObject("videoDetails")?.optString("title").orEmpty()
        return MediaCandidate(
            url = audio.optString("url"),
            fileName = audioName(title, id),
            thumbnailUrl = "https://i.ytimg.com/vi/$id/hqdefault.jpg",
            isVideo = false,
            sourceUrl = pageUrl,
            chunked = true,
        )
    }

    private fun defaultAudio(streaming: JSONObject): JSONObject? {
        val audios = objects(streaming.optJSONArray("adaptiveFormats"))
            .filter {
                it.optString("mimeType").startsWith("audio/mp4") &&
                    it.optString("mimeType").contains("mp4a") &&
                    it.optString("url").isNotBlank()
            }
        return audios.filter {
            it.optJSONObject("audioTrack")?.optBoolean("audioIsDefault") == true
        }.maxByOrNull { it.optLong("bitrate", 0L) }
            ?: audios.maxByOrNull { it.optLong("bitrate", 0L) }
    }

    private fun visitorData(html: String): String? = Regex("\"visitorData\":\"(.*?)\"")
        .find(html)
        ?.groupValues?.get(1)
        ?.replace("\\u003d", "=")
        ?.replace("\\u0026", "&")
        ?.replace("\\/", "/")
        ?.takeIf { it.isNotBlank() }

    private fun directName(title: String, id: String, height: Int): String =
        "${clampBytes(cleanedTitle(title), 160)} [$id] (${height}p).mp4"

    private fun audioName(title: String, id: String): String =
        "${clampBytes(cleanedTitle(title), 160)} [$id].m4a"

    private fun cleanedTitle(title: String): String = title
        .replace(Regex("""[\\/:*?"<>|\r\n\t]"""), " ")
        .replace(Regex("""\s+"""), " ")
        .trim()
        .trimEnd('.')
        .ifBlank { "YouTube" }

    private fun clampBytes(value: String, maxBytes: Int): String {
        if (value.toByteArray(Charsets.UTF_8).size <= maxBytes) return value
        var end = value.length
        while (end > 0 && value.substring(0, end).toByteArray(Charsets.UTF_8).size > maxBytes) {
            end--
            if (end > 0 && value[end - 1].isLowSurrogate()) end--
        }
        return value.substring(0, end).trimEnd()
    }

    private fun formats(streaming: JSONObject): List<JSONObject> {
        val all = ArrayList<JSONObject>()
        for (name in arrayOf("formats", "adaptiveFormats")) {
            all += objects(streaming.optJSONArray(name))
        }
        return all
    }

    private fun objects(formats: JSONArray?): List<JSONObject> {
        if (formats == null) return emptyList()
        val result = ArrayList<JSONObject>(formats.length())
        for (index in 0 until formats.length()) {
            formats.optJSONObject(index)?.let(result::add)
        }
        return result
    }

    /** The watch, Shorts, live, embed and youtu.be forms all carry the same eleven-character id. */
    internal fun videoId(pageUrl: String): String? {
        val host = pageUrl.substringAfter("://", "")
            .substringBefore('/')
            .substringBefore('?')
            .lowercase()
            .substringAfter('@')
        val youTubeHost = host == "youtube.com" ||
            host.endsWith(".youtube.com") ||
            host == "youtube-nocookie.com" ||
            host.endsWith(".youtube-nocookie.com") ||
            host == "youtu.be" ||
            host.endsWith(".youtu.be")
        if (!youTubeHost) return null
        return Regex(
            """(?:[?&]v=|/shorts/|/live/|/embed/|youtu\.be/)([A-Za-z0-9_-]{6,})""",
        ).find(pageUrl)?.groupValues?.get(1)
    }
}
