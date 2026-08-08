import sources.SoundCloudAudioProfile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SoundCloudAudioProfileTest {
    @Test
    fun acceptsDocumentedProfileNames() {
        assertEquals(SoundCloudAudioProfile.OFF, SoundCloudAudioProfile.from("off"))
        assertEquals(SoundCloudAudioProfile.CLEAN, SoundCloudAudioProfile.from("clean"))
        assertEquals(
            SoundCloudAudioProfile.NORMALIZED,
            SoundCloudAudioProfile.from("normalized")
        )
        assertEquals(
            SoundCloudAudioProfile.SOFT_HIGHS,
            SoundCloudAudioProfile.from("softHighs")
        )
        assertNull(SoundCloudAudioProfile.from("unknown"))
    }

    @Test
    fun onlyOffProfileAllowsRemux() {
        assertFalse(SoundCloudAudioProfile.OFF.requiresTranscode)
        assertTrue(SoundCloudAudioProfile.CLEAN.requiresTranscode)
        assertTrue(SoundCloudAudioProfile.NORMALIZED.requiresTranscode)
        assertTrue(SoundCloudAudioProfile.SOFT_HIGHS.requiresTranscode)
    }

    @Test
    fun processingProfilesEndWithStereoHighQualityResampling() {
        val expected = "aresample=48000:resampler=soxr:precision=28:" +
            "out_channel_layout=stereo"

        assertTrue(requireNotNull(SoundCloudAudioProfile.CLEAN.filterChain).endsWith(expected))
        assertTrue(requireNotNull(SoundCloudAudioProfile.NORMALIZED.filterChain).endsWith(expected))
        assertTrue(requireNotNull(SoundCloudAudioProfile.SOFT_HIGHS.filterChain).endsWith(expected))
    }

    @Test
    fun softHighsReducesHighsWithoutNormalizingVolume() {
        val softHighs = requireNotNull(SoundCloudAudioProfile.SOFT_HIGHS.filterChain)
        val normalized = requireNotNull(SoundCloudAudioProfile.NORMALIZED.filterChain)

        assertTrue("highshelf=" in softHighs)
        assertFalse("loudnorm=" in softHighs)
        assertTrue("loudnorm=" in normalized)
    }
}
