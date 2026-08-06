package sources

import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.DeliveryMethod

internal object SoundCloudAudioSelector {
    fun select(
        streams: List<AudioStream>,
        qualityMode: SoundCloudAudioSettings.QualityMode
    ): AudioStream? {
        val comparator = when (qualityMode) {
            SoundCloudAudioSettings.QualityMode.BEST -> compareByDescending<AudioStream> {
                it.averageBitrate
            }.thenByDescending(::formatPriority)
                .thenByDescending(::isProgressive)

            SoundCloudAudioSettings.QualityMode.COMPATIBLE -> compareByDescending<AudioStream> {
                isProgressive(it)
            }.thenByDescending { it.averageBitrate }
                .thenByDescending(::formatPriority)
        }
        return streams.sortedWith(comparator).firstOrNull()
    }

    fun canRemux(stream: AudioStream): Boolean = stream.format == MediaFormat.M4A

    private fun isProgressive(stream: AudioStream): Boolean =
        stream.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP

    private fun formatPriority(stream: AudioStream): Int = when (stream.format) {
        MediaFormat.M4A -> 3
        MediaFormat.OPUS, MediaFormat.WEBMA_OPUS -> 2
        MediaFormat.MP3 -> 1
        else -> 0
    }
}
