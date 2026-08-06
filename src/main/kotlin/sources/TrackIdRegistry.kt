package sources

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

internal class TrackIdRegistry {
    private val internalIds = ConcurrentHashMap<String, String>()
    private val sourceIds = ConcurrentHashMap<String, String>()
    private val nextId = AtomicLong(1)

    fun encode(sourceId: String): String = internalIds.computeIfAbsent(sourceId) {
        val internalId = "sc" + nextId.getAndIncrement().toString().padStart(8, '0')
        check(sourceIds.putIfAbsent(internalId, sourceId) == null) {
            "Duplicate internal SoundCloud track ID $internalId"
        }
        internalId
    }

    fun decode(internalId: String): String? = sourceIds[internalId]
}
