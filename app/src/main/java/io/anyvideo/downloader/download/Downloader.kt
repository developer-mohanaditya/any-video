package io.anyvideo.downloader.download

import android.content.ContentValues
import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import io.anyvideo.downloader.data.Library
import io.anyvideo.downloader.net.MediaApi
import io.anyvideo.downloader.net.YouTubeFormats
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
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.nio.ByteBuffer
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
        val audioUrl: String? = null,
        val chunked: Boolean = false,
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
            audioUrl = candidate.audioUrl,
            chunked = candidate.chunked,
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

    /** Failed rows are session feedback, not gallery history. Drop them when the app leaves. */
    fun clearFailed() {
        _tasks.value = _tasks.value.filter { it.state != State.FAILED }
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
        try {
            update(task.id) { it.copy(state = State.RUNNING) }
            val downloaded = when {
                !task.audioUrl.isNullOrBlank() -> downloadMerged(
                    context = context,
                    task = task,
                    headers = headers,
                    audioUrl = task.audioUrl!!,
                    onProgress = { received, total ->
                        update(task.id) { it.copy(received = received, total = total) }
                    },
                )
                task.chunked -> downloadChunked(
                    context = context,
                    task = task,
                    headers = headers,
                    onProgress = { received, total ->
                        update(task.id) { it.copy(received = received, total = total) }
                    },
                )
                else -> downloadTo(
                    url = task.url,
                    headers = headers,
                    cacheDir = File(context.cacheDir, "downloads"),
                    fileName = task.fileName,
                    onProgress = { received, total ->
                        update(task.id) { it.copy(received = received, total = total) }
                    },
                    publishFile = { file -> publish(context, task, file) },
                )
            }
            // Finish with the bytes that actually reached disk, not a missing content length.
            update(task.id) {
                it.copy(state = State.DONE, received = downloaded, total = downloaded, error = null)
            }
            synchronized(inFlight) { inFlight.remove("${task.relativePath}/${task.fileName}") }
        } catch (t: Throwable) {
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
                // The resolver's audio tunnel is often empty for the same videos whose video
                // tunnel fails. Save YouTube's original AAC track as M4A instead.
                if (task.mime.orEmpty().startsWith("audio/")) {
                    val direct = runCatching {
                        YouTubeFormats.directAudio(task.sourceUrl!!)
                    }.getOrNull()
                    if (direct != null) {
                        Toast.makeText(
                            context,
                            "Downloading as M4A because the resolver could not convert this audio",
                            Toast.LENGTH_LONG,
                        ).show()
                        synchronized(inFlight) {
                            inFlight.remove("${task.relativePath}/${task.fileName}")
                            inFlight.add("${task.relativePath}/${direct.fileName}")
                        }
                        val retry = task.copy(
                            url = direct.url,
                            fileName = direct.fileName,
                            mime = MimeTypes.of(direct.fileName),
                            isVideo = false,
                            audioUrl = null,
                            chunked = true,
                            retried = true,
                        )
                        update(task.id) {
                            it.copy(
                                state = State.RUNNING,
                                received = 0,
                                total = 0,
                                retried = true,
                                fileName = retry.fileName,
                                mime = retry.mime,
                                isVideo = false,
                            )
                        }
                        run(context, retry, direct.headers, viaProxy = false)
                        return
                    }
                }

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

    /**
     * The real network-to-gallery path: fetch into a temp file, reject an empty or error-page
     * body, then hand a nonempty file to the publisher. Exposed for the zero-byte regression test.
     */
    internal suspend fun downloadTo(
        url: String,
        headers: Map<String, String>,
        cacheDir: File,
        fileName: String,
        onProgress: (received: Long, total: Long) -> Unit = { _, _ -> },
        publishFile: (File) -> Unit,
    ): Long = withContext(Dispatchers.IO) {
        val dir = cacheDir.apply { mkdirs() }
        val temp = File(dir, "download-${System.nanoTime()}-$fileName")
        try {
            val request = Request.Builder().url(url).apply {
                headers.forEach { (key, value) -> header(key, value) }
            }.build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IllegalStateException("HTTP ${response.code}")
                }
                val body = response.body ?: throw IllegalStateException("Empty reply")
                // Tunnel replies often send no Content-Length, only the resolver's estimate.
                // Using that keeps the progress bar and the final size honest for YouTube.
                val total = body.contentLength().takeIf { it > 0L }
                    ?: response.header("Estimated-Content-Length")?.toLongOrNull()
                        ?.takeIf { it > 0L }
                    ?: 0L
                onProgress(0L, total)
                temp.outputStream().use { output ->
                    body.byteStream().use { input ->
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
                                onProgress(received, total)
                            }
                        }
                    }
                }
            }
            requirePayload(temp)
            val size = temp.length()
            publishFile(temp)
            size
        } finally {
            temp.delete()
        }
    }

    /**
     * YouTube's direct path hands out separate video and AAC files. Download both, merge them
     * into one MP4, and only then let the normal publisher put it in the gallery. The video side
     * can be H.264 MP4, VP9 WebM, or AV1 MP4, so its temporary extension follows the URL.
     */
    private suspend fun downloadMerged(
        context: Context,
        task: Task,
        headers: Map<String, String>,
        audioUrl: String,
        onProgress: (received: Long, total: Long) -> Unit,
    ): Long = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "downloads").apply { mkdirs() }
        val stamp = System.nanoTime()
        val webm = task.fileName.endsWith(".webm", ignoreCase = true)
        val videoFile = File(dir, "video-$stamp.${if (webm) "webm" else "mp4"}")
        val audioFile = File(dir, "audio-$stamp.${if (webm) "webm" else "m4a"}")
        val merged = File(dir, "merged-$stamp.${if (webm) "webm" else "mp4"}")
        var videoTotal = 0L
        var videoDone = 0L
        var audioTotal = 0L
        var audioDone = 0L
        try {
            val videoSize = fetchToFile(task.url, headers, videoFile) { received, total ->
                videoDone = received
                videoTotal = total
                onProgress(videoDone + audioDone, videoTotal + audioTotal)
            }
            val audioSize = fetchToFile(audioUrl, headers, audioFile) { received, total ->
                audioDone = received
                audioTotal = total
                onProgress(videoDone + audioDone, videoTotal + audioTotal)
            }
            onProgress(videoSize + audioSize, videoSize + audioSize)
            mux(videoFile, audioFile, merged)
            requirePayload(merged)
            publish(context, task, merged)
            merged.length()
        } finally {
            videoFile.delete()
            audioFile.delete()
            merged.delete()
        }
    }

    /**
     * YouTube's audio CDN stops a long connection part way through. Asking for one range at a
     * time keeps every request short and resumes exactly where the previous one ended.
     */
    /** A direct audio file uses the same resumable range fetch as the merged streams. */
    private suspend fun downloadChunked(
        context: Context,
        task: Task,
        headers: Map<String, String>,
        onProgress: (received: Long, total: Long) -> Unit,
    ): Long = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "downloads").apply { mkdirs() }
        val temp = File(dir, "direct-${System.nanoTime()}-${task.fileName}")
        try {
            fetchToFile(task.url, headers, temp, onProgress)
            publish(context, task, temp)
            temp.length()
        } finally {
            temp.delete()
        }
    }

    private suspend fun fetchToFile(
        url: String,
        headers: Map<String, String>,
        dest: File,
        onProgress: (received: Long, total: Long) -> Unit,
    ): Long = withContext(Dispatchers.IO) {
        val chunk = 512 * 1024L
        var received = 0L
        var total = 0L
        var lastEmit = 0L
        dest.outputStream().use { output ->
            while (true) {
                val end = if (total > 0L) {
                    (received + chunk - 1).coerceAtMost(total - 1)
                } else {
                    received + chunk - 1
                }
                val request = Request.Builder().url(url).apply {
                    headers.forEach { (key, value) -> header(key, value) }
                    header("Range", "bytes=$received-$end")
                    header("Accept-Encoding", "identity")
                }.build()
                client.newCall(request).execute().use { response ->
                    if (response.code == 416) {
                        throw IllegalStateException("The server rejected the resume range")
                    }
                    if (!response.isSuccessful) throw IllegalStateException("HTTP ${response.code}")
                    val body = response.body ?: throw IllegalStateException("Empty reply")
                    val bodyLength = body.contentLength().takeIf { it > 0L } ?: 0L
                    if (response.code == 200 && received > 0L) {
                        throw IllegalStateException("The server stopped honouring range requests")
                    }
                    if (response.code == 206) {
                        val fromRange = response.header("Content-Range")
                            ?.substringAfterLast('/')
                            ?.toLongOrNull()
                            ?.takeIf { it > 0L }
                        if (fromRange != null) total = fromRange
                    } else if (bodyLength > 0L) {
                        total = received + bodyLength
                    }
                    body.byteStream().use { input ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val read = input.read(buffer)
                            if (read <= 0) break
                            output.write(buffer, 0, read)
                            received += read
                            val now = System.currentTimeMillis()
                            if (now - lastEmit > 120) {
                                lastEmit = now
                                onProgress(received, total)
                            }
                        }
                    }
                    onProgress(received, total)
                    if (bodyLength == 0L) {
                        throw IllegalStateException("The server returned an empty range")
                    }
                }
                if (total > 0L && received >= total) break
                if (total <= 0L && received == 0L) break
            }
        }
        if (total > 0L && received < total) {
            throw IllegalStateException("Incomplete download ($received of $total bytes)")
        }
        requirePayload(dest)
        dest.length()
    }

    private fun mux(video: File, audio: File, out: File) {
        val videoExtractor = MediaExtractor()
        val audioExtractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        var started = false
        try {
            videoExtractor.setDataSource(video.absolutePath)
            audioExtractor.setDataSource(audio.absolutePath)
            val videoTrack = firstTrack(videoExtractor, "video/")
                ?: throw IllegalStateException("The video stream has no video track")
            val audioTrack = firstTrack(audioExtractor, "audio/")
                ?: throw IllegalStateException("The audio stream has no audio track")
            val videoFormat = videoExtractor.getTrackFormat(videoTrack)
            val audioFormat = audioExtractor.getTrackFormat(audioTrack)
            val webm = video.name.endsWith(".webm", ignoreCase = true) ||
                out.name.endsWith(".webm", ignoreCase = true)
            val target = MediaMuxer(
                out.absolutePath,
                if (webm) MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM
                else MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4,
            )
            muxer = target
            val videoOut = target.addTrack(videoFormat)
            val audioOut = target.addTrack(audioFormat)
            target.start()
            started = true
            copySamples(videoExtractor, videoTrack, videoOut, target, videoFormat)
            copySamples(audioExtractor, audioTrack, audioOut, target, audioFormat)
        } finally {
            if (started) runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
            videoExtractor.release()
            audioExtractor.release()
        }
    }

    private fun firstTrack(extractor: MediaExtractor, prefix: String): Int? =
        (0 until extractor.trackCount).firstOrNull {
            extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)
                ?.startsWith(prefix) == true
        }

    private fun copySamples(
        extractor: MediaExtractor,
        track: Int,
        outTrack: Int,
        muxer: MediaMuxer,
        format: MediaFormat,
    ) {
        extractor.selectTrack(track)
        val maxInput = if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
            format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE)
        } else {
            2 * 1024 * 1024
        }
        val buffer = ByteBuffer.allocateDirect(maxInput.coerceAtLeast(2 * 1024 * 1024))
        val info = MediaCodec.BufferInfo()
        while (true) {
            val size = extractor.readSampleData(buffer, 0)
            if (size < 0) break
            info.set(0, size, extractor.sampleTime, extractor.sampleFlags)
            muxer.writeSampleData(outTrack, buffer, info)
            extractor.advance()
        }
    }

    /** Inserts the finished file into the gallery, or writes it to the public folder before Q. */
    private fun publish(context: Context, task: Task, file: File, attempt: Int = 0) {
        val resolver = context.contentResolver
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // A stale row can outlive its file and survive a failed delete, especially after a
            // reinstall. Recheck now, and avoid a name that any row still occupies, including
            // one whose file cannot be opened, before inserting the pending row.
            if (Library.exists(context, task.relativePath, task.fileName)) return
            val collection = when {
                task.isVideo -> MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                task.mime?.startsWith("audio/") == true ->
                    MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                else -> MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            }
            val targetName = availableName(resolver, collection, task.relativePath, task.fileName)
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, targetName)
                task.mime?.let { put(MediaStore.MediaColumns.MIME_TYPE, it) }
                put(MediaStore.MediaColumns.RELATIVE_PATH, task.relativePath)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = insertPending(resolver, collection, values, targetName)
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
                if (attempt < 5 && t.message.orEmpty().contains("UNIQUE", ignoreCase = true)) {
                    publish(context, task.copy(fileName = numberedName(task.fileName, attempt + 2)), file, attempt + 1)
                    return
                }
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

    /**
     * A row can remain at the requested path even after its file is gone, and Android can refuse
     * to delete it. Never fail the download over that: add a suffix until MediaStore accepts it.
     */
    private fun insertPending(
        resolver: android.content.ContentResolver,
        collection: Uri,
        values: ContentValues,
        fileName: String,
    ): Uri {
        var candidate = fileName
        for (attempt in 1..20) {
            values.put(MediaStore.MediaColumns.DISPLAY_NAME, candidate)
            try {
                return resolver.insert(collection, values)
                    ?: throw IllegalStateException("Could not create the file")
            } catch (t: RuntimeException) {
                val unique = t.message.orEmpty().contains("UNIQUE", ignoreCase = true)
                if (!unique || attempt == 20) throw t
                candidate = numberedName(fileName, attempt + 1)
            }
        }
        throw IllegalStateException("Could not create the file")
    }

    /** A name for a new row that no stale row or pending row already occupies. */
    private fun availableName(
        resolver: android.content.ContentResolver,
        collection: Uri,
        relativePath: String,
        fileName: String,
    ): String {
        for (attempt in 1..20) {
            val candidate = if (attempt == 1) fileName else numberedName(fileName, attempt)
            val selection = "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? AND " +
                "${MediaStore.MediaColumns.DISPLAY_NAME} = ?"
            val taken = runCatching {
                resolver.query(
                    collection,
                    arrayOf(MediaStore.MediaColumns._ID),
                    selection,
                    arrayOf("$relativePath%", candidate),
                    null,
                )?.use { it.count > 0 } ?: false
            }.getOrDefault(false)
            if (!taken) return candidate
        }
        return numberedName(fileName, System.currentTimeMillis().toInt())
    }

    internal fun numberedName(fileName: String, number: Int): String {
        val dot = fileName.lastIndexOf('.')
        if (dot <= 0) return "$fileName ($number)"
        return "${fileName.substring(0, dot)} ($number)${fileName.substring(dot)}"
    }

    private fun update(id: Long, change: (Task) -> Task) {
        _tasks.value = _tasks.value.map { if (it.id == id) change(it) else it }
    }

    /**
     * The last gate before MediaStore. A tunnel can answer 200 with no body, and a CDN refusing
     * a private file can answer with a login page; neither may become a gallery file.
     */
    internal fun requirePayload(file: File) {
        if (file.length() <= 0L) throw IllegalStateException("Empty file")
        if (looksLikeErrorPage(file)) {
            throw IllegalStateException("The server sent a web page instead of the file")
        }
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
