package beatport.api

import kotlinx.serialization.Serializable

@Serializable
data class Genre(val id: Int, val name: String)

@Serializable
data class Genres(val results: List<Genre>)

@Serializable
data class Artist(val id: Int, val name: String)

@Serializable
data class Track(val id: Long, val artists: List<Artist>, val name: String)

@Serializable
data class TrackResponse(val results: List<Track>)

@Serializable
data class Tracks(val tracks: List<Track>)

@Serializable
data class Download(val location: String, val stream_quality: String, val length_ms: Int)