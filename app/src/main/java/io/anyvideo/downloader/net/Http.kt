package io.anyvideo.downloader.net

import io.anyvideo.downloader.util.Prefs
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

object Http {
    const val DESKTOP_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/115.0.0.0 Safari/537.36"

    /** User agent the original app pins for `i.instagram.com`. */
    const val INSTAGRAM_UA =
        "Instagram 241.1.0.18.114 Android (31/12; 420dpi; 1080x2312; samsung; SM-G780F; exynos990; en_US;)"

    const val INSTAGRAM_APP_ID = "936619743392459"

    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(40, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private val instagramOrigins = listOf(
        "https://i.instagram.com" to "i",
        "https://www.instagram.com" to "www",
    )

    /**
     * Debug-only redirect used by the emulator smoke test: with a mock base configured, the
     * Instagram hosts are answered by `<base>/ig/<i|www><path>`. Keeping the host in the path lets
     * the mock prove which endpoint family the resolver actually chose. Release builds are no-ops
     * because [Prefs.instagramMockBase] is always empty there.
     */
    private fun mirror(url: String): String {
        if (!url.startsWith("https://")) return url
        val mock = runCatching { Prefs.instagramMockBase }.getOrDefault("")
        if (mock.isEmpty()) return url
        for ((origin, tag) in instagramOrigins) {
            if (!url.startsWith(origin)) continue
            val rest = url.removePrefix(origin)
            return "$mock/ig/$tag" + if (rest.startsWith("/")) rest else "/$rest"
        }
        return url
    }

    fun get(url: String, headers: Map<String, String> = emptyMap()): String? = try {
        val builder = Request.Builder().url(mirror(url)).get()
        headers.forEach { (key, value) -> builder.addHeader(key, value) }
        client.newCall(builder.build()).execute().use { response ->
            if (response.isSuccessful) response.body?.string() else null
        }
    } catch (t: Throwable) {
        null
    }

    /** Status and body of one reply, kept together because resolver APIs answer errors with JSON. */
    data class Reply(val code: Int, val body: String?) {
        val isSuccess: Boolean get() = code in 200..299
    }

    /**
     * Like [get] but keeps the status: Instagram answers a gated request with 404/403 and a
     * normal-looking page, so the body alone cannot tell "no such profile" from "not logged in".
     */
    fun getReply(url: String, headers: Map<String, String> = emptyMap()): Reply? = try {
        val builder = Request.Builder().url(mirror(url)).get()
        headers.forEach { (key, value) -> builder.addHeader(key, value) }
        client.newCall(builder.build()).execute().use { response ->
            Reply(response.code, response.body?.string())
        }
    } catch (t: Throwable) {
        null
    }

    fun postJson(url: String, json: String, headers: Map<String, String> = emptyMap()): Reply? = try {
        val body = json.toRequestBody("application/json; charset=utf-8".toMediaType())
        val builder = Request.Builder().url(mirror(url)).post(body)
        headers.forEach { (key, value) -> builder.addHeader(key, value) }
        client.newCall(builder.build()).execute().use { response ->
            Reply(response.code, response.body?.string())
        }
    } catch (t: Throwable) {
        null
    }

    fun postForm(url: String, form: Map<String, String>, headers: Map<String, String> = emptyMap()): String? = try {
        val body = FormBody.Builder().apply { form.forEach { (k, v) -> add(k, v) } }.build()
        val builder = Request.Builder().url(mirror(url)).post(body)
        headers.forEach { (key, value) -> builder.addHeader(key, value) }
        client.newCall(builder.build()).execute().use { response ->
            if (response.isSuccessful) response.body?.string() else null
        }
    } catch (t: Throwable) {
        null
    }
}
