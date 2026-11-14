package io.anyvideo.downloader

import io.anyvideo.downloader.util.audioBitrateToSave
import org.junit.Assert.assertEquals
import org.junit.Test

class AudioChoiceTest {

    @Test
    fun `automatic 128k fallback does not overwrite saved 320k`() {
        assertEquals(
            "320",
            audioBitrateToSave(saved = "320", effective = "128", explicitlyChosen = false),
        )
    }

    @Test
    fun `explicit 128k tap saves 128k`() {
        assertEquals(
            "128",
            audioBitrateToSave(saved = "320", effective = "128", explicitlyChosen = true),
        )
    }

    @Test
    fun `explicit 320k tap saves 320k`() {
        assertEquals(
            "320",
            audioBitrateToSave(saved = "128", effective = "320", explicitlyChosen = true),
        )
    }
}
