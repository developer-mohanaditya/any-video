package io.anyvideo.downloader

import io.anyvideo.downloader.net.MediaApi
import io.anyvideo.downloader.net.YouTubeChoice
import org.junit.Assert.assertEquals
import org.junit.Test

class MediaApiChoiceTest {

    private val videoUrl = "https://www.youtube.com/watch?v=dQw4w9WgXcQ"

    @Test
    fun `per-request audio bitrate is used instead of the saved preference`() {
        val choice = YouTubeChoice(
            audioOnly = true,
            quality = "max",
            audioFormat = "mp3",
            audioBitrate = "128",
        )

        assertEquals("audio/mp3/128", MediaApi.variant(videoUrl, choice))
    }

    @Test
    fun `per-request video quality is capped at 8k`() {
        val choice = YouTubeChoice(
            audioOnly = false,
            quality = "8000",
            audioFormat = "mp3",
            audioBitrate = "320",
        )

        assertEquals("auto/4320", MediaApi.variant(videoUrl, choice))
    }
}
