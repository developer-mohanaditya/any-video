package io.anyvideo.downloader.data

/**
 * One downloadable file discovered by a resolver.
 *
 * The original app names files `username-___<postId>___-.jpg|.mp4`; the repost
 * feature parses that name back, so the convention is preserved.
 */
data class MediaCandidate(
    val url: String,
    val fileName: String,
    val thumbnailUrl: String? = null,
    val isVideo: Boolean = false,
    val sourceUrl: String? = null,
    val headers: Map<String, String> = emptyMap(),
    /** Separate audio stream to merge with [url], used by the direct YouTube path. */
    val audioUrl: String? = null,
    /** Download in short range requests, needed for YouTube's throttled audio CDN. */
    val chunked: Boolean = false,
)

object FileNames {
    fun build(user: String?, id: String, extension: String): String {
        val cleanUser = (user ?: "User").replace(Regex("[^A-Za-z0-9._]"), "").ifBlank { "User" }
        val cleanId = id.replace(Regex("[^A-Za-z0-9._-]"), "").ifBlank { System.currentTimeMillis().toString() }
        return "$cleanUser-___${cleanId}___-.$extension"
    }

    /** Returns the username and the embedded post id, or null when the name was not produced here. */
    fun parse(fileName: String): Pair<String, String?> {
        val user = fileName.substringBefore('-').substringBefore('_').ifBlank { "User" }
        val id = if (fileName.contains("-___") && fileName.contains("___-")) {
            fileName.substringAfter("-___").substringBefore("___-").ifBlank { null }
        } else {
            null
        }
        return user to id
    }

    fun extensionFromUrl(url: String, isVideo: Boolean): String {
        val path = url.substringBefore('?').substringBefore('#')
        val ext = path.substringAfterLast('.', "").lowercase()
        if (ext in setOf("jpg", "jpeg", "png", "webp", "heic")) return ext
        if (ext in setOf("mp4", "m4v", "mov", "webm")) return ext
        return if (isVideo) "mp4" else "jpg"
    }

    fun looksLikeVideo(url: String): Boolean {
        val path = url.substringBefore('?').lowercase()
        if (path.endsWith(".mp4") || path.endsWith(".m4v") || path.endsWith(".mov") || path.endsWith(".webm")) return true
        // The generic extractor hands out CDN links of the shape `...?token=...&dl=1`.
        return url.contains("token=", true) && url.contains("&dl=1", true)
    }

    fun looksLikeImage(url: String): Boolean {
        val path = url.substringBefore('?').lowercase()
        if (path.endsWith(".jpg") || path.endsWith(".jpeg") || path.endsWith(".png") || path.endsWith(".webp")) return true
        return url.contains("cdninstagram", true) || url.contains("fbcdn", true)
    }
}
