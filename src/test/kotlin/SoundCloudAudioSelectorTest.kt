import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import sources.SoundCloudAudioSelector
import sources.SoundCloudAudioSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SoundCloudAudioSelectorTest {
    @Test
    fun bestModeChoosesHigherBitrateAac() {
        val mp3 = stream("mp3", MediaFormat.MP3, 128, DeliveryMethod.PROGRESSIVE_HTTP)
        val aac = stream("aac", MediaFormat.M4A, 160, DeliveryMethod.HLS)

        val selected = SoundCloudAudioSelector.select(
            listOf(mp3, aac),
            SoundCloudAudioSettings.QualityMode.BEST
        )

        assertEquals("aac", selected?.id)
        assertTrue(SoundCloudAudioSelector.canRemux(requireNotNull(selected)))
    }

    @Test
    fun compatibleModeKeepsProgressivePreference() {
        val mp3 = stream("mp3", MediaFormat.MP3, 128, DeliveryMethod.PROGRESSIVE_HTTP)
        val aac = stream("aac", MediaFormat.M4A, 160, DeliveryMethod.HLS)

        val selected = SoundCloudAudioSelector.select(
            listOf(aac, mp3),
            SoundCloudAudioSettings.QualityMode.COMPATIBLE
        )

        assertEquals("mp3", selected?.id)
        assertFalse(SoundCloudAudioSelector.canRemux(requireNotNull(selected)))
    }

    private fun stream(
        id: String,
        format: MediaFormat,
        bitrate: Int,
        deliveryMethod: DeliveryMethod
    ): AudioStream = AudioStream.Builder()
        .setId(id)
        .setContent("https://example.com/$id", true)
        .setMediaFormat(format)
        .setAverageBitrate(bitrate)
        .setDeliveryMethod(deliveryMethod)
        .build()
}
