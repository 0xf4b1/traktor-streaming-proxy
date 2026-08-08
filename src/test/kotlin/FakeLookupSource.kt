import beatport.api.Playlist
import beatport.api.Track
import sources.ISource

/** Minimal [ISource] that only implements [lookupTrack] for catalog hydration tests. */
class FakeLookupSource(private val byId: Map<String, Track>) : ISource {
    override val name: String = "FakeLookup"
    override fun getGenre(): List<Track> = emptyList()
    override fun getCuratedPlaylists(reset: Boolean): List<Playlist> = emptyList()
    override fun getCuratedPlaylist(id: String): List<Track> = emptyList()
    override fun getPlaylists(): List<Playlist> = emptyList()
    override fun getPlaylist(id: String): List<Track> = emptyList()
    override fun getTop100(): List<Track> = emptyList()
    override fun query(query: String, reset: Boolean): List<Track> = emptyList()
    override fun lookupTrack(id: String): Track? = byId[id]
    override fun download(id: String): ByteArray = ByteArray(0)
}
