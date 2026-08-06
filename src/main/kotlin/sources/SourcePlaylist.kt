package sources

/**
 * A playlist as identified by its source.
 *
 * Source IDs stay strings because providers such as YouTube and Spotify do not
 * use numeric playlist IDs. The HTTP layer converts them to Traktor-compatible
 * numeric IDs.
 */
data class SourcePlaylist(val id: String, val name: String)
