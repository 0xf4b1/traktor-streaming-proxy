import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

data class SourcePlaylistReference(val sourceIndex: Int, val sourcePlaylistId: String)

/**
 * Maps provider-specific playlist IDs to positive numeric IDs accepted by
 * Beatport's API schema and Traktor.
 *
 * Small sequential IDs are used because Traktor does not preserve very large
 * 64-bit playlist IDs when it builds the playlist-tracks request. The reverse
 * mapping restores both the source and its alphanumeric playlist ID.
 */
class PlaylistIdRegistry {
    private val externalIds = ConcurrentHashMap<SourcePlaylistReference, Long>()
    private val sourceReferences = ConcurrentHashMap<Long, SourcePlaylistReference>()
    private val nextExternalId = AtomicLong(1)

    fun encode(sourceIndex: Int, sourcePlaylistId: String): Long {
        require(sourceIndex >= 0) { "Source index must not be negative" }
        require(sourcePlaylistId.isNotBlank()) { "Source playlist ID must not be blank" }

        val reference = SourcePlaylistReference(sourceIndex, sourcePlaylistId)
        return externalIds.computeIfAbsent(reference) {
            val externalId = nextExternalId.getAndIncrement()
            check(externalId > 0) { "Playlist ID space exhausted" }
            check(sourceReferences.putIfAbsent(externalId, reference) == null) {
                "Duplicate external playlist ID $externalId"
            }
            externalId
        }
    }

    fun decode(externalId: Long): SourcePlaylistReference? = sourceReferences[externalId]
}
