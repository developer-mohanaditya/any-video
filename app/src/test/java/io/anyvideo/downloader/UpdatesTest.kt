package io.anyvideo.downloader

import io.anyvideo.downloader.update.Updates
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
              "digest": "sha256:aa11",
              "browser_download_url": "https://github.com/developer-mohanaditya/any-video/releases/download/v1.3.7/AnyVideo-1.3.7.apk"
            }
          ]
        }
    """.trimIndent()

    @Test
    fun `update uses the APK asset for an in-app download`() {
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
        assertEquals("aa11", release.sha256)
    }

    @Test
    fun `the same version is not offered when the APK is unchanged`() {
        assertNull(Updates.parseLatest(JSONObject(latest), "1.3.7", "aa11"))
    }

    @Test
    fun `a same-version APK with a different hash is offered as a hotfix`() {
        val release = Updates.parseLatest(JSONObject(latest), "1.3.7", "different")!!

        assertEquals("1.3.7", release.name)
        assertEquals("aa11", release.sha256)
    }

    @Test
    fun `debug builds do not offer the same version without a hash`() {
        assertNull(Updates.parseLatest(JSONObject(latest), "1.3.7"))
    }

    @Test
    fun `a release without an APK is an error, not a browser fallback`() {
        val noApk = JSONObject(latest)
            .put("assets", org.json.JSONArray())

        val result = runCatching { Updates.parseLatest(noApk, "1.3.6") }

        assertTrue(result.exceptionOrNull() is IllegalStateException)
    }
}
