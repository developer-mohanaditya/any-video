package io.anyvideo.downloader

import io.anyvideo.downloader.data.FileNames
import io.anyvideo.downloader.net.Instagram
import io.anyvideo.downloader.net.MediaApi
import io.anyvideo.downloader.net.Resolver
import io.anyvideo.downloader.ui.formatDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CoreLogicTest {

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
}
