package sources

import beatport.api.Track

interface ISource {
    /**
     * Displayed source name in Traktor
     */
    val name:String

    /**
     * Contents showing in Traktor when navigating to Genre-><source name>
     */
    fun getGenre(): List<Track>

    /**
     * Playlist names showing in Traktor when navigating to Curated Playlists-><source name>
     */
    fun getPlaylists(reset: Boolean): List<String>

    /**
     * Contents showing in Traktor when navigating to Curated Playlists-><source name>-><playlist name>
     */
    fun getPlaylist(id: Int, reset: Boolean): List<Track>

    /**
     * Called when using search within Traktor
     */
    fun query(query: String, reset: Boolean): List<Track>

    /**
     * Download music track data (must be in mp4 format)
     */
    fun download(id: String): ByteArray
}