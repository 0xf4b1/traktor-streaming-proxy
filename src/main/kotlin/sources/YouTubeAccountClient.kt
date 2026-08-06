package sources

import beatport.api.Artist
import beatport.api.Track
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Duration

internal class YouTubeAccountClient(credentials: OAuthCredentialStore) {
    private val api = AccountApi(credentials, "Bearer")

    fun getPlaylists(): List<SourcePlaylist> {
        val playlists = mutableListOf<SourcePlaylist>()
        var pageToken: String? = null
        var pageCount = 0
        do {
            check(pageCount++ < MAX_PAGES) { "YouTube account response exceeded $MAX_PAGES pages" }
            val response = api.getJson(
                "$API/playlists?part=snippet&mine=true&maxResults=50${page(pageToken)}"
            )
            response.items().forEach { item ->
                val id = item.string("id") ?: return@forEach
                val title = item.obj("snippet")?.string("title") ?: id
                playlists.add(SourcePlaylist(accountId(id), title))
            }
            pageToken = response.string("nextPageToken")
        } while (pageToken != null)

        getLikedPlaylist()?.let { liked ->
            if (playlists.none { it.id == liked.id }) {
                playlists.add(liked)
            }
        }
        return playlists
    }

    fun getPlaylist(accountPlaylistId: String): List<Track> {
        val playlistId = sourceId(accountPlaylistId)
        val items = mutableListOf<PlaylistVideo>()
        var pageToken: String? = null
        var pageCount = 0
        do {
            check(pageCount++ < MAX_PAGES) { "YouTube account playlist exceeded $MAX_PAGES pages" }
            val response = api.getJson(
                "$API/playlistItems?part=snippet,contentDetails&maxResults=50" +
                    "&playlistId=${encode(playlistId)}${page(pageToken)}"
            )
            response.items().mapNotNullTo(items, ::parsePlaylistVideo)
            pageToken = response.string("nextPageToken")
        } while (pageToken != null)

        val details = items.chunked(50).flatMap { chunk -> getVideoDetails(chunk.map { it.id }) }
            .associateBy(VideoDetails::id)
        return items.mapNotNull { item ->
            val detail = details[item.id] ?: return@mapNotNull null
            Track(
                item.id,
                listOf(Artist(1, detail.artist.ifBlank { item.artist })),
                detail.title.ifBlank { item.title },
                detail.durationMs
            )
        }
    }

    private fun getLikedPlaylist(): SourcePlaylist? {
        val response = api.getJson("$API/channels?part=contentDetails&mine=true&maxResults=1")
        val id = response.items().firstOrNull()
            ?.obj("contentDetails")
            ?.obj("relatedPlaylists")
            ?.string("likes")
            ?: return null
        return SourcePlaylist(accountId(id), "Liked videos")
    }

    private fun getVideoDetails(ids: List<String>): List<VideoDetails> {
        if (ids.isEmpty()) {
            return emptyList()
        }
        val response = api.getJson(
            "$API/videos?part=snippet,contentDetails&id=${encode(ids.joinToString(","))}&maxResults=50"
        )
        return response.items().mapNotNull { item ->
            val id = item.string("id") ?: return@mapNotNull null
            val snippet = item.obj("snippet")
            val duration = item.obj("contentDetails")?.string("duration")
            VideoDetails(
                id,
                snippet?.string("title").orEmpty(),
                snippet?.string("channelTitle").orEmpty(),
                duration?.let(::durationMillis) ?: 0
            )
        }
    }

    private fun parsePlaylistVideo(item: JsonObject): PlaylistVideo? {
        val snippet = item.obj("snippet")
        val id = item.obj("contentDetails")?.string("videoId")
            ?: snippet?.obj("resourceId")?.string("videoId")
            ?: return null
        return PlaylistVideo(
            id,
            snippet?.string("title").orEmpty(),
            snippet?.string("videoOwnerChannelTitle").orEmpty()
        )
    }

    private fun durationMillis(value: String): Long = runCatching {
        Duration.parse(value).toMillis()
    }.getOrDefault(0)

    private fun page(token: String?): String = token?.let { "&pageToken=${encode(it)}" }.orEmpty()

    private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)

    private data class PlaylistVideo(val id: String, val title: String, val artist: String)
    private data class VideoDetails(
        val id: String,
        val title: String,
        val artist: String,
        val durationMs: Long
    )

    companion object {
        private const val API = "https://www.googleapis.com/youtube/v3"
        private const val ACCOUNT_PREFIX = "youtube-account:"
        private const val MAX_PAGES = 1_000

        fun accountId(sourceId: String): String = "$ACCOUNT_PREFIX$sourceId"

        fun isAccountId(id: String): Boolean = id.startsWith(ACCOUNT_PREFIX)

        fun sourceId(accountId: String): String = accountId.removePrefix(ACCOUNT_PREFIX)
    }
}

private fun JsonObject.items(): List<JsonObject> =
    (this["items"] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()

private fun JsonObject.obj(name: String): JsonObject? = this[name] as? JsonObject

private fun JsonObject.string(name: String): String? =
    this[name]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)
