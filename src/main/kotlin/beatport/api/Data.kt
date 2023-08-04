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

data class Track(val id: String, val artists: List<Artist>, val name: String, val length_ms: Long)

@Serializable
data class TrackResponse(val id: Long, val artists: List<Artist>, val name: String, val length_ms: Long)

@Serializable
data class GenreTrackResponse(val results: List<TrackResponse>)

@Serializable
data class QueryTrackResponse(val tracks: List<TrackResponse>)

@Serializable
data class Download(val location: String, val stream_quality: String, val length_ms: Int)