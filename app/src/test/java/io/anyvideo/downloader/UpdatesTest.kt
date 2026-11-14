package io.anyvideo.downloader

import io.anyvideo.downloader.update.Updates
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UpdatesTest {

    private val latest = """
        {
          "tag_name": "v1.3.7",
          "html_url": "https://github.com/developer-mohanaditya/any-video/releases/tag/v1.3.7",
          "body": "Release notes",
          "assets": [
            {
              "name": "AnyVideo-1.3.7.apk",
              "size": 1690490,
              "browser_download_url": "https://github.com/developer-mohanaditya/any-video/releases/download/v1.3.7/AnyVideo-1.3.7.apk"
            }
          ]
        }
    """.trimIndent()

    @Test
    fun `update opens the APK asset instead of the release page`() {
        val release = Updates.parseLatest(JSONObject(latest), "1.3.6")!!

        assertEquals("1.3.7", release.name)
        assertEquals(
            "https://github.com/developer-mohanaditya/any-video/releases/download/v1.3.7/AnyVideo-1.3.7.apk",
            release.downloadUrl,
        )
        assertEquals(
            "https://github.com/developer-mohanaditya/any-video/releases/tag/v1.3.7",
            release.pageUrl,
        )
        assertEquals("AnyVideo-1.3.7.apk", release.apkName)
        assertEquals(1_690_490L, release.size)
    }

    @Test
    fun `the same version is not offered again`() {
        assertNull(Updates.parseLatest(JSONObject(latest), "1.3.7"))
    }

    @Test
    fun `a release without an APK falls back to its page`() {
        val noApk = JSONObject(latest)
            .put("assets", org.json.JSONArray())

        val release = Updates.parseLatest(noApk, "1.3.6")!!

        assertEquals(release.pageUrl, release.downloadUrl)
        assertEquals("", release.apkName)
        assertEquals(0L, release.size)
    }
}
