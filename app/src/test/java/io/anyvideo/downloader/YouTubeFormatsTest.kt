package io.anyvideo.downloader

import io.anyvideo.downloader.net.YouTubeFormats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class YouTubeFormatsTest {

    @Test
    fun `quality above 8k is capped before the resolver`() {
        assertEquals("4320", YouTubeFormats.cappedQuality("8000"))
        assertEquals("4320", YouTubeFormats.cappedQuality("4321"))
    }

    @Test
    fun `8k and lower qualities are kept`() {
        assertEquals("4320", YouTubeFormats.cappedQuality("4320"))
        assertEquals("2160", YouTubeFormats.cappedQuality("2160"))
        assertEquals("max", YouTubeFormats.cappedQuality("max"))
    }

    @Test
    fun `invalid quality falls back to best`() {
        assertEquals("max", YouTubeFormats.cappedQuality(""))
        assertEquals("max", YouTubeFormats.cappedQuality("0"))
        assertEquals("max", YouTubeFormats.cappedQuality("not-a-height"))
    }

    @Test
    fun `watch shorts live embed and short links all yield the id`() {
        assertEquals("dQw4w9WgXcQ", YouTubeFormats.videoId("https://www.youtube.com/watch?v=dQw4w9WgXcQ"))
        assertEquals("dQw4w9WgXcQ", YouTubeFormats.videoId("https://youtube.com/shorts/dQw4w9WgXcQ"))
        assertEquals("dQw4w9WgXcQ", YouTubeFormats.videoId("https://youtube.com/live/dQw4w9WgXcQ"))
        assertEquals("dQw4w9WgXcQ", YouTubeFormats.videoId("https://youtube.com/embed/dQw4w9WgXcQ"))
        assertEquals("dQw4w9WgXcQ", YouTubeFormats.videoId("https://youtu.be/dQw4w9WgXcQ"))
        assertEquals(
            "dQw4w9WgXcQ",
            YouTubeFormats.videoId("https://m.youtube.com/watch?v=dQw4w9WgXcQ&si=tracking"),
        )
        assertEquals(
            "dQw4w9WgXcQ",
            YouTubeFormats.videoId("https://music.youtube.com/watch?v=dQw4w9WgXcQ"),
        )
        assertEquals(
            "dQw4w9WgXcQ",
            YouTubeFormats.videoId("https://youtu.be/dQw4w9WgXcQ?si=tracking"),
        )
        assertEquals(
            "dQw4w9WgXcQ",
            YouTubeFormats.videoId("https://youtube.com/shorts/dQw4w9WgXcQ?feature=share"),
        )
    }

    @Test
    fun `non-video links yield no id`() {
        assertNull(YouTubeFormats.videoId("https://www.youtube.com/@channel"))
        assertNull(YouTubeFormats.videoId("https://example.com/watch?v=dQw4w9WgXcQ"))
    }

    @Test
    fun `parser caps 16k at 8k and reads the highest audio-only bitrate`() {
        val body = """
            {
              "playabilityStatus": {"status": "OK"},
              "streamingData": {
                "formats": [
                  {"height": 360, "mimeType": "video/mp4", "bitrate": 700000}
                ],
                "adaptiveFormats": [
                  {"height": 8640, "mimeType": "video/webm"},
                  {"height": 4320, "mimeType": "video/webm"},
                  {"height": 2160, "mimeType": "video/mp4"},
                  {"mimeType": "audio/webm; codecs=\"opus\"", "bitrate": 148999},
                  {"mimeType": "audio/mp4; codecs=\"mp4a.40.2\"", "bitrate": 130000}
                ]
              }
            }
        """.trimIndent()

        assertEquals(
            YouTubeFormats.Availability(listOf(4320, 2160, 360), 148),
            YouTubeFormats.parseAvailability(body),
        )
    }

    @Test
    fun `parser reports no audio when only video formats exist`() {
        val body = """
            {
              "playabilityStatus": {"status": "OK"},
              "streamingData": {
                "formats": [{"height": 720, "mimeType": "video/mp4"}],
                "adaptiveFormats": [{"height": 1080, "mimeType": "video/mp4"}]
              }
            }
        """.trimIndent()

        assertEquals(
            YouTubeFormats.Availability(listOf(1080, 720), null),
            YouTubeFormats.parseAvailability(body),
        )
    }

    @Test
    fun `parser accepts a successful reply with no streaming data`() {
        assertEquals(
            YouTubeFormats.Availability(emptyList(), null),
            YouTubeFormats.parseAvailability("""{"playabilityStatus":{"status":"OK"}}"""),
        )
    }

    @Test
    fun `parser treats a blocked playability status as a failed probe`() {
        assertNull(
            YouTubeFormats.parseAvailability(
                """{"playabilityStatus":{"status":"LOGIN_REQUIRED"}}""",
            ),
        )
    }

    @Test
    fun `parser rejects malformed json`() {
        assertNull(YouTubeFormats.parseAvailability("{not json"))
        assertNull(YouTubeFormats.parseAvailability(""))
        assertNull(YouTubeFormats.parseAvailability(null))
    }

    private val directBody = """
        {
          "playabilityStatus": {"status": "OK"},
          "videoDetails": {"title": "9. How to value a company?"},
          "streamingData": {
            "adaptiveFormats": [
              {"height": 1080, "mimeType": "video/mp4; codecs=\"avc1.640028\"", "url": "https://video-1080"},
              {"height": 720, "mimeType": "video/mp4; codecs=\"avc1.64001F\"", "url": "https://video-720"},
              {"mimeType": "audio/mp4; codecs=\"mp4a.40.2\"", "bitrate": 192000, "url": "https://audio-dubbed"},
              {"mimeType": "audio/mp4; codecs=\"mp4a.40.2\"", "bitrate": 128000, "audioTrack": {"audioIsDefault": true}, "url": "https://audio-original"}
            ]
          }
        }
    """.trimIndent()

    @Test
    fun `direct path picks the requested video and the original audio track`() {
        val candidate = YouTubeFormats.parseDirect(
            directBody,
            "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
            "720",
        )!!

        assertEquals("https://video-720", candidate.url)
        assertEquals("https://audio-original", candidate.audioUrl)
        assertEquals("9. How to value a company [dQw4w9WgXcQ] (720p).mp4", candidate.fileName)
    }

    @Test
    fun `direct path picks 1080 when it is requested`() {
        val candidate = YouTubeFormats.parseDirect(
            directBody,
            "https://youtube.com/shorts/dQw4w9WgXcQ",
            "1080",
        )!!

        assertEquals("https://video-1080", candidate.url)
        assertEquals("9. How to value a company [dQw4w9WgXcQ] (1080p).mp4", candidate.fileName)
    }

    @Test
    fun `direct path keeps the best stream at or below 1080 for best quality`() {
        val candidate = YouTubeFormats.parseDirect(
            directBody,
            "https://youtu.be/dQw4w9WgXcQ?si=tracking",
            "max",
        )!!

        assertEquals("https://video-1080", candidate.url)
    }

    @Test
    fun `direct path declines when every stream is above the 1080 cap`() {
        val only4k = """
            {
              "playabilityStatus": {"status": "OK"},
              "streamingData": {
                "adaptiveFormats": [
                  {"height": 2160, "mimeType": "video/mp4; codecs=\"avc1.640033\"", "url": "https://video-2160"},
                  {"mimeType": "audio/mp4; codecs=\"mp4a.40.2\"", "audioTrack": {"audioIsDefault": true}, "url": "https://audio"}
                ]
              }
            }
        """.trimIndent()

        assertNull(
            YouTubeFormats.parseDirect(
                only4k,
                "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
                "max",
            ),
        )
    }

    @Test
    fun `direct path refuses a video with no audio track`() {
        val videoOnly = """
            {
              "playabilityStatus": {"status": "OK"},
              "streamingData": {
                "adaptiveFormats": [
                  {"height": 720, "mimeType": "video/mp4; codecs=\"avc1.64001F\"", "url": "https://video-720"}
                ]
              }
            }
        """.trimIndent()

        assertNull(
            YouTubeFormats.parseDirect(
                videoOnly,
                "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
                "720",
            ),
        )
    }

    @Test
    fun `direct path rejects a blocked video`() {
        assertNull(
            YouTubeFormats.parseDirect(
                """{"playabilityStatus":{"status":"LOGIN_REQUIRED"}}""",
                "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
                "720",
            ),
        )
    }

    @Test
    fun `direct path does not downgrade a requested 720 to 360`() {
        val only360 = """
            {
              "playabilityStatus": {"status": "OK"},
              "streamingData": {
                "adaptiveFormats": [
                  {"height": 360, "mimeType": "video/mp4; codecs=\"avc1.4D401E\"", "url": "https://video-360"},
                  {"mimeType": "audio/mp4; codecs=\"mp4a.40.2\"", "audioTrack": {"audioIsDefault": true}, "url": "https://audio"}
                ]
              }
            }
        """.trimIndent()

        assertNull(
            YouTubeFormats.parseDirect(
                only360,
                "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
                "720",
            ),
        )
    }

    @Test
    fun `direct audio saves the original AAC track as M4A`() {
        val candidate = YouTubeFormats.parseAudio(
            directBody,
            "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
        )!!

        assertEquals("https://audio-original", candidate.url)
        assertEquals("9. How to value a company [dQw4w9WgXcQ].m4a", candidate.fileName)
        assertEquals(false, candidate.isVideo)
        assertEquals(true, candidate.chunked)
    }
}
