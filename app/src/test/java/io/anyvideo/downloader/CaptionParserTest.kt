package io.anyvideo.downloader

import io.anyvideo.downloader.data.CaptionParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CaptionParserTest {

    @Test
    fun `webvtt cues keep timing and clean markup and entities`() {
        val cues = CaptionParser.parse(
            """
            WEBVTT

            00:00:01.000 --> 00:00:03.250 line:90%
            <i>Hello</i> &amp; welcome&#10;friend

            00:00:04.000 --> 00:00:05.000 align:start position:10%
            Second line
            """.trimIndent(),
        )

        assertEquals(2, cues.size)
        assertEquals(1_000L, cues[0].startMs)
        assertEquals(3_250L, cues[0].endMs)
        assertEquals("Hello & welcome\nfriend", cues[0].text)
        assertEquals("Second line", cues[1].text)
    }

    @Test
    fun `srt comma timestamps are accepted`() {
        val cues = CaptionParser.parse(
            """
            1
            00:00:01,000 --> 00:00:02,500
            A subtitle line
            """.trimIndent(),
        )

        assertEquals(1, cues.size)
        assertEquals(1_000L, cues[0].startMs)
        assertEquals(2_500L, cues[0].endMs)
        assertEquals("A subtitle line", cues[0].text)
    }

    @Test
    fun `current returns only the active cue and gaps stay empty`() {
        val cues = CaptionParser.parse(
            """
            WEBVTT

            00:00:01.000 --> 00:00:02.000
            First

            00:00:03.000 --> 00:00:04.000
            Second
            """.trimIndent(),
        )

        val first = CaptionParser.currentCue(cues, 1_500L)
        assertEquals("First", first?.text)
        assertEquals(1_000L, first?.startMs)
        assertNull(CaptionParser.current(cues, 2_000L))
        assertEquals("Second", CaptionParser.current(cues, 3_999L))
        assertNull(CaptionParser.current(cues, 4_000L))
    }

    @Test
    fun `blocks without a timing line are ignored`() {
        val cues = CaptionParser.parse(
            "WEBVTT\n\nnot a cue\n\n00:00:01.000 --> 00:00:02.000\nKept",
        )

        assertEquals(1, cues.size)
        assertEquals("Kept", cues.single().text)
    }
}
