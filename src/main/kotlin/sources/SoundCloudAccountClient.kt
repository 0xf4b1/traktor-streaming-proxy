package sources

import beatport.api.Artist
import beatport.api.Track
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

internal class SoundCloudAccountClient(
    credentials: OAuthCredentialStore,
    private val trackIds: TrackIdRegistry
) {
    private val api = AccountApi(credentials, "OAuth")
    private val userId by lazy(::resolveUserId)

    fun getPlaylists(): List<SourcePlaylist> {
        val playlists = mutableListOf(SourcePlaylist(accountId(LIKES_ID), "Liked tracks"))
        playlists += accountCollection(
            "owned playlists",
            "$API/me/playlists?show_tracks=false&linked_partitioning=true&limit=200",
            "$API/users/${encode(userId)}/playlists?linked_partitioning=true&limit=200"
        )
            .mapNotNull(::parsePlaylist)
        playlists += accountCollection(
            "liked playlists",
            "$API/me/likes/playlists?linked_partitioning=true&limit=200",
            "$API/users/${encode(userId)}/likes/playlists?linked_partitioning=true&limit=200"
        )
            .mapNotNull(::parsePlaylist)
        return playlists.distinctBy(SourcePlaylist::id)
    }

    fun getPlaylist(accountPlaylistId: String): List<Track> {
        val id = sourceId(accountPlaylistId)
        val trackObjects = if (id == LIKES_ID) {
            accountCollection(
                "liked tracks",
                "$API/me/likes/tracks?linked_partitioning=true&limit=200",
                "$API/users/${encode(userId)}/likes/tracks?linked_partitioning=true&limit=200"
            )
        } else {
            pagedCollection(
                "$API/playlists/${encode(id)}/tracks?linked_partitioning=true&limit=200"
            )
        }
        return trackObjects.mapNotNull(::parseTrack)
    }

    private fun resolveUserId(): String {
        val account = api.getJson("$API/me")
        return account.string("urn") ?: account.string("id")
            ?: error("SoundCloud /me response contained no account ID")
    }

    private fun accountCollection(
        label: String,
        primaryUrl: String,
        fallbackUrl: String
    ): List<JsonObject> {
        return try {
            pagedCollection(primaryUrl)
        } catch (primaryException: Exception) {
            System.err.println(
                "Could not load SoundCloud $label through /me; trying user endpoint: " +
                    primaryException.message
            )
            try {
                pagedCollection(fallbackUrl)
            } catch (fallbackException: Exception) {
                System.err.println(
                    "Could not load SoundCloud $label: ${fallbackException.message}"
                )
                emptyList()
            }
        }
    }

    private fun pagedCollection(initialUrl: String): List<JsonObject> {
        val results = mutableListOf<JsonObject>()
        var nextUrl: String? = initialUrl
        var pageCount = 0
        while (nextUrl != null) {
            check(pageCount++ < MAX_PAGES) { "SoundCloud account response exceeded $MAX_PAGES pages" }
            val response = api.getJson(nextUrl)
            results += response.array("collection")
            nextUrl = response.string("next_href")?.let(::absoluteApiUrl)
        }
        return results
    }

    private fun parsePlaylist(item: JsonObject): SourcePlaylist? {
        val playlist = item.obj("playlist") ?: item
        val id = playlist.string("urn") ?: playlist.string("id") ?: return null
        val title = playlist.string("title") ?: return null
        return SourcePlaylist(accountId(id), title)
    }

    private fun parseTrack(value: JsonObject): Track? {
        val item = value.obj("track") ?: value
        val url = item.string("permalink_url") ?: return null
        val secretToken = item.string("secret_token")
        val authenticatedUrl = if (secretToken == null) {
            url
        } else {
            "$url?secret_token=${encode(secretToken)}"
        }
        val artist = item.obj("user")?.string("username").orEmpty()
        return Track(
            trackIds.encode(authenticatedUrl),
            listOf(Artist(1, artist)),
            item.string("title") ?: url,
            item.long("duration") ?: 0
        )
    }

    private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)

    private fun absoluteApiUrl(value: String): String = when {
        value.startsWith("https://") -> value
        value.startsWith("/") -> "$API$value"
        else -> "$API/$value"
    }

    companion object {
        private const val API = "https://api.soundcloud.com"
        private const val ACCOUNT_PREFIX = "soundcloud-account:"
        private const val LIKES_ID = "likes"
        private const val MAX_PAGES = 1_000

        fun accountId(sourceId: String): String = "$ACCOUNT_PREFIX$sourceId"

        fun isAccountId(id: String): Boolean = id.startsWith(ACCOUNT_PREFIX)

        fun sourceId(accountId: String): String = accountId.removePrefix(ACCOUNT_PREFIX)
    }
}

private fun JsonObject.array(name: String): List<JsonObject> =
    (this[name] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()

private fun JsonObject.obj(name: String): JsonObject? = this[name] as? JsonObject

private fun JsonObject.string(name: String): String? =
    this[name]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)

private fun JsonObject.long(name: String): Long? = this[name]?.jsonPrimitive?.longOrNull
