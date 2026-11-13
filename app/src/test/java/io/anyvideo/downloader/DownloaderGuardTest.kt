package io.anyvideo.downloader

import io.anyvideo.downloader.download.Downloader
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.file.Files

class DownloaderGuardTest {

    @Test
    fun `zero byte tunnel payload is rejected before publishing`() {
        val payload = File.createTempFile("empty-tunnel", ".mp4")
        try {
            val error = assertThrows(IllegalStateException::class.java) {
                Downloader.requirePayload(payload)
            }
            assertEquals("Empty file", error.message)
        } finally {
            payload.delete()
        }
    }

    @Test
    fun `zero byte response through real download path never creates a gallery file`() {
        val server = serveOnce(ByteArray(0))
        val gallery = Files.createTempDirectory("anyvideo-gallery").toFile()
        val cache = Files.createTempDirectory("anyvideo-cache").toFile()
        try {
            val error = assertThrows(IllegalStateException::class.java) {
                runBlocking {
                    Downloader.downloadTo(
                        url = "http://127.0.0.1:${server.localPort}/empty",
                        headers = emptyMap(),
                        cacheDir = cache,
                        fileName = "empty.mp4",
                        publishFile = { file ->
                            file.copyTo(File(gallery, "empty.mp4"))
                        },
                    )
                }
            }
            assertEquals("Empty file", error.message)
            assertEquals(emptyList<String>(), gallery.list()?.toList().orEmpty())
            assertEquals(emptyList<String>(), cache.list()?.toList().orEmpty())
        } finally {
            server.close()
            gallery.deleteRecursively()
            cache.deleteRecursively()
        }
    }

    @Test
    fun `nonempty response through real download path publishes a file`() {
        val server = serveOnce("not a real video, but a nonempty payload".toByteArray())
        val gallery = Files.createTempDirectory("anyvideo-gallery").toFile()
        val cache = Files.createTempDirectory("anyvideo-cache").toFile()
        try {
            val size = runBlocking {
                Downloader.downloadTo(
                    url = "http://127.0.0.1:${server.localPort}/video",
                    headers = emptyMap(),
                    cacheDir = cache,
                    fileName = "video.mp4",
                    publishFile = { file ->
                        file.copyTo(File(gallery, "video.mp4"))
                    },
                )
            }
            assertTrue(size > 0L)
            assertTrue(File(gallery, "video.mp4").length() > 0L)
            assertEquals(listOf("video.mp4"), gallery.list()?.toList().orEmpty())
        } finally {
            server.close()
            gallery.deleteRecursively()
            cache.deleteRecursively()
        }
    }

    private fun serveOnce(body: ByteArray): ServerSocket {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        Thread {
            try {
                server.accept().use { socket ->
                    val input = socket.getInputStream().bufferedReader()
                    while (true) {
                        val line = input.readLine() ?: break
                        if (line.isEmpty()) break
                    }
                    val output = socket.getOutputStream()
                    output.write(
                        ("HTTP/1.1 200 OK\r\n" +
                            "Content-Type: video/mp4\r\n" +
                            "Content-Length: ${body.size}\r\n" +
                            "Connection: close\r\n\r\n").toByteArray(),
                    )
                    output.write(body)
                    output.flush()
                }
            } catch (_: IOException) {
                // The test closes the listener on cleanup.
            }
        }.apply {
            isDaemon = true
            start()
        }
        return server
    }

    @Test
    fun `collision fallback keeps the file extension`() {
        assertEquals("clip (2).mp4", Downloader.numberedName("clip.mp4", 2))
        assertEquals("clip (3)", Downloader.numberedName("clip", 3))
    }

    @Test
    fun `nonempty payload passes the publish gate`() {
        val payload = File.createTempFile("real-tunnel", ".mp4")
        try {
            payload.writeBytes(byteArrayOf(0, 0, 0, 24, 'f'.code.toByte()))
            Downloader.requirePayload(payload)
        } finally {
            payload.delete()
        }
    }

    @Test
    fun `web page payload is rejected before publishing`() {
        val payload = File.createTempFile("login-page", ".mp4")
        try {
            payload.writeText("<!doctype html><html><body>Log in</body></html>")
            val error = assertThrows(IllegalStateException::class.java) {
                Downloader.requirePayload(payload)
            }
            assertEquals("The server sent a web page instead of the file", error.message)
        } finally {
            payload.delete()
        }
    }
}
