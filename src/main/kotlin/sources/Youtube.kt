package sources

import beatport.api.*
import org.schabi.newpipe.extractor.*
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory.MUSIC_SONGS
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URI
import java.util.*
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

class Youtube : ISource {

    private val next = HashMap<String, Page?>()

    override val name: String
        get() = "YouTube"

    init {
        NewPipe.init(Downloader(), Localization.DEFAULT)
    }

    override fun getGenre(): List<Track> {
        return getTrending()
    }

    override fun getCuratedPlaylists(reset: Boolean): List<Playlist> {
        return emptyList()
    }

    override fun getCuratedPlaylist(id: String): List<Track> {
        return emptyList()
    }

    override fun getPlaylists(): List<Playlist> {
        return emptyList()
    }

    override fun getPlaylist(id: String): List<Track> {
        return emptyList()
    }

    override fun getTop100(): List<Track> {
        return emptyList()
    }

    override fun query(query: String, reset: Boolean): List<Track> {
        val extractor = ServiceList.YouTube.getSearchExtractor(query, listOf(MUSIC_SONGS), "")
        val itemsPage = if (!reset && next.containsKey(query)) {
            if (next[query] == null)
                return emptyList()
            extractor.getPage(next[query])
        } else {
            extractor.fetchPage()
            extractor.initialPage
        }
        next[query] = itemsPage.nextPage
        return extractItems(itemsPage.items)
    }

    override fun download(id: String): ByteArray {
        return downloadTrack(getAudioStream(id))
    }

    /**
     * Hydrate metadata + thumbnail for a YouTube video id.
     *
     * Accepts a full 11-char id, or a 10-char prefix already present in the prefix cache
     * (warmed when we previously saw the full id). Does **not** network-expand prefixes:
     * Traktor decode yields ≤10 chars for every source, and Spotify/Tidal ids share the same
     * alphabet — blind `i.ytimg` probing would mis-attribute foreign ids to YouTube.
     */
    override fun lookupTrack(id: String): Track? {
        val videoId = resolveVideoIdFromPrefix(id, allowNetworkExpand = false) ?: return null
        rememberVideoId(videoId)
        val fallbackThumb = thumbnailUrl(videoId)
        return try {
            val extractor = ServiceList.YouTube.getStreamExtractor("https://www.youtube.com/watch?v=$videoId")
            extractor.fetchPage()
            val title = extractor.name.ifBlank { videoId }
            val artist = extractor.uploaderName.ifBlank { "YouTube" }
            val durationMs = extractor.length.coerceAtLeast(0L) * 1000L
            val thumb = bestThumbnailUrl(extractor.thumbnails) ?: fallbackThumb
            Track(
                videoId,
                listOf(Artist(1, artist)),
                title,
                durationMs,
                releaseWithArt(title, thumb)
            )
        } catch (ex: Exception) {
            println("YouTube: lookupTrack extractor failed for $videoId (${ex.message}), using i.ytimg fallback")
            Track(
                videoId,
                listOf(Artist(1, "YouTube")),
                videoId,
                0L,
                releaseWithArt(videoId, fallbackThumb)
            )
        }
    }

    private fun downloadTrack(path: String): ByteArray {
        val con = URL(path).openConnection()
        con.setRequestProperty("range", "bytes=0-")
        return con.inputStream.readBytes()
    }

    private fun getTrending(): List<Track> {
        val extractor = ServiceList.YouTube.kioskList.getExtractorById("trending_music", null)
        extractor.fetchPage()
        return extractItems(extractor.initialPage.items)
    }

    private fun extractItems(items: List<InfoItem>): List<Track> {
        val results = LinkedList<Track>()
        for (item in items) {
            if (item is StreamInfoItem) {
                val videoId = item.url.substring(item.url.indexOf('=') + 1)
                // Persist prefix→full so cold catalog hydration after restart can resolve
                // Traktor's 10-char decode without probing (avoids Spotify/Tidal collisions).
                rememberVideoId(videoId)
                val thumbUrl = bestThumbnailUrl(item.thumbnails) ?: thumbnailUrl(videoId)
                results.add(
                    Track(
                        videoId,
                        listOf(Artist(1, item.uploaderName)),
                        item.name,
                        item.duration * 1000,
                        releaseWithArt(item.name, thumbUrl)
                    )
                )
            }
        }
        return results
    }

    companion object {
        private const val MAX_CONCURRENT_PREFIX_EXPANDS = 2
        private const val PREFIX_EXPAND_WAIT_SECONDS = 20L
        private const val PREFIX_CACHE_FILE = "youtube-id-prefix-cache.properties"

        private val YOUTUBE_ID_CHARS =
            (('0'..'9') + ('a'..'z') + ('A'..'Z') + listOf('-', '_')).toList()
        private val YOUTUBE_ID_CHAR_SET = YOUTUBE_ID_CHARS.toSet()
        private val prefixCache = ConcurrentHashMap<String, String>()
        /** Prefixes with more than one valid 11-char completion — do not guess. */
        private val ambiguousPrefixes = ConcurrentHashMap.newKeySet<String>()
        private val prefixInflight = ConcurrentHashMap<String, CompletableFuture<String?>>()
        private val expandPermits = Semaphore(MAX_CONCURRENT_PREFIX_EXPANDS)
        private val expandExecutor = Executors.newFixedThreadPool(8) { r ->
            Thread(r, "youtube-id-expand").apply { isDaemon = true }
        }
        private val prefixCacheLock = Any()
        @Volatile private var prefixCacheLoaded = false

        /** Overridable for tests; production probes i.ytimg.com. */
        internal var thumbnailProbe: (String) -> Boolean = ::probeThumbnailExists

        /** When false, skip reading/writing [PREFIX_CACHE_FILE] (unit tests). */
        internal var prefixCachePersistenceEnabled: Boolean = true

        internal fun isYouTubeVideoId(id: String): Boolean =
            id.length == 11 && id.all { it in YOUTUBE_ID_CHAR_SET }

        internal fun isYouTubeVideoIdPrefix(id: String): Boolean =
            id.length == 10 && id.all { it in YOUTUBE_ID_CHAR_SET }

        /** Standard public thumbnail CDN URL (no API call). */
        internal fun thumbnailUrl(videoId: String, quality: String = "hqdefault"): String =
            "https://i.ytimg.com/vi/$videoId/$quality.jpg"

        /**
         * Resolve a full 11-char video id. Accepts a full id, or a 10-char prefix produced by
         * [beatport.api.Utils.decode] after Traktor-id truncation.
         *
         * When [allowNetworkExpand] is false (default; used by [lookupTrack]), only a full id or
         * an existing prefix-cache entry is accepted — no `i.ytimg` probing. Network expand is
         * unsafe for cross-source catalog fan-out because Spotify/Tidal ids share this alphabet.
         *
         * When [allowNetworkExpand] is true, probes `i.ytimg.com` for the missing final character.
         * Concurrent callers for the same prefix share one expand (single-flight). At most
         * [MAX_CONCURRENT_PREFIX_EXPANDS] distinct prefixes expand at once. Only an unambiguous
         * single match is cached (memory + [PREFIX_CACHE_FILE]); multiple HEAD hits → no guess.
         */
        internal fun resolveVideoIdFromPrefix(
            idOrPrefix: String,
            allowNetworkExpand: Boolean = false,
        ): String? {
            if (isYouTubeVideoId(idOrPrefix)) return idOrPrefix
            if (!isYouTubeVideoIdPrefix(idOrPrefix)) return null
            ensurePrefixCacheLoaded()
            prefixCache[idOrPrefix]?.let { return it }
            if (idOrPrefix in ambiguousPrefixes) return null
            if (!allowNetworkExpand) return null

            val created = CompletableFuture<String?>()
            val existing = prefixInflight.putIfAbsent(idOrPrefix, created)
            if (existing != null) {
                return awaitPrefixFuture(existing)
            }
            try {
                val resolved = expandPrefixWithPermit(idOrPrefix)
                created.complete(resolved)
                return resolved
            } catch (ex: Exception) {
                created.complete(null)
                println("YouTube: expand failed for '$idOrPrefix': ${ex.message}")
                return null
            } finally {
                prefixInflight.remove(idOrPrefix, created)
            }
        }

        /**
         * Record a known full video id so a later 10-char Traktor decode can resolve via cache
         * without network expand (safe across Spotify/Tidal id collisions).
         */
        internal fun rememberVideoId(videoId: String) {
            if (!isYouTubeVideoId(videoId)) return
            ensurePrefixCacheLoaded()
            val prefix = videoId.substring(0, 10)
            if (prefix in ambiguousPrefixes) return
            val existing = prefixCache[prefix]
            if (existing != null && existing != videoId) {
                prefixCache.remove(prefix)
                ambiguousPrefixes.add(prefix)
                println(
                    "YouTube: conflicting full ids for prefix '$prefix' " +
                        "('$existing' vs '$videoId'); not caching"
                )
                return
            }
            if (existing == videoId) return
            prefixCache[prefix] = videoId
            persistPrefixCacheEntry(prefix, videoId)
        }

        private fun awaitPrefixFuture(future: CompletableFuture<String?>): String? {
            return try {
                future.get(PREFIX_EXPAND_WAIT_SECONDS, TimeUnit.SECONDS)
            } catch (_: Exception) {
                null
            }
        }

        private fun expandPrefixWithPermit(prefix: String): String? {
            // Re-check after joining any in-flight waiters / before taking a permit.
            prefixCache[prefix]?.let { return it }
            if (prefix in ambiguousPrefixes) return null
            if (!expandPermits.tryAcquire(PREFIX_EXPAND_WAIT_SECONDS, TimeUnit.SECONDS)) {
                println("YouTube: timed out waiting for expand permit for '$prefix'")
                return null
            }
            try {
                prefixCache[prefix]?.let { return it }
                if (prefix in ambiguousPrefixes) return null
                return when (val outcome = expandPrefixUncached(prefix)) {
                    is PrefixExpandOutcome.Unique -> {
                        prefixCache[prefix] = outcome.videoId
                        persistPrefixCacheEntry(prefix, outcome.videoId)
                        println("YouTube: expanded id prefix '$prefix' -> '${outcome.videoId}'")
                        outcome.videoId
                    }
                    is PrefixExpandOutcome.Ambiguous -> {
                        ambiguousPrefixes.add(prefix)
                        println(
                            "YouTube: ambiguous id prefix '$prefix' " +
                                "(${outcome.hits.size} matches: ${outcome.hits.take(5)}); not guessing"
                        )
                        null
                    }
                    PrefixExpandOutcome.None -> {
                        println("YouTube: could not expand id prefix '$prefix'")
                        null
                    }
                }
            } finally {
                expandPermits.release()
            }
        }

        /**
         * Probe all 11th-character candidates. Require a single HEAD hit; if two or more
         * succeed, treat as ambiguous (do not pick the first completed).
         */
        private fun expandPrefixUncached(prefix: String): PrefixExpandOutcome {
            val completion = ExecutorCompletionService<String?>(expandExecutor)
            val futures = ArrayList<Future<String?>>(YOUTUBE_ID_CHARS.size)
            for (ch in YOUTUBE_ID_CHARS) {
                val candidate = prefix + ch
                futures.add(completion.submit {
                    if (thumbnailProbe(candidate)) candidate else null
                })
            }
            val hits = ArrayList<String>(2)
            try {
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(12)
                run {
                    repeat(futures.size) {
                        val remainingMs = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())
                        if (remainingMs <= 0) return@run
                        val done = try {
                            completion.poll(remainingMs, TimeUnit.MILLISECONDS)
                        } catch (_: Exception) {
                            null
                        } ?: return@run
                        val hit = try {
                            done.get()
                        } catch (_: Exception) {
                            null
                        }
                        if (hit != null) {
                            hits.add(hit)
                            // Early exit once ambiguity is proven; still cancel outstanding HEADs.
                            if (hits.size > 1) return@run
                        }
                    }
                }
            } finally {
                futures.forEach { it.cancel(true) }
            }
            return when (hits.size) {
                0 -> PrefixExpandOutcome.None
                1 -> PrefixExpandOutcome.Unique(hits[0])
                else -> PrefixExpandOutcome.Ambiguous(hits.toList())
            }
        }

        private sealed class PrefixExpandOutcome {
            data class Unique(val videoId: String) : PrefixExpandOutcome()
            data class Ambiguous(val hits: List<String>) : PrefixExpandOutcome()
            data object None : PrefixExpandOutcome()
        }

        internal fun probeThumbnailExists(videoId: String): Boolean {
            return try {
                val con = URI.create(thumbnailUrl(videoId)).toURL().openConnection() as HttpURLConnection
                con.requestMethod = "HEAD"
                con.connectTimeout = 3_000
                con.readTimeout = 3_000
                con.instanceFollowRedirects = false
                con.setRequestProperty("User-Agent", "traktor-streaming-proxy/youtube-id")
                con.responseCode in 200..299
            } catch (_: Exception) {
                false
            }
        }

        private fun ensurePrefixCacheLoaded() {
            if (prefixCacheLoaded) return
            synchronized(prefixCacheLock) {
                if (prefixCacheLoaded) return
                if (!prefixCachePersistenceEnabled) {
                    prefixCacheLoaded = true
                    return
                }
                try {
                    val file = File(PREFIX_CACHE_FILE)
                    if (file.isFile) {
                        file.forEachLine { line ->
                            val trimmed = line.trim()
                            if (trimmed.isEmpty() || trimmed.startsWith("#")) return@forEachLine
                            val eq = trimmed.indexOf('=')
                            if (eq <= 0) return@forEachLine
                            val prefix = trimmed.substring(0, eq)
                            val full = trimmed.substring(eq + 1)
                            if (isYouTubeVideoIdPrefix(prefix) &&
                                isYouTubeVideoId(full) &&
                                full.startsWith(prefix)
                            ) {
                                prefixCache.putIfAbsent(prefix, full)
                            }
                        }
                    }
                } catch (ex: Exception) {
                    println("YouTube: failed to load id prefix cache: ${ex.message}")
                }
                prefixCacheLoaded = true
            }
        }

        private fun persistPrefixCacheEntry(prefix: String, full: String) {
            if (!prefixCachePersistenceEnabled) return
            synchronized(prefixCacheLock) {
                try {
                    // Keep memory map authoritative; rewrite small cache file.
                    prefixCache[prefix] = full
                    File(PREFIX_CACHE_FILE).bufferedWriter().use { writer ->
                        writer.write("# YouTube 10-char Traktor prefix -> 11-char video id\n")
                        prefixCache.entries.sortedBy { it.key }.forEach { (key, value) ->
                            writer.write("$key=$value\n")
                        }
                    }
                } catch (ex: Exception) {
                    println("YouTube: failed to persist id prefix cache: ${ex.message}")
                }
            }
        }

        /** Test helper: clear in-memory caches (does not delete the on-disk file). */
        internal fun clearPrefixCacheForTests() {
            prefixCache.clear()
            ambiguousPrefixes.clear()
            prefixInflight.clear()
            prefixCacheLoaded = true // skip reloading disk during unit tests
        }

        internal fun bestThumbnailUrl(thumbnails: List<org.schabi.newpipe.extractor.Image>): String? {
            return thumbnails
                .maxByOrNull { thumb ->
                    val w = if (thumb.width > 0) thumb.width else 0
                    val h = if (thumb.height > 0) thumb.height else 0
                    w * h
                }
                ?.url
                ?.takeIf { it.isNotBlank() }
        }
    }

    private fun getAudioStream(url: String): String {
        val extractor = ServiceList.YouTube.getStreamExtractor("https://www.youtube.com/watch?v=$url")
        extractor.fetchPage()
        return extractor.audioStreams.filter { it.format!!.name == "m4a" }.maxBy { it.averageBitrate }.content
    }

    class Downloader : org.schabi.newpipe.extractor.downloader.Downloader() {

        private val userAgent = "Mozilla/5.0 (Windows NT 10.0; WOW64; rv:68.0) Gecko/20100101 Firefox/68.0"

        override fun execute(request: Request): Response {
            val headers = HashMap<String, String>()
            headers["User-Agent"] = userAgent
            request.headers().forEach { (k, v) -> headers[k] = v[0]}

            val con = WebRequests.createConnection(request.url(), request.httpMethod(), headers)

            if (request.httpMethod() == "POST")
                WebRequests.post(con, request.dataToSend() as ByteArray)

            val res = WebRequests.request(con)

            return Response(res.status, "", con.headerFields, res.value, request.url())
        }
    }
}