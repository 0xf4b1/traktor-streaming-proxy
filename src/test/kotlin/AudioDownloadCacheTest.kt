import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AudioDownloadCacheTest {
    @Test
    fun reusesCompletedDownload() = withCache { cache, directory ->
        val calls = AtomicInteger()
        val first = cache.prepare("track") {
            calls.incrementAndGet()
            byteArrayOf(1, 2, 3)
        }.get(5, TimeUnit.SECONDS)
        val second = cache.prepare("track") {
            calls.incrementAndGet()
            byteArrayOf(4)
        }.get(5, TimeUnit.SECONDS)

        assertEquals(first, second)
        assertEquals(1, calls.get())
        assertContentEquals(byteArrayOf(1, 2, 3), Files.readAllBytes(first))
        assertEquals(directory, first.parent)
    }

    @Test
    fun deduplicatesConcurrentDownload() = withCache(workerCount = 2) { cache, _ ->
        val calls = AtomicInteger()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val first = cache.prepare("track") {
            calls.incrementAndGet()
            started.countDown()
            release.await(5, TimeUnit.SECONDS)
            byteArrayOf(1)
        }
        assertTrue(started.await(5, TimeUnit.SECONDS))
        val second = cache.prepare("track") {
            calls.incrementAndGet()
            byteArrayOf(2)
        }
        release.countDown()

        assertEquals(first.get(5, TimeUnit.SECONDS), second.get(5, TimeUnit.SECONDS))
        assertEquals(1, calls.get())
    }

    @Test
    fun removesOldestDownload() = withCache(maxFiles = 2) { cache, directory ->
        val first = cache.prepare("one") { byteArrayOf(1) }.get(5, TimeUnit.SECONDS)
        Thread.sleep(5)
        val second = cache.prepare("two") { byteArrayOf(2) }.get(5, TimeUnit.SECONDS)
        Thread.sleep(5)
        val third = cache.prepare("three") { byteArrayOf(3) }.get(5, TimeUnit.SECONDS)

        assertFalse(Files.exists(first))
        assertTrue(Files.exists(second))
        assertTrue(Files.exists(third))
        Files.list(directory).use { assertEquals(2, it.count()) }
    }

    private fun withCache(
        maxFiles: Int = 20,
        workerCount: Int = 1,
        test: (AudioDownloadCache, java.nio.file.Path) -> Unit
    ) {
        val directory = createTempDirectory("audio-cache-test-")
        AudioDownloadCache.createForTest(directory, maxFiles, workerCount).use { cache ->
            try {
                test(cache, directory)
            } finally {
                directory.toFile().deleteRecursively()
            }
        }
    }
}
