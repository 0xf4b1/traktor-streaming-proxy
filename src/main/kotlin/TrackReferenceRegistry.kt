import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Base64
import java.util.Properties

data class SourceTrackReference(
    val sourceName: String,
    val sourceTrackId: String,
    val lengthMs: Long
)

private data class SourceTrackKey(val sourceName: String, val sourceTrackId: String)

/**
 * Assigns stable numeric IDs accepted by Traktor to provider-specific track IDs.
 * New mappings are flushed in batches by [processTracks].
 */
class TrackReferenceRegistry(private val stateFile: Path) {
    private val externalIds = HashMap<SourceTrackKey, Long>()
    private val sourceReferences = HashMap<Long, SourceTrackReference>()
    private var nextExternalId = 1L
    private var dirty = false

    init {
        load()
    }

    @Synchronized
    fun encode(sourceName: String, sourceTrackId: String, lengthMs: Long): Long {
        require(sourceName.isNotBlank()) { "Source name must not be blank" }
        require(sourceTrackId.isNotBlank()) { "Source track ID must not be blank" }

        val key = SourceTrackKey(sourceName, sourceTrackId)
        val existingId = externalIds[key]
        if (existingId != null) {
            val existing = sourceReferences.getValue(existingId)
            if (lengthMs > 0 && existing.lengthMs != lengthMs) {
                sourceReferences[existingId] = existing.copy(lengthMs = lengthMs)
                dirty = true
            }
            return existingId
        }

        return nextId().also { externalId ->
            externalIds[key] = externalId
            sourceReferences[externalId] = SourceTrackReference(sourceName, sourceTrackId, lengthMs)
            dirty = true
        }
    }

    @Synchronized
    fun decode(externalId: Long): SourceTrackReference? = sourceReferences[externalId]

    @Synchronized
    fun flush() {
        if (!dirty) {
            return
        }

        stateFile.parent?.let(Files::createDirectories)
        val properties = Properties()
        sourceReferences.toSortedMap().forEach { (externalId, reference) ->
            properties.setProperty(
                ENTRY_PREFIX + externalId,
                encodeValue(reference.sourceName) + "," +
                    encodeValue(reference.sourceTrackId) + "," + reference.lengthMs
            )
        }

        val parent = stateFile.toAbsolutePath().parent
        val temporaryFile = Files.createTempFile(parent, "track-ids-", ".tmp")
        try {
            Files.newOutputStream(temporaryFile).use { output ->
                properties.store(output, "Stable Traktor track ID mappings")
            }
            try {
                Files.move(
                    temporaryFile,
                    stateFile,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporaryFile, stateFile, StandardCopyOption.REPLACE_EXISTING)
            }
            dirty = false
        } finally {
            Files.deleteIfExists(temporaryFile)
        }
    }

    private fun load() {
        if (!Files.isRegularFile(stateFile)) {
            return
        }

        val properties = Properties()
        Files.newInputStream(stateFile).use(properties::load)
        properties.stringPropertyNames()
            .filter { it.startsWith(ENTRY_PREFIX) }
            .forEach { key ->
                val externalId = key.removePrefix(ENTRY_PREFIX).toLongOrNull()
                val parts = properties.getProperty(key).split(',', limit = 3)
                val reference = if (parts.size >= 2) {
                    runCatching {
                        SourceTrackReference(
                            decodeValue(parts[0]),
                            decodeValue(parts[1]),
                            parts.getOrNull(2)?.toLongOrNull() ?: 0
                        )
                    }.getOrNull()
                } else {
                    null
                }
                if (externalId == null || externalId <= 0 || reference == null ||
                    reference.sourceName.isBlank() || reference.sourceTrackId.isBlank()
                ) {
                    System.err.println("Ignoring invalid track registry entry '$key'")
                    return@forEach
                }
                val sourceKey = SourceTrackKey(reference.sourceName, reference.sourceTrackId)
                if (sourceReferences.containsKey(externalId) || externalIds.containsKey(sourceKey)) {
                    System.err.println("Ignoring duplicate track registry entry '$key'")
                    return@forEach
                }
                sourceReferences[externalId] = reference
                externalIds[sourceKey] = externalId
                nextExternalId = maxOf(nextExternalId, externalId + 1)
            }
    }

    private fun nextId(): Long {
        val externalId = nextExternalId++
        check(externalId > 0) { "Track ID space exhausted" }
        return externalId
    }

    private fun encodeValue(value: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray(Charsets.UTF_8))

    private fun decodeValue(value: String): String =
        String(Base64.getUrlDecoder().decode(value), Charsets.UTF_8)

    companion object {
        private const val ENTRY_PREFIX = "track."
    }
}
