package sources

import java.util.Properties

internal data class SoundCloudAudioSettings(
    val qualityMode: QualityMode,
    val transcodeBitrate: String,
    val preferRemux: Boolean,
    val audioProfile: SoundCloudAudioProfile
) {
    enum class QualityMode {
        BEST,
        COMPATIBLE
    }

    companion object {
        private val BITRATE = Regex("^(\\d+)k$")

        fun from(properties: Properties): SoundCloudAudioSettings {
            val qualityMode = when (properties.getProperty("soundcloud.audioQuality", "best").trim().lowercase()) {
                "compatible" -> QualityMode.COMPATIBLE
                "best" -> QualityMode.BEST
                else -> {
                    System.err.println("Invalid soundcloud.audioQuality; using 'best'")
                    QualityMode.BEST
                }
            }
            val configuredBitrate = properties
                .getProperty("soundcloud.transcodeBitrate", "256k")
                .trim()
                .lowercase()
            val bitrateValue = BITRATE.matchEntire(configuredBitrate)
                ?.groupValues
                ?.get(1)
                ?.toIntOrNull()
            val transcodeBitrate = configuredBitrate.takeIf { bitrateValue in 32..512 } ?: "256k".also {
                System.err.println(
                    "Invalid soundcloud.transcodeBitrate '$configuredBitrate'; using '256k'"
                )
            }
            val configuredProfile = properties.getProperty("soundcloud.audioProfile", "clean")
            val audioProfile = SoundCloudAudioProfile.from(configuredProfile)
                ?: SoundCloudAudioProfile.CLEAN.also {
                    System.err.println(
                        "Invalid soundcloud.audioProfile '$configuredProfile'; using 'clean'"
                    )
                }
            return SoundCloudAudioSettings(
                qualityMode,
                transcodeBitrate,
                properties.getProperty("soundcloud.preferRemux", "true").toBooleanStrictOrNull()
                    ?: true,
                audioProfile
            )
        }
    }
}
