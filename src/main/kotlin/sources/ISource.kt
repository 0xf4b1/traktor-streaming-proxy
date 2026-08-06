package sources

import beatport.api.Track
import java.io.OutputStream

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
    fun getCuratedPlaylists(reset: Boolean): List<SourcePlaylist>

    /**
     * Contents showing in Traktor when navigating to Curated Playlists-><source name>-><playlist name>
     */
    fun getCuratedPlaylist(id: String): List<Track>

    /**
     * Playlist names showing in Traktor when navigating to Playlists
     */
    fun getPlaylists(): List<SourcePlaylist>

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
     * Download music track data (must be in mp4 format)
     */
    fun download(id: String): ByteArray

    /**
     * Write music track data to the client. Sources may override this to start
     * delivering data before the complete track has been downloaded.
     */
    fun writeDownload(id: String, output: OutputStream) {
        output.write(download(id))
    }
}
