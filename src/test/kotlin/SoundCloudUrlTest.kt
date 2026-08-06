import sources.SoundCloudUrl
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SoundCloudUrlTest {
    @Test
    fun normalizesPublicPlaylistUrls() {
        assertEquals(
            "https://soundcloud.com/artist/sets/my-playlist",
            SoundCloudUrl.normalizePlaylist(
                "https://www.soundcloud.com/artist/sets/my-playlist?utm_source=test"
            )
        )
    }

    @Test
    fun rejectsTrackAndProfileUrls() {
        assertNull(SoundCloudUrl.normalizePlaylist("https://soundcloud.com/artist/track"))
        assertNull(SoundCloudUrl.normalizePlaylist("https://soundcloud.com/artist"))
    }

    @Test
    fun keepsPrivatePlaylistSecretTokens() {
        assertEquals(
            "https://soundcloud.com/artist/sets/private?secret_token=s-AbC123",
            SoundCloudUrl.normalizePlaylist(
                "https://soundcloud.com/artist/sets/private?secret_token=s-AbC123&utm_source=test"
            )
        )
    }
}
