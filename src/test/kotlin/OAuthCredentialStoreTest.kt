import sources.OAuthCredentialStore
import java.nio.file.Files
import java.time.Instant
import kotlin.io.path.deleteIfExists
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals

class OAuthCredentialStoreTest {
    @Test
    fun readsAStillValidAccessTokenWithoutRefreshing() {
        val file = Files.createTempFile("oauth", ".properties")
        try {
            file.writeText(
                "accessToken=working-token\n" +
                    "expiresAt=${Instant.now().epochSecond + 3_600}\n"
            )
            val store = OAuthCredentialStore(file.toFile(), "https://invalid.example/token")

            assertEquals("working-token", store.accessToken())
        } finally {
            file.deleteIfExists()
        }
    }
}
