package io.anyvideo.downloader.download

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import io.anyvideo.downloader.data.Library
import io.anyvideo.downloader.net.MediaApi
import io.anyvideo.downloader.data.MediaCandidate
import io.anyvideo.downloader.data.MimeTypes
import io.anyvideo.downloader.util.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Downloads run inside the app, not through DownloadManager, so the UI can show real progress
 * and a finished file is only published once the bytes are on disk.
 *
 * Every transfer streams to a temporary file in the cache, is checked for a real payload and
 * then inserted into MediaStore. A candidate is queued once: the same file name and folder are
 * never accepted twice at the same time, and a file already in the gallery is skipped.
 */
object Downloader {

    const val IMAGE_FOLDER = "Pictures/anyvideo"
    const val VIDEO_FOLDER = "Movies/anyvideo"
    const val AUDIO_FOLDER = "Music/anyvideo"

    enum class State { QUEUED, RUNNING, DONE, FAILED }

    data class Task(
        val id: Long,
        val fileName: String,
        val url: String,
        val relativePath: String,
        val mime: String?,
        val isVideo: Boolean,
        val sourceUrl: String?,
        val state: State = State.QUEUED,
        val received: Long = 0L,
        val total: Long = 0L,
        val error: String? = null,
        val retried: Boolean = false,
    ) {
        val progress: Float
            get() = when {
                state == State.DONE -> 1f
                total > 0L -> (received.toFloat() / total).coerceIn(0f, 1f)
                else -> 0f
                // A tunnel often streams without a length. The row then shows the bytes
                // received instead of a percentage that would sit at 100 from the start.
            }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val slot = Semaphore(3)
    private val nextId = AtomicLong(1L)
    private val inFlight = LinkedHashSet<String>()

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val _tasks = MutableStateFlow<List<Task>>(emptyList())
    val tasks: StateFlow<List<Task>> = _tasks.asStateFlow()

    /** True while at least one transfer is queued or running, used by the foreground service. */
    val busy: Boolean
        get() = _tasks.value.any { it.state == State.QUEUED || it.state == State.RUNNING }

    fun isAudio(fileName: String): Boolean =
        MimeTypes.of(fileName)?.startsWith("audio/") == true

    fun kindLabel(fileName: String, isVideo: Boolean): String = when {
        isVideo -> "Video"
        isAudio(fileName) -> "Audio"
        else -> "Photo"
    }

    private fun standardDirectory(candidate: MediaCandidate): String = when {
        candidate.isVideo -> Environment.DIRECTORY_MOVIES
        isAudio(candidate.fileName) -> Environment.DIRECTORY_MUSIC
        else -> Environment.DIRECTORY_PICTURES
    }

    fun folderFor(candidate: MediaCandidate): String =
        "${standardDirectory(candidate)}/anyvideo"

    /**
     * Queues one item. Returns false when it is already downloading or already saved, which is
     * what keeps a double tap or a repeated carousel enqueue from writing every file twice.
     */
    fun enqueue(context: Context, candidate: MediaCandidate): Boolean {
        val app = context.applicationContext
        val folder = folderFor(candidate)
        val key = "$folder/${candidate.fileName}"
        synchronized(inFlight) {
            if (key in inFlight) return false
            if (Library.exists(app, folder, candidate.fileName)) return false
            inFlight += key
        }

        val task = Task(
            id = nextId.getAndIncrement(),
            fileName = candidate.fileName,
            url = candidate.url,
            relativePath = folder,
            mime = MimeTypes.of(candidate.fileName),
            isVideo = candidate.isVideo,
            sourceUrl = candidate.sourceUrl,
        )
        _tasks.value = _tasks.value + task
        candidate.sourceUrl?.let { DownloadRegistry.rememberSource(app, it) }
        launch(app, task, headers = candidate.headers, viaProxy = false)
        DownloadService.start(app)
        return true
    }

    /** True when this exact file is already in the gallery. */
    fun isSaved(context: Context, candidate: MediaCandidate): Boolean =
        Library.exists(context.applicationContext, folderFor(candidate), candidate.fileName)

    /** Queues what is not queued or saved yet, and reports how many transfers actually started. */
    fun enqueueAll(context: Context, candidates: List<MediaCandidate>): Int =
        candidates.count { enqueue(context, it) }

    fun retry(context: Context, id: Long) {
        val task = _tasks.value.firstOrNull { it.id == id } ?: return
        if (task.state != State.FAILED) return
        update(task.id) { it.copy(state = State.QUEUED, received = 0, error = null) }
        launch(context.applicationContext, task.copy(state = State.QUEUED), emptyMap(), false)
        DownloadService.start(context.applicationContext)
    }

    fun cancel(id: Long) {
        update(id) { it.copy(state = State.FAILED, error = "Cancelled") }
    }

    fun clearFinished() {
        _tasks.value = _tasks.value.filter { it.state == State.QUEUED || it.state == State.RUNNING }
    }

    fun finishedCount(): Int = _tasks.value.count { it.state == State.DONE }

    private fun launch(
        context: Context,
        task: Task,
        headers: Map<String, String>,
        viaProxy: Boolean,
    ) {
        scope.launch {
            slot.withPermit { run(context, task, headers, viaProxy) }
        }
    }

    /**
     * A tunnel URL that sat in the resolver cache can expire before its download starts, which
     * surfaces as 404 or 410. A fresh resolver reply fixes it, just like an empty body does.
     */
    private fun isRetryableEmpty(message: String) =
        message == "Empty file" || message == "Empty reply" ||
            message == "HTTP 404" || message == "HTTP 410"

    private suspend fun run(
        context: Context,
        task: Task,
        headers: Map<String, String>,
        viaProxy: Boolean,
    ) {
        var temp: File? = null
        try {
            update(task.id) { it.copy(state = State.RUNNING) }
            val request = Request.Builder().url(task.url).apply {
                headers.forEach { (key, value) -> header(key, value) }
            }.build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IllegalStateException("HTTP ${response.code}")
                }
                val body = response.body ?: throw IllegalStateException("Empty reply")
                val total = body.contentLength().coerceAtLeast(0L)
                update(task.id) { it.copy(total = total) }
                val dir = File(context.cacheDir, "downloads").apply { mkdirs() }
                temp = File(dir, "${task.id}-${task.fileName}")
                body.byteStream().use { input ->
                    temp!!.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var received = 0L
                        var lastEmit = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read <= 0) break
                            output.write(buffer, 0, read)
                            received += read
                            val now = System.currentTimeMillis()
                            if (now - lastEmit > 120 || (total > 0 && received == total)) {
                                lastEmit = now
                                update(task.id) { it.copy(received = received) }
                            }
                        }
                    }
                }
                val size = temp!!.length()
                if (size <= 0L) throw IllegalStateException("Empty file")
                if (looksLikeErrorPage(temp!!)) {
                    throw IllegalStateException("The server sent a web page instead of the file")
                }
                publish(context, task, temp!!)
            }
            update(task.id) { it.copy(state = State.DONE, received = it.total, error = null) }
            temp?.delete()
            synchronized(inFlight) { inFlight.remove("${task.relativePath}/${task.fileName}") }
        } catch (t: Throwable) {
            temp?.delete()
            val message = t.message ?: "Download failed"
            val proxy = Prefs.fallbackProxy.trim()
            // A failed CDN request gets one retry through the configured proxy, without the
            // session headers, because a proxy is a third party.
            if (!viaProxy && proxy.isNotEmpty() && !task.url.startsWith(proxy)) {
                val separator = if (proxy.contains('?')) '&' else '?'
                val proxied = "$proxy${separator}isvideo=${task.isVideo}&url=${Uri.encode(task.url)}"
                synchronized(inFlight) { inFlight.remove("${task.relativePath}/${task.fileName}") }
                update(task.id) { it.copy(state = State.QUEUED, received = 0, total = 0) }
                run(context, task.copy(url = proxied), emptyMap(), viaProxy = true)
            } else if (!viaProxy && !task.retried && isRetryableEmpty(message) && !task.sourceUrl.isNullOrBlank()) {
                // A resolver tunnel URL serves one transfer and then answers with an empty
                // body. Ask the resolver for a fresh link to the same file and use that.
                val fresh = runCatching { MediaApi.resolve(task.sourceUrl!!) }.getOrNull()
                    ?.candidates?.firstOrNull { it.fileName == task.fileName }
                if (fresh != null && fresh.url != task.url) {
                    update(task.id) { it.copy(state = State.RUNNING, received = 0, total = 0, retried = true) }
                    run(context, task.copy(url = fresh.url, retried = true), fresh.headers, viaProxy = false)
                    return
                }
                synchronized(inFlight) { inFlight.remove("${task.relativePath}/${task.fileName}") }
                update(task.id) { it.copy(state = State.FAILED, error = message) }
            } else {
                synchronized(inFlight) { inFlight.remove("${task.relativePath}/${task.fileName}") }
                update(task.id) { it.copy(state = State.FAILED, error = message) }
            }
        }
    }

    /** Inserts the finished file into the gallery, or writes it to the public folder before Q. */
    private fun publish(context: Context, task: Task, file: File) {
        val resolver = context.contentResolver
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val collection = when {
                task.isVideo -> MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                task.mime?.startsWith("audio/") == true ->
                    MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                else -> MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            }
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, task.fileName)
                task.mime?.let { put(MediaStore.MediaColumns.MIME_TYPE, it) }
                put(MediaStore.MediaColumns.RELATIVE_PATH, task.relativePath)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = resolver.insert(collection, values)
                ?: throw IllegalStateException("Could not create the file")
            try {
                val output = resolver.openOutputStream(uri)
                    ?: throw IllegalStateException("Could not write the file")
                output.use { out -> file.inputStream().use { it.copyTo(out) } }
                val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                resolver.update(uri, done, null, null)
                // The row is only trusted once the bytes are readable back through it. A row
                // without a file is exactly the ghost tile this engine exists to avoid.
                val written = resolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: 0L
                if (written <= 0L) throw IllegalStateException("The file did not reach storage")
            } catch (t: Throwable) {
                runCatching { resolver.delete(uri, null, null) }
                throw t
            }
        } else {
            val standard = task.relativePath.substringBefore('/')
            val folder = File(
                Environment.getExternalStoragePublicDirectory(standard),
                task.relativePath.substringAfter('/', "anyvideo"),
            ).apply { mkdirs() }
            val target = File(folder, task.fileName)
            file.inputStream().use { input -> target.outputStream().use { input.copyTo(it) } }
            MediaScannerConnection.scanFile(
                context,
                arrayOf(target.absolutePath),
                task.mime?.let { arrayOf(it) },
                null,
            )
        }
    }

    private fun update(id: Long, change: (Task) -> Task) {
        _tasks.value = _tasks.value.map { if (it.id == id) change(it) else it }
    }

    /** A CDN refusing a private file can answer 200 with a login page; never publish that. */
    private fun looksLikeErrorPage(file: File): Boolean = runCatching {
        val head = ByteArray(512)
        val read = file.inputStream().use { it.read(head) }
        if (read <= 0) return@runCatching false
        val text = String(head, 0, read).trimStart()
        text.startsWith("<") || text.startsWith("{")
    }.getOrDefault(false)
}

/** Small bookkeeping used by the home screen to list recent profile sources. */
object DownloadRegistry {
    private const val KEY_SOURCES = "recent_sources"
    private const val MAX = 40

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences("download_registry", Context.MODE_PRIVATE)

    fun rememberSource(context: Context, source: String) {
        val store = prefs(context)
        val list = store.getString(KEY_SOURCES, "").orEmpty()
            .split('\n')
            .filter { it.isNotBlank() && it != source }
            .toMutableList()
        list.add(0, source)
        store.edit().putString(KEY_SOURCES, list.take(MAX).joinToString("\n")).apply()
    }

    fun recentSources(context: Context, limit: Int): List<String> =
        prefs(context).getString(KEY_SOURCES, "").orEmpty()
            .split('\n')
            .filter { it.isNotBlank() }
            .take(limit)
}
