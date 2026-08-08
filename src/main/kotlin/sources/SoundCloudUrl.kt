package sources

internal object SoundCloudUrl {
    private val playlistUrl = Regex(
        "^https?://(?:www\\.|m\\.)?soundcloud\\.com/([^/?#]+)/sets/([^/?#]+)(?:[/?#].*)?$",
        RegexOption.IGNORE_CASE
    )

    fun normalizePlaylist(value: String): String? {
        val candidate = value.trim()
        val match = playlistUrl.matchEntire(candidate) ?: return null
        val baseUrl = "https://soundcloud.com/${match.groupValues[1]}/sets/${match.groupValues[2]}"
        val secretToken = SECRET_TOKEN.find(candidate)?.groupValues?.get(1)
        return secretToken?.let { "$baseUrl?secret_token=$it" } ?: baseUrl
    }

    private val SECRET_TOKEN = Regex("[?&]secret_token=(s-[A-Za-z0-9_-]+)")
}
