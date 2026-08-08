import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.FileTime
import java.security.MessageDigest
import java.util.Properties
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

class AudioDownloadCache private constructor(
    private val directory: Path,
    private val maxFiles: Int,
    workerCount: Int
) : AutoCloseable {
    private val executor = Executors.newFixedThreadPool(workerCount.coerceAtLeast(1))
    private val inFlight = ConcurrentHashMap<String, CompletableFuture<Path>>()
    private val pruneLock = Any()

    fun prepare(key: String, producer: () -> ByteArray): CompletableFuture<Path> {
        val target = directory.resolve("${sha256(key)}.mp4")
        if (isUsable(target)) {
            touch(target)
            return CompletableFuture.completedFuture(target)
        }

        while (true) {
            inFlight[key]?.let { return it }

            val result = CompletableFuture<Path>()
            if (inFlight.putIfAbsent(key, result) != null) {
                continue
            }
            executor.execute {
                try {
                    result.complete(create(target, producer))
                } catch (exception: Throwable) {
                    result.completeExceptionally(exception)
                } finally {
                    inFlight.remove(key, result)
                }
            }
            return result
        }
    }

    override fun close() {
        executor.shutdown()
    }

    private fun create(target: Path, producer: () -> ByteArray): Path {
        Files.createDirectories(directory)
        if (isUsable(target)) {
            touch(target)
            return target
        }

        val temporary = Files.createTempFile(directory, ".audio-", ".part")
        try {
            val audio = producer()
            check(audio.isNotEmpty()) { "Audio conversion returned an empty file" }
            Files.write(temporary, audio)
            moveIntoPlace(temporary, target)
        } finally {
            Files.deleteIfExists(temporary)
        }
        prune(target)
        return target
    }

    private fun prune(current: Path) = synchronized(pruneLock) {
        if (maxFiles < 1 || !Files.isDirectory(directory)) {
            return@synchronized
        }
        Files.list(directory).use { paths ->
            paths
                .filter { it != current && isUsable(it) && it.fileName.toString().endsWith(".mp4") }
                .sorted { left, right ->
                    Files.getLastModifiedTime(right).compareTo(Files.getLastModifiedTime(left))
                }
                .skip((maxFiles - 1).toLong())
                .forEach(Files::deleteIfExists)
        }
    }

    private fun moveIntoPlace(source: Path, target: Path) {
        try {
            Files.move(
                source,
                target,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun isUsable(path: Path): Boolean =
        Files.isRegularFile(path) && Files.size(path) > 0

    private fun touch(path: Path) {
        Files.setLastModifiedTime(path, FileTime.fromMillis(System.currentTimeMillis()))
    }

    private fun sha256(value: String): String = MessageDigest
        .getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }

    companion object {
        fun from(properties: Properties): AudioDownloadCache = AudioDownloadCache(
            directory = Path.of(
                properties.getProperty("server.audioCacheDirectory", "state/audio-cache")
            ),
            maxFiles = positiveInt(properties, "server.audioCacheMaxFiles", 20),
            workerCount = positiveInt(properties, "server.audioCacheWorkers", 1)
        )

        internal fun createForTest(
            directory: Path,
            maxFiles: Int = 20,
            workerCount: Int = 1
        ): AudioDownloadCache = AudioDownloadCache(directory, maxFiles, workerCount)

        private fun positiveInt(properties: Properties, key: String, default: Int): Int {
            val configured = properties.getProperty(key)?.trim()?.toIntOrNull()
            return configured?.takeIf { it > 0 } ?: default.also {
                if (properties.containsKey(key)) {
                    System.err.println("Invalid $key; using '$default'")
                }
            }
        }
    }
}
