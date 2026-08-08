package sources

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class YoutubeLookupTest {

    @AfterTest
    fun tearDown() {
        Youtube.thumbnailProbe = Youtube::probeThumbnailExists
        Youtube.prefixCachePersistenceEnabled = true
        Youtube.clearPrefixCacheForTests()
    }

    @Test
    fun isYouTubeVideoId_requiresElevenAlphabetChars() {
        assertTrue(Youtube.isYouTubeVideoId("dQw4w9WgXcQ"))
        assertTrue(Youtube.isYouTubeVideoId("afSgBNwmZr_"))
        assertFalse(Youtube.isYouTubeVideoId("afSgBNwmZr")) // truncated decode (10)
        assertFalse(Youtube.isYouTubeVideoId("104791279"))
        assertFalse(Youtube.isYouTubeVideoId(""))
    }

    @Test
    fun isYouTubeVideoIdPrefix_acceptsTenChars() {
        assertTrue(Youtube.isYouTubeVideoIdPrefix("dQw4w9WgXc"))
        assertTrue(Youtube.isYouTubeVideoIdPrefix("afSgBNwmZr"))
        assertFalse(Youtube.isYouTubeVideoIdPrefix("dQw4w9WgXcQ"))
        assertFalse(Youtube.isYouTubeVideoIdPrefix("short"))
    }

    @Test
    fun thumbnailUrl_usesIytimgCdn() {
        assertEquals(
            "https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg",
            Youtube.thumbnailUrl("dQw4w9WgXcQ")
        )
    }

    @Test
    fun resolveVideoIdFromPrefix_passthroughFullId() {
        assertEquals("dQw4w9WgXcQ", Youtube.resolveVideoIdFromPrefix("dQw4w9WgXcQ"))
        assertNull(Youtube.resolveVideoIdFromPrefix("104791279"))
        assertNull(Youtube.resolveVideoIdFromPrefix("short"))
    }

    @Test
    fun resolveVideoIdFromPrefix_defaultDoesNotNetworkExpand() {
        Youtube.prefixCachePersistenceEnabled = false
        Youtube.clearPrefixCacheForTests()
        var probes = 0
        Youtube.thumbnailProbe = {
            probes += 1
            true
        }
        // Spotify-like 10-char prefix must not be guessed as YouTube via i.ytimg.
        assertNull(Youtube.resolveVideoIdFromPrefix("4uLU6hMCjM"))
        assertNull(Youtube.resolveVideoIdFromPrefix("4uLU6hMCjM", allowNetworkExpand = false))
        assertEquals(0, probes)
    }

    @Test
    fun rememberVideoId_allowsColdPrefixResolveWithoutExpand() {
        Youtube.prefixCachePersistenceEnabled = false
        Youtube.clearPrefixCacheForTests()
        var probes = 0
        Youtube.thumbnailProbe = {
            probes += 1
            false
        }
        Youtube.rememberVideoId("dQw4w9WgXcQ")
        assertEquals("dQw4w9WgXcQ", Youtube.resolveVideoIdFromPrefix("dQw4w9WgXc"))
        assertEquals(0, probes)
    }

    @Test
    fun resolveVideoIdFromPrefix_singleFlightSharesOneExpand() {
        Youtube.prefixCachePersistenceEnabled = false
        Youtube.clearPrefixCacheForTests()
        val probes = AtomicInteger(0)
        val releaseProbes = CountDownLatch(1)
        Youtube.thumbnailProbe = { id ->
            probes.incrementAndGet()
            // Block until both callers have entered resolve, so the second joins in-flight.
            releaseProbes.await(2, TimeUnit.SECONDS)
            id == "dQw4w9WgXcQ"
        }

        val pool = Executors.newFixedThreadPool(2)
        try {
            val started = CountDownLatch(2)
            val f1 = pool.submit<String?> {
                started.countDown()
                started.await(2, TimeUnit.SECONDS)
                Youtube.resolveVideoIdFromPrefix("dQw4w9WgXc", allowNetworkExpand = true)
            }
            val f2 = pool.submit<String?> {
                started.countDown()
                started.await(2, TimeUnit.SECONDS)
                Youtube.resolveVideoIdFromPrefix("dQw4w9WgXc", allowNetworkExpand = true)
            }
            // Both threads are inside resolve; allow probes to finish.
            Thread.sleep(50)
            releaseProbes.countDown()
            assertEquals("dQw4w9WgXcQ", f1.get(5, TimeUnit.SECONDS))
            assertEquals("dQw4w9WgXcQ", f2.get(5, TimeUnit.SECONDS))
        } finally {
            pool.shutdownNow()
        }

        // One expand = at most one full alphabet sweep (64), not two (128).
        assertTrue(probes.get() in 1..64, "probes=${probes.get()}")
        // Cached second call must not probe again (even without allowNetworkExpand).
        val before = probes.get()
        assertEquals("dQw4w9WgXcQ", Youtube.resolveVideoIdFromPrefix("dQw4w9WgXc"))
        assertEquals(before, probes.get())
    }

    @Test
    fun resolveVideoIdFromPrefix_ambiguousWhenMultipleHits() {
        Youtube.prefixCachePersistenceEnabled = false
        Youtube.clearPrefixCacheForTests()
        Youtube.thumbnailProbe = { id ->
            id == "dQw4w9WgXcQ" || id == "dQw4w9WgXcX"
        }
        assertNull(Youtube.resolveVideoIdFromPrefix("dQw4w9WgXc", allowNetworkExpand = true))
        // Negative cache: do not probe again.
        var probes = 0
        Youtube.thumbnailProbe = {
            probes += 1
            false
        }
        assertNull(Youtube.resolveVideoIdFromPrefix("dQw4w9WgXc", allowNetworkExpand = true))
        assertEquals(0, probes)
    }

    @Test
    fun resolveVideoIdFromPrefix_uniqueHitOnly() {
        Youtube.prefixCachePersistenceEnabled = false
        Youtube.clearPrefixCacheForTests()
        Youtube.thumbnailProbe = { id -> id == "afSgBNwmZrQ" }
        assertEquals(
            "afSgBNwmZrQ",
            Youtube.resolveVideoIdFromPrefix("afSgBNwmZr", allowNetworkExpand = true)
        )
    }
}
