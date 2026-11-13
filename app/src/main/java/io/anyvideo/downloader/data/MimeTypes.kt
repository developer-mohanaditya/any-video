package io.anyvideo.downloader.data

/**
 * MIME types for the extensions this app produces.
 *
 * The gallery/file indexer is told the type explicitly: media scanning by filename alone
 * guesses wrong for `.m4a` and `.opus` on some devices, which leaves a perfectly good audio
 * file invisible to every player.
 */
object MimeTypes {

    /** The type for a file name, or null when the extension is not one this app produces. */
    fun of(fileName: String): String? =
        when (fileName.substringAfterLast('.', "").lowercase()) {
            "mp3" -> "audio/mpeg"
            "m4a", "aac" -> "audio/mp4"
            "opus", "ogg" -> "audio/ogg"
            "wav" -> "audio/wav"
            "flac" -> "audio/flac"

            "mp4", "m4v" -> "video/mp4"
            "mov" -> "video/quicktime"
            "webm" -> "video/webm"
            "mkv" -> "video/x-matroska"

            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            "heic" -> "image/heic"
            "avif" -> "image/avif"

            else -> null
        }

    /**
     * A concrete type when the extension is known, otherwise the family wildcard. Intents must
     * never carry a blank type: a null `ACTION_VIEW` type matches almost nothing, and a null
     * `ACTION_SEND` type leaves the chooser without a category to filter on.
     */
    fun forKind(fileName: String, kind: MediaKind): String = of(fileName) ?: when (kind) {
        MediaKind.AUDIO -> "audio/*"
        MediaKind.VIDEO -> "video/*"
        MediaKind.PHOTO -> "image/*"
    }
}
