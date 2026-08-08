package beatport.api

import kotlinx.serialization.Serializable


@Serializable
data class Auth(val access_token: String,
                val expires_in: Int,
                val token_type: String,
                val scope: String,
                val refresh_token: String)

@Serializable
data class Account(val id: Int)

@Serializable
data class Genre(val id: Int, val name: String)

@Serializable
data class Genres(val results: List<Genre>)

@Serializable
data class Artist(val id: Int, val name: String)

/**
 * Beatport-shaped artwork object. Traktor reads [uri] / [dynamic_uri] from catalog track payloads.
 * [dynamic_uri] may contain `{w}` / `{h}` placeholders (Beatport convention).
 */
@Serializable
data class Image(
    val id: Long = 0,
    val uri: String = "",
    val dynamic_uri: String = ""
)

@Serializable
data class Label(
    val id: Long = 0,
    val name: String = "",
    /** Null when there is no artwork (omitted in JSON via explicitNulls = false). */
    val image: Image? = null
)

@Serializable
data class Release(
    val id: Long = 0,
    val name: String = "",
    /** Null when there is no artwork (omitted in JSON via explicitNulls = false). */
    val image: Image? = null,
    val label: Label? = null
)

/**
 * Build a minimal Beatport [Release] that carries cover art for Traktor.
 * When [imageUrl] is blank, returns a release with name only (no image fields).
 */
fun releaseWithArt(
    releaseName: String,
    imageUrl: String?,
    dynamicUri: String? = null
): Release {
    val uri = imageUrl?.trim().orEmpty()
    if (uri.isEmpty()) {
        return Release(name = releaseName)
    }
    val dynamic = dynamicUri?.trim()?.takeIf { it.isNotEmpty() } ?: uri
    val image = Image(uri = uri, dynamic_uri = dynamic)
    return Release(
        name = releaseName,
        image = image,
        label = Label(name = releaseName, image = image)
    )
}

data class Track(
    val id: String,
    val artists: List<Artist>,
    val name: String,
    val length_ms: Long,
    val release: Release = Release()
)

@Serializable
data class TrackResponse(
    val id: Long,
    val artists: List<Artist>,
    val name: String,
    val length_ms: Long,
    val release: Release? = null,
    /** Top-level image (real Beatport includes this alongside [release].image). Null = no art. */
    val image: Image? = null
)

@Serializable
data class GenreTrackResponse(val results: List<TrackResponse>, val next: String)

@Serializable
data class QueryTrackResponse(val tracks: List<TrackResponse>, val next: String)

@Serializable
data class Playlist(val id: Long, val name: String)

@Serializable
data class CuratedPlaylistsResponse(val results: List<Playlist>, val next: String)

@Serializable
data class PlaylistItem(val track: TrackResponse)

@Serializable
data class CuratedPlaylistResponse(val results: List<PlaylistItem>, val next: String)

@Serializable
data class Download(val location: String, val stream_quality: String, val length_ms: Int)

// --- New models for the Beatport Search API response ---

@Serializable
data class BeatportSearchResponse(
    val data: List<BeatportTrack>
)

@Serializable
data class BeatportTrack(
    val track_id: Long,
    val artists: List<BeatportArtist>,
    val track_name: String,
    val length: Long,
    val release: Release? = null,
    val image: Image? = null
)

@Serializable
data class BeatportArtist(
    val artist_name: String,
    val artist_type_name: String // Required by traktor
)
