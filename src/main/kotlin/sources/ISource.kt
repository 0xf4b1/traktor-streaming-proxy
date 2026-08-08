package sources

import beatport.api.Playlist
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
    fun getCuratedPlaylists(reset: Boolean): List<Playlist>

    /**
     * Contents showing in Traktor when navigating to Curated Playlists-><source name>-><playlist name>
     */
    fun getCuratedPlaylist(id: String): List<Track>

    /**
     * Playlist names showing in Traktor when navigating to Playlists
     */
    fun getPlaylists(): List<Playlist>

    /**
     * Contents showing in Traktor when navigating to Playlists-><playlist name>
     */
    fun getPlaylist(id: String): List<Track>

    /**
     * Contents showing in Traktor when navigating to Top 100-><source name>
     */
    fun getTop100(): List<Track>

    /**
     * Called when using search within Traktor
     */
    fun query(query: String, reset: Boolean): List<Track>

    /**
     * Resolve a single track by source-native id (for lazy catalog/cover hydration).
     * Default: unsupported.
     */
    fun lookupTrack(id: String): Track? = null

    /**
     * Download music track data (must be in mp4 format)
     */
    fun download(id: String): ByteArray
}