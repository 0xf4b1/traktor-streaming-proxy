import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class TrackReferenceRegistryTest {
    @Test
    fun persistsProviderTrackReferences() = withStateFile { stateFile ->
        val firstRegistry = TrackReferenceRegistry(stateFile)
        val externalId = firstRegistry.encode(
            "SoundCloud",
            "https://soundcloud.com/artist/track?secret_token=private",
            180_000
        )
        firstRegistry.flush()

        val restored = TrackReferenceRegistry(stateFile).decode(externalId)

        assertEquals(
            SourceTrackReference(
                "SoundCloud",
                "https://soundcloud.com/artist/track?secret_token=private",
                180_000
            ),
            restored
        )
    }

    @Test
    fun keepsIdsStableAndUpdatesDuration() = withStateFile { stateFile ->
        val registry = TrackReferenceRegistry(stateFile)

        val first = registry.encode("SoundCloud", "first", 0)
        val repeated = registry.encode("SoundCloud", "first", 120_000)
        val second = registry.encode("SoundCloud", "second", 130_000)

        assertEquals(first, repeated)
        assertNotEquals(first, second)
        assertEquals(120_000, registry.decode(first)?.lengthMs)
    }

    @Test
    fun ignoresInvalidPersistedEntries() = withStateFile { stateFile ->
        Files.writeString(stateFile, "track.invalid=not-base64\n")

        assertNull(TrackReferenceRegistry(stateFile).decode(1))
    }

    private fun withStateFile(test: (java.nio.file.Path) -> Unit) {
        val directory = createTempDirectory("track-registry-test-")
        try {
            test(directory.resolve("track-ids.properties"))
        } finally {
            directory.toFile().deleteRecursively()
        }
    }
}
