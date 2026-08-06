import sources.SoundCloudAudioProfile
import sources.SoundCloudAudioSettings
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class SoundCloudAudioSettingsTest {
    @Test
    fun readsConfiguredAudioSettings() {
        val properties = Properties().apply {
            setProperty("soundcloud.audioQuality", "compatible")
            setProperty("soundcloud.transcodeBitrate", "320k")
            setProperty("soundcloud.preferRemux", "false")
            setProperty("soundcloud.audioProfile", "softHighs")
        }

        val settings = SoundCloudAudioSettings.from(properties)

        assertEquals(SoundCloudAudioSettings.QualityMode.COMPATIBLE, settings.qualityMode)
        assertEquals("320k", settings.transcodeBitrate)
        assertFalse(settings.preferRemux)
        assertEquals(SoundCloudAudioProfile.SOFT_HIGHS, settings.audioProfile)
    }

    @Test
    fun defaultsToBestQualityAndCleanProfile() {
        val settings = SoundCloudAudioSettings.from(Properties())

        assertEquals(SoundCloudAudioSettings.QualityMode.BEST, settings.qualityMode)
        assertEquals("256k", settings.transcodeBitrate)
        assertEquals(true, settings.preferRemux)
        assertEquals(SoundCloudAudioProfile.CLEAN, settings.audioProfile)
    }

    @Test
    fun rejectsOutOfRangeTranscodeBitrate() {
        val properties = Properties().apply {
            setProperty("soundcloud.transcodeBitrate", "513k")
        }

        assertEquals("256k", SoundCloudAudioSettings.from(properties).transcodeBitrate)
    }

    @Test
    fun rejectsUnknownAudioProfile() {
        val properties = Properties().apply {
            setProperty("soundcloud.audioProfile", "unknown")
        }

        assertEquals(
            SoundCloudAudioProfile.CLEAN,
            SoundCloudAudioSettings.from(properties).audioProfile
        )
    }
}
