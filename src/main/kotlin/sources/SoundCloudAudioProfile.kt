package sources

internal enum class SoundCloudAudioProfile(
    val filterChain: String?
) {
    OFF(null),
    CLEAN(
        "highpass=f=20,aresample=48000:resampler=soxr:precision=28"
    ),
    NORMALIZED(
        "highpass=f=20," +
            "loudnorm=I=-14:LRA=11:TP=-1.5," +
            "aresample=48000:resampler=soxr:precision=28"
    ),
    SOFT_HIGHS(
        "highpass=f=20," +
            "highshelf=f=12000:g=-2," +
            "loudnorm=I=-14:LRA=11:TP=-1.5," +
            "aresample=48000:resampler=soxr:precision=28"
    );

    val requiresTranscode: Boolean
        get() = filterChain != null

    companion object {
        fun from(value: String): SoundCloudAudioProfile? = when (
            value.trim().lowercase().replace("-", "").replace("_", "")
        ) {
            "off" -> OFF
            "clean" -> CLEAN
            "normalized" -> NORMALIZED
            "softhighs" -> SOFT_HIGHS
            else -> null
        }
    }
}
