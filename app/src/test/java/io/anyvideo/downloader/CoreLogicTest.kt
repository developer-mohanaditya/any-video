package io.anyvideo.downloader

import io.anyvideo.downloader.data.FileNames
import io.anyvideo.downloader.net.IgSession
import io.anyvideo.downloader.net.Instagram
import io.anyvideo.downloader.net.MediaApi
import io.anyvideo.downloader.net.Resolver
import io.anyvideo.downloader.ui.formatDuration
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CoreLogicTest {

    @Test
    fun `instagram web requests use the browser identity and safe cookies`() {
        val headers = IgSession.webHeadersFor(
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) Mobile Safari/537.36",
            "sessionid=abc; csrftoken=xyz\u0001bad",
        )
        assertEquals(
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) Mobile Safari/537.36",
            headers["User-Agent"],
        )
        assertEquals("936619743392459", headers["X-IG-App-ID"])
        assertEquals("sessionid=abc; csrftoken=xyzbad", headers["Cookie"])
        assertEquals("xyzbad", headers["X-CSRFToken"])
        assertEquals("same-origin", headers["Sec-Fetch-Site"])
    }

    @Test
    fun `filename keeps the original convention and round-trips`() {
        val name = FileNames.build("some.user", "C1a2B3c4", "mp4")
        assertEquals("some.user-___C1a2B3c4___-.mp4", name)

        val (user, id) = FileNames.parse(name)
        assertEquals("some.user", user)
        assertEquals("C1a2B3c4", id)
    }

    @Test
    fun `duration is shown as minutes and seconds, or hours when needed`() {
        assertEquals("0:00", formatDuration(0))
        assertEquals("1:01", formatDuration(61_000))
        assertEquals("59:59", formatDuration(3_599_000))
        assertEquals("1:01:01", formatDuration(3_661_000))
        assertEquals("2:00:00", formatDuration(7_200_000))
    }

    @Test
    fun `filename falls back when the username is missing`() {
        val name = FileNames.build(null, "12345", "jpg")
        assertTrue(name.startsWith("User-___12345___-"))
        assertEquals("12345", FileNames.parse(name).second)
    }

    @Test
    fun `youtube preview thumbnail is derived from every video link shape`() {
        assertEquals(
            "https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg",
            MediaApi.youTubeThumbnail("https://www.youtube.com/watch?v=dQw4w9WgXcQ"),
        )
        assertEquals(
            "https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg",
            MediaApi.youTubeThumbnail("https://youtu.be/dQw4w9WgXcQ?si=abc"),
        )
        assertNull(MediaApi.youTubeThumbnail("https://www.youtube.com/"))
    }

    @Test
    fun `extension detection prefers the known suffix`() {
        assertEquals("mp4", FileNames.extensionFromUrl("https://cdn.example/x/video.mp4?token=abc", true))
        assertEquals("jpg", FileNames.extensionFromUrl("https://cdn.example/p/photo.jpg?x=1", false))
        assertEquals("jpg", FileNames.extensionFromUrl("https://cdn.example/no-extension", false))
        assertEquals("mp4", FileNames.extensionFromUrl("https://cdn.example/no-extension", true))
    }

    @Test
    fun `video detection matches the extractor link shape`() {
        assertTrue(FileNames.looksLikeVideo("https://cdn.example/get?token=abc&dl=1"))
        assertTrue(FileNames.looksLikeVideo("https://cdn.example/file.mp4"))
        assertTrue(!FileNames.looksLikeVideo("https://cdn.example/photo.jpg"))
    }

    @Test
    fun `instagram shortcode is read from every post url shape`() {
        assertEquals("C1a2B3c4", Instagram.shortcode("https://www.instagram.com/p/C1a2B3c4/?igsh=abc"))
        assertEquals("C1a2B3c4", Instagram.shortcode("https://www.instagram.com/reel/C1a2B3c4/"))
        assertEquals("C1a2B3c4", Instagram.shortcode("https://www.instagram.com/tv/C1a2B3c4"))
        assertNull(Instagram.shortcode("https://www.instagram.com/some.profile/"))
    }

    @Test
    fun `story links distinguish one story, a profile tray and a highlight`() {
        assertEquals(
            Instagram.StoryTarget.Profile("some.profile", "3344556677"),
            Instagram.storyTarget("https://www.instagram.com/stories/some.profile/3344556677/?igsh=abc"),
        )
        assertEquals(
            Instagram.StoryTarget.Profile("some.profile"),
            Instagram.storyTarget("https://www.instagram.com/stories/some.profile/"),
        )
        assertEquals(
            Instagram.StoryTarget.Highlight("17880160963012870"),
            Instagram.storyTarget("https://www.instagram.com/stories/highlights/17880160963012870/"),
        )
        assertNull(Instagram.storyTarget("https://www.instagram.com/some.profile/"))
    }

    @Test
    fun `story normalisation keeps the story path and drops tracking`() {
        assertEquals(
            "https://www.instagram.com/stories/some.profile/3344556677/",
            Resolver.normalize(
                "https://www.instagram.com/stories/some.profile/3344556677/?igsh=abc",
            ),
        )
    }

    @Test
    fun `profile username is not mistaken for a post route`() {
        assertEquals("some.profile", Instagram.usernameFrom("https://www.instagram.com/some.profile/?hl=en"))
        assertNull(Instagram.usernameFrom("https://www.instagram.com/p/C1a2B3c4/"))
        assertNull(Instagram.usernameFrom("https://www.instagram.com/reels/"))
    }

    @Test
    fun `media id is the numeric shortcode decoding`() {
        val id = Instagram.mediaId("B")
        assertEquals("1", id)
        assertTrue(Instagram.mediaId("C1a2B3c4").all(Char::isDigit))
    }

    @Test
    fun `instagram embed context exposes a single image`() {
        val context = """
            {"gql_data":{"shortcode_media":{"id":"111","owner":{"username":"tester"},"display_url":"https://cdn.example/photo.jpg"}}}
        """.trimIndent()
        val html = """<script>"init",[],[{"isRichEmbed":false,"contextJSON":${JSONObject.quote(context)}}]]</script>"""

        val candidates = Instagram.parseEmbed(html, "https://www.instagram.com/p/C1a2B3c4/").items

        assertEquals(1, candidates.size)
        assertEquals("https://cdn.example/photo.jpg", candidates[0].url)
        assertEquals(false, candidates[0].isVideo)
        assertTrue(candidates[0].fileName.startsWith("tester-___111___-"))
    }

    @Test
    fun `instagram embed context keeps every carousel child`() {
        val context = """
            {"gql_data":{"shortcode_media":{"id":"111","owner":{"username":"tester"},"edge_sidecar_to_children":{"edges":[
              {"node":{"id":"one","display_url":"https://cdn.example/one.jpg"}},
              {"node":{"id":"two","display_url":"https://cdn.example/two.jpg"}}
            ]}}}}
        """.trimIndent()
        val html = """<script>"init",[],[{"contextJSON":${JSONObject.quote(context)}}]]</script>"""

        val candidates = Instagram.parseEmbed(html, "https://www.instagram.com/p/C1a2B3c4/").items

        assertEquals(2, candidates.size)
        assertEquals("https://cdn.example/one.jpg", candidates[0].url)
        assertEquals("https://cdn.example/two.jpg", candidates[1].url)
    }

    @Test
    fun `instagram oembed is a usable image fallback`() {
        val body = """
            {"thumbnail_url":"https://scontent.example/photo.jpg","author_name":"tester","media_id":"4026078363327388337_9402315705"}
        """.trimIndent()

        val candidates = Instagram.candidatesFromOEmbed(
            body,
            "https://www.instagram.com/p/C1a2B3c4/",
            "C1a2B3c4",
        )

        assertEquals(1, candidates.size)
        assertEquals("https://scontent.example/photo.jpg", candidates[0].url)
        assertEquals(false, candidates[0].isVideo)
        assertTrue(candidates[0].fileName.startsWith("tester-___4026078363327388337___-"))
    }

    @Test
    fun `instagram oembed without an image returns nothing`() {
        val candidates = Instagram.candidatesFromOEmbed(
            """{"author_name":"tester"}""",
            "https://www.instagram.com/p/C1a2B3c4/",
            "C1a2B3c4",
        )

        assertTrue(candidates.isEmpty())
    }

    @Test
    fun `instagram graphql post payload is wrapped as one item`() {
        val items = Instagram.parseMediaItems(
            """{"graphql":{"shortcode_media":{"id":"111","display_url":"https://cdn.example/photo.jpg"}}}""",
        )

        assertEquals(1, items?.length())
        assertEquals("111", items?.optJSONObject(0)?.optString("id"))
    }

    @Test
    fun `instagram carousel skips missing urls and keeps usable fallbacks`() {
        val payload = JSONObject(
            """
            {"items":[{"id":"parent","user":{"username":"tester"},"carousel_media":[
              {"id":"fallback","image_versions2":{"candidates":[{"url":""}]},"display_url":"https://cdn.example/fallback.jpg"},
              {"id":"missing"},
              {"id":"video","video_versions":[{"url":""},{"url":"https://cdn.example/video.mp4"}]},
              {"id":"relative","display_url":"//cdn.example/protocol.jpg"}
            ]}]}
            """.trimIndent(),
        )
        val candidates = Instagram.itemsToCandidates(
            payload.getJSONArray("items"),
            "https://www.instagram.com/p/C1a2B3c4/",
        )

        assertEquals(3, candidates.size)
        assertEquals("https://cdn.example/fallback.jpg", candidates[0].url)
        assertEquals(false, candidates[0].isVideo)
        assertEquals("https://cdn.example/video.mp4", candidates[1].url)
        assertEquals(true, candidates[1].isVideo)
        assertEquals("https://cdn.example/protocol.jpg", candidates[2].url)
    }

    @Test
    fun `instagram carousel with no usable media urls returns nothing`() {
        val payload = JSONObject(
            """
            {"items":[{"id":"parent","user":{"username":"tester"},"carousel_media":[
              {"id":"missing"},
              {"id":"blank","image_versions2":{"candidates":[{"url":""}]}}
            ]}]}
            """.trimIndent(),
        )
        val candidates = Instagram.itemsToCandidates(
            payload.getJSONArray("items"),
            "https://www.instagram.com/p/C1a2B3c4/",
        )

        assertTrue(candidates.isEmpty())
    }

    @Test
    fun `instagram link normalisation rewrites reel and tv routes`() {
        assertEquals(
            "https://www.instagram.com/p/C1a2B3c4/",
            Resolver.normalize("https://www.instagram.com/reel/C1a2B3c4/?utm_source=ig_web"),
        )
        assertEquals(
            "https://www.instagram.com/p/C1a2B3c4/",
            Resolver.normalize("https://www.instagram.com/tv/C1a2B3c4/"),
        )
    }

    @Test
    fun `supported hosts cover the app's advertised sites`() {
        assertTrue(Resolver.isSupported("https://www.tiktok.com/@user/video/123"))
        assertTrue(Resolver.isSupported("https://vm.tiktok.com/abc"))
        assertTrue(Resolver.isSupported("https://www.facebook.com/watch?v=1"))
        assertTrue(!Resolver.isSupported("https://example.org/file.mp4"))
    }

    @Test
    fun `instagram video node without a stream is never saved as its cover`() {
        val payload = JSONObject(
            """
            {"items":[{"id":"parent","user":{"username":"tester"},"carousel_media":[
              {"id":"novideo","is_video":true,"display_url":"https://cdn.example/cover.jpg"},
              {"id":"emptyversions","video_versions":[],"display_url":"https://cdn.example/cover2.jpg"},
              {"id":"photo","display_url":"https://cdn.example/photo.jpg"}
            ]}]}
            """.trimIndent(),
        )
        val candidates = Instagram.itemsToCandidates(
            payload.getJSONArray("items"),
            "https://www.instagram.com/p/C1a2B3c4/",
        )

        assertEquals(1, candidates.size)
        assertEquals("https://cdn.example/photo.jpg", candidates[0].url)
        assertEquals(false, candidates[0].isVideo)
    }

    @Test
    fun `instagram embed reports a carousel and flags a slide it could not read`() {
        val context = """
            {"gql_data":{"shortcode_media":{"id":"111","owner":{"username":"tester"},"edge_sidecar_to_children":{"edges":[
              {"node":{"id":"one","display_url":"https://cdn.example/one.jpg"}},
              {"node":{"id":"two","is_video":true,"display_url":"https://cdn.example/two-cover.jpg"}}
            ]}}}}
        """.trimIndent()
        val html = """<script>"init",[],[{"contextJSON":${JSONObject.quote(context)}}]]</script>"""

        val probe = Instagram.parseEmbed(html, "https://www.instagram.com/p/C1a2B3c4/")

        assertEquals(Instagram.PostShape.CAROUSEL, probe.shape)
        assertEquals(1, probe.items.size)
        assertEquals("https://cdn.example/one.jpg", probe.items[0].url)
        assertTrue(probe.incomplete)
    }

    @Test
    fun `instagram embed keeps a video stream instead of its cover`() {
        val context = """
            {"context":{"type":"GraphVideo"},"gql_data":{"shortcode_media":{"id":"111","is_video":true,"video_url":"https://cdn.example/clip.mp4","display_url":"https://cdn.example/cover.jpg","owner":{"username":"tester"}}}}
        """.trimIndent()
        val html = """<script>"init",[],[{"contextJSON":${JSONObject.quote(context)}}]]</script>"""

        val probe = Instagram.parseEmbed(html, "https://www.instagram.com/p/C1a2B3c4/")

        assertEquals(Instagram.PostShape.VIDEO, probe.shape)
        assertEquals(1, probe.items.size)
        assertEquals("https://cdn.example/clip.mp4", probe.items[0].url)
        assertTrue(probe.items[0].isVideo)
    }

    @Test
    fun `instagram embed shape separates a photo shell from a video reply`() {
        val photo = """{"isRichEmbed":false,"isSidecar":false,"contextJSON":null}"""
        val sidecar = """{"isSidecar":true,"contextJSON":null}"""
        val video =
            """<script>{"contextJSON":"{\"gql_data\":{\"shortcode_media\":{\"is_video\":true,\"video_url\":\"https://cdn.example/v.mp4\"}}}"}</script>"""

        assertEquals(Instagram.PostShape.PHOTO, Instagram.shapeOfHtml(photo))
        assertEquals(Instagram.PostShape.CAROUSEL, Instagram.shapeOfHtml(sidecar))
        assertEquals(Instagram.PostShape.VIDEO, Instagram.shapeOfHtml(video))
        assertEquals(
            Instagram.PostShape.UNKNOWN,
            Instagram.shapeOfHtml("<!doctype html><title>Instagram</title>"),
        )
    }

    @Test
    fun `instagram post page marks a reel route as video`() {
        val reel = """<meta property="og:url" content="https://www.instagram.com/user/reel/C1a2B3c4/"><meta property="og:image" content="https://cdn.example/cover.jpg">"""
        val photo = """<meta property="og:url" content="https://www.instagram.com/user/p/C1a2B3c4/"><meta property="og:image" content="https://cdn.example/photo.jpg">"""

        assertEquals(Instagram.PostShape.VIDEO, Instagram.shapeOfPage(reel))
        assertEquals(Instagram.PostShape.PHOTO, Instagram.shapeOfPage(photo))
    }

    @Test
    fun `instagram only calls a loss a missing slide when the post had several`() {
        val single = Instagram.tallyItems(
            JSONObject(
                """{"items":[{"id":"v","is_video":true,"display_url":"https://cdn.example/cover.jpg"}]}""",
            ).getJSONArray("items"),
            "https://www.instagram.com/p/C1a2B3c4/",
        )
        val carousel = Instagram.tallyItems(
            JSONObject(
                """
                {"items":[{"id":"p","carousel_media":[
                  {"id":"one","display_url":"https://cdn.example/one.jpg"},
                  {"id":"novideo","is_video":true,"display_url":"https://cdn.example/cover.jpg"},
                  {"id":"missing"}
                ]}]}
                """.trimIndent(),
            ).getJSONArray("items"),
            "https://www.instagram.com/p/C1a2B3c4/",
        )

        assertTrue(single.items.isEmpty())
        assertTrue(!single.incomplete)
        assertEquals(1, carousel.items.size)
        assertTrue(carousel.incomplete)
    }
}
