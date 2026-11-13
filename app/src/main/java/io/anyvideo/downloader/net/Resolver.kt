package io.anyvideo.downloader.net

import android.app.Activity
import io.anyvideo.downloader.data.MediaCandidate
import kotlinx.coroutines.delay
import java.util.concurrent.ConcurrentHashMap

data class ResolveOutcome(
    val candidates: List<MediaCandidate>,
    val note: String? = null,
)

object Resolver {

    /** Total tries for a failure cobalt calls temporary (busy instance, empty upstream answer). */
    private const val ATTEMPTS = 3

    /** Pauses before the second and third try; long enough to clear a short rate-limit window. */
    private val BACKOFF_MS = longArrayOf(900L, 2_600L)

    /**
     * Recent successes, so pasting the same link twice (or retyping it) costs no request at all.
     * Kept short: the tunnel URLs inside a reply are handed out for a limited time, and an
     * expired one downloads nothing.
     */
    private const val CACHE_TTL_MS = 5 * 60 * 1000L
    private const val CACHE_ENTRIES = 40

    private data class CacheEntry(val at: Long, val outcome: ResolveOutcome)
    private val cache = ConcurrentHashMap<String, CacheEntry>()

    /**
     * The link plus the options that change the answer. Without the variant, asking for audio
     * right after a video resolve returns the cached video reply.
     */
    private fun key(url: String): String = "$url#${MediaApi.variant(url)}"

    private fun cached(url: String): ResolveOutcome? {
        val entry = cache[key(url)] ?: return null
        if (System.currentTimeMillis() - entry.at > CACHE_TTL_MS) {
            cache.remove(key(url))
            return null
        }
        return entry.outcome
    }

    /** Drops every remembered reply, e.g. after a download failed on a stale tunnel URL. */
    fun invalidate() = cache.clear()

    private fun remember(url: String, outcome: ResolveOutcome) {
        if (outcome.candidates.isEmpty()) return
        cache[key(url)] = CacheEntry(System.currentTimeMillis(), outcome)
        if (cache.size > CACHE_ENTRIES) {
            val cutoff = System.currentTimeMillis() - CACHE_TTL_MS
            cache.entries.removeAll { it.value.at < cutoff }
        }
    }

    private val supportedHosts = listOf(
        "instagram.com", "instagr.am", "facebook.com", "fb.watch", "tiktok.com",
        "twitter.com", "x.com", "vimeo.com", "dailymotion.com", "pinterest.com",
        "youtube.com", "youtu.be", "snapchat.com", "reddit.com", "likee.video",
    )

    fun isSupported(url: String): Boolean = supportedHosts.any { url.contains(it, true) }

    /** Strip tracking parameters and normalise Instagram reel/tv links to posts. */
    fun normalize(url: String): String {
        val trimmed = url.trim()
        val withoutQuery = trimmed.substringBefore('?').substringBefore('#')
        return when {
            withoutQuery.contains("/reel/") -> withoutQuery.replace("/reel/", "/p/")
            withoutQuery.contains("/reels/") -> withoutQuery.replace("/reels/", "/p/")
            withoutQuery.contains("/tv/") -> withoutQuery.replace("/tv/", "/p/")
            else -> withoutQuery
        }
    }

    suspend fun resolve(activity: Activity, rawUrl: String): ResolveOutcome {
        // Only Instagram links get their query stripped: `watch?v=…` and `status/…` carry the
        // identity of the post, so normalising those would break every non-Instagram link.
        val trimmed = rawUrl.trim()
        val url = if (Instagram.isInstagram(trimmed)) normalize(trimmed) else trimmed
        if (url.isBlank()) return ResolveOutcome(emptyList(), "Paste a link to download from.")

        cached(url)?.let { return it }
        val outcome = lookup(activity, trimmed, url)
        remember(url, outcome)
        return outcome
    }

    private suspend fun lookup(activity: Activity, rawUrl: String, url: String): ResolveOutcome {
        // The resolver API is the widest net (Instagram, YouTube, X, Pinterest, …), so it goes
        // first; its failure reason is kept in case the on-device paths find nothing either.
        var apiError: String? = null
        if (MediaApi.isConfigured()) {
            // A busy instance or an empty answer from the upstream service is usually gone within
            // a couple of seconds, so those are retried here and the user never sees them.
            var attempt = 0
            while (true) {
                val api = MediaApi.resolve(rawUrl)
                if (api.candidates.isNotEmpty()) {
                    return ResolveOutcome(api.candidates, "Resolved via the resolver API")
                }
                apiError = api.error
                // A rate-limit window on the instance is measured in tens of seconds, so a quick
                // retry would only dig deeper into the same budget: wait once, briefly, and then
                // let the message explain it.
                val rateLimited = api.code?.contains("rate", true) == true
                val maxAttempts = if (rateLimited) 2 else ATTEMPTS
                if (!api.retryable || attempt >= maxAttempts - 1) break
                delay(
                    if (rateLimited) 4_000L
                    else BACKOFF_MS[attempt.coerceAtMost(BACKOFF_MS.lastIndex)],
                )
                attempt++
            }
        }

        if (Instagram.isInstagram(url)) {
            if (Instagram.shortcode(url) != null) {
                val direct = Instagram.resolvePost(activity, url)
                if (direct.isNotEmpty()) return ResolveOutcome(direct, "Resolved from Instagram")
            }
            val profile = Instagram.usernameFrom(url)
            if (profile != null) {
                val feed = Instagram.profileFeed(activity, profile)
                if (feed.items.isNotEmpty()) {
                    return ResolveOutcome(feed.items, if (feed.signedIn) "Profile feed: @$profile" else null)
                }
            }
        }

        val generic = GenericResolver.resolve(activity, url)
        return when {
            generic.isNotEmpty() -> ResolveOutcome(generic, "Resolved with the generic extractor")
            apiError != null -> ResolveOutcome(emptyList(), "No media found: $apiError.")
            MediaApi.isConfigured() -> ResolveOutcome(emptyList(), "No media found in that link.")
            else -> ResolveOutcome(
                emptyList(),
                "No media found. Add a resolver API in Settings to support YouTube, X, Pinterest and " +
                    "other sites, or check that the extractor endpoint is up to date.",
            )
        }
    }
}
