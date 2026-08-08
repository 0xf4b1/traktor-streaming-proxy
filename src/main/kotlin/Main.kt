import Config.prop
import beatport.api.*
import io.ktor.http.*
import io.ktor.network.tls.certificates.buildKeyStore
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.engine.*
import io.ktor.server.engine.sslConnector
import io.ktor.server.netty.*
import io.ktor.server.plugins.calllogging.*
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.request.queryString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import org.apache.log4j.BasicConfigurator
import sources.ISource
import sources.Spotify
import sources.Tidal
import sources.Youtube
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.security.KeyStore
import java.util.*
import kotlin.collections.ArrayList
import kotlin.math.min

val sources: ArrayList<ISource> = ArrayList()
val trackIdToSource: HashMap<String, Int> = HashMap()
val traktorIdToTrackId: HashMap<Long, String> = HashMap()
internal val traktorIdToTrackResponse: HashMap<Long, TrackResponse> = HashMap()
internal val traktorIdToCoverOrigin: HashMap<Long, String> = HashMap()

private const val COVER_CACHE_MAX = 256
private const val COVER_CACHE_MAX_BYTES = 16 * 1024 * 1024
private var lastDownloadData = ByteArray(0)

val allSources = mapOf(
    "youtube" to Youtube::class.java,
    "spotify" to Spotify::class.java,
    "tidal" to Tidal::class.java
)

object Config {
    val prop = Properties()

    fun readConfig() {
        val file = File("config.properties")
        if (!file.exists())
            return
        FileInputStream(file).use { prop.load(it) }
    }

    fun saveConfig() {
        val file = File("config.properties")
        FileOutputStream(file).use {
            prop.store(it, "")
        }
    }
}

fun register(source: Class<out ISource>) {
    try {
        sources.add(source.getConstructor().newInstance())
    } catch (ex: Exception) {
        println("Can not instantiate $source: ${ex.printStackTrace()}")
    }
}

/**
 * Traktor loads artwork from [Image.uri] / [Image.dynamic_uri]. Point both at our HTTPS host
 * (`api.beatport.com` via /etc/hosts) so fetches go through this proxy.
 *
 * When [hasArt] is false, returns null so JSON omits image fields (`explicitNulls = false`).
 */
internal fun proxiedCoverImage(traktorId: Long, hasArt: Boolean): Image? {
    if (!hasArt) return null
    val uri = coverProxyUrl(traktorId)
    return Image(id = traktorId, uri = uri, dynamic_uri = uri)
}

internal fun coverProxyUrl(traktorId: Long): String =
    "https://api.beatport.com/v4/catalog/tracks/$traktorId/cover/"

internal fun parseCatalogTrackIds(params: Parameters): List<Long> {
    val fromId = params.getAll("id").orEmpty()
    val fromIds = params.getAll("ids").orEmpty()
    return (fromId + fromIds)
        .flatMap { value -> value.split(',', ' ', '\t') }
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .mapNotNull { it.toLongOrNull() }
        .distinct()
}

/**
 * Allow only remote HTTPS cover origins. Blocks cleartext and common local/private targets
 * to reduce SSRF risk when proxying artwork.
 */
internal fun isAllowedCoverOriginUrl(url: String): Boolean {
    val uri = try {
        URI(url.trim())
    } catch (_: Exception) {
        return false
    }
    if (!uri.scheme.equals("https", ignoreCase = true)) return false
    if (!uri.userInfo.isNullOrEmpty()) return false
    val host = uri.host?.lowercase()?.trim('.') ?: return false
    if (host.isEmpty()) return false
    if (host == "localhost" || host.endsWith(".localhost") || host.endsWith(".local")) {
        return false
    }
    return !isPrivateOrLocalHost(host)
}

internal fun isPrivateOrLocalHost(host: String): Boolean {
    if (host == "::1" || host == "0:0:0:0:0:0:0:1") return true
    val ipv4 = host.split('.')
    if (ipv4.size == 4 && ipv4.all { part -> part.toIntOrNull()?.let { it in 0..255 } == true }) {
        val a = ipv4[0].toInt()
        val b = ipv4[1].toInt()
        return when {
            a == 0 -> true
            a == 10 -> true
            a == 127 -> true
            a == 169 && b == 254 -> true
            a == 172 && b in 16..31 -> true
            a == 192 && b == 168 -> true
            else -> false
        }
    }
    if (host.contains(':')) {
        return host.startsWith("fc") ||
            host.startsWith("fd") ||
            host.startsWith("fe80") ||
            host.startsWith("::ffff:127.") ||
            host.startsWith("::ffff:10.") ||
            host.startsWith("::ffff:192.168.")
    }
    return false
}

internal data class CoverDownload(val bytes: ByteArray, val contentType: ContentType)

/**
 * LRU cache of cover bytes keyed by upstream origin URL.
 */
internal class CoverCache(
    private val maxSize: Int = COVER_CACHE_MAX,
    private val maxBytes: Long = COVER_CACHE_MAX_BYTES.toLong()
) {
    private val lock = Any()
    private var totalBytes = 0L
    private val map = LinkedHashMap<String, CoverDownload>(maxSize, 0.75f, true)

    init {
        require(maxSize > 0) { "maxSize must be positive" }
        require(maxBytes > 0) { "maxBytes must be positive" }
    }

    fun put(originUrl: String, cover: CoverDownload) {
        synchronized(lock) {
            val stored = CoverDownload(cover.bytes.copyOf(), cover.contentType)
            val previous = map.put(originUrl, stored)
            totalBytes += stored.bytes.size
            if (previous != null) {
                totalBytes -= previous.bytes.size
            }
            trimToLimits()
        }
    }

    fun get(originUrl: String): CoverDownload? {
        synchronized(lock) {
            val hit = map[originUrl] ?: return null
            return CoverDownload(hit.bytes.copyOf(), hit.contentType)
        }
    }

    fun size(): Int = synchronized(lock) { map.size }

    fun byteSize(): Long = synchronized(lock) { totalBytes }

    private fun trimToLimits() {
        val iterator = map.entries.iterator()
        while (
            (map.size > maxSize || totalBytes > maxBytes) &&
            map.size > 1 &&
            iterator.hasNext()
        ) {
            val eldest = iterator.next()
            totalBytes -= eldest.value.bytes.size
            iterator.remove()
        }
    }
}

internal val coverCache = CoverCache()

/** Overridable for tests; production uses [downloadCover]. */
internal var coverDownloader: (String) -> CoverDownload = ::downloadCover

internal fun getCachedOrDownloadCover(origin: String): CoverDownload {
    coverCache.get(origin)?.let { return it }
    val cover = coverDownloader(origin)
    coverCache.put(origin, cover)
    return cover
}

fun processTracks(id: Int, tracks: List<Track>): List<TrackResponse> {
    return tracks.map { track ->
        trackIdToSource[track.id] = id
        val traktorId = Utils.encode(track.id.substring(0, min(track.id.length, 10)))
        if (!traktorIdToTrackId.containsKey(traktorId)) {
            traktorIdToTrackId[traktorId] = if (track.id.length > 10) track.id.substring(10) else ""
        }
        val rawOrigin = track.release.image?.uri?.takeIf { it.isNotBlank() }
            ?: track.release.image?.dynamic_uri?.takeIf { it.isNotBlank() }
        val origin = rawOrigin?.takeIf { isAllowedCoverOriginUrl(it) }
        if (origin != null) {
            traktorIdToCoverOrigin[traktorId] = origin
        } else {
            traktorIdToCoverOrigin.remove(traktorId)
        }
        val image = proxiedCoverImage(traktorId, origin != null)
        val release = if (origin != null) {
            val labelBase = track.release.label ?: Label(name = track.release.name)
            track.release.copy(image = image, label = labelBase.copy(image = image))
        } else {
            track.release.copy(image = null, label = track.release.label?.copy(image = null))
        }
        TrackResponse(traktorId, track.artists, track.name, track.length_ms, release, image).also {
            traktorIdToTrackResponse[traktorId] = it
        }
    }
}

/** Reconstruct native track id from Traktor id using the upstream prefix+suffix maps. */
internal fun resolveFullTrackId(traktorId: Long): String? {
    val prefix = try {
        Utils.decode(traktorId)
    } catch (_: Exception) {
        return null
    }
    if (prefix.isBlank()) return null
    val suffix = traktorIdToTrackId[traktorId]
    return if (suffix != null) prefix + suffix else prefix
}

internal fun getCoverOriginUrl(traktorId: Long): String? = traktorIdToCoverOrigin[traktorId]

internal fun getStoredTrack(traktorId: Long): TrackResponse? = traktorIdToTrackResponse[traktorId]

/** Test helper: drop in-memory mapping for a Traktor id. */
internal fun clearTrackMapping(traktorId: Long) {
    val fullId = resolveFullTrackId(traktorId)
    traktorIdToTrackId.remove(traktorId)
    traktorIdToTrackResponse.remove(traktorId)
    traktorIdToCoverOrigin.remove(traktorId)
    if (fullId != null) {
        trackIdToSource.remove(fullId)
    }
}

/**
 * Ask enabled sources for a track by native id. Returns the first hit as (sourceIndex, track).
 *
 * [trackId] may be a truncated Traktor decode (≤10 chars). YouTube [Youtube.lookupTrack]
 * only accepts full ids or cached prefixes (no blind network expand).
 */
internal fun resolveTrackFromSources(trackId: String): Pair<Int, Track>? {
    if (trackId.isBlank()) return null
    sources.forEachIndexed { index, source ->
        val track = try {
            source.lookupTrack(trackId)
        } catch (ex: Exception) {
            println("lookupTrack failed for $trackId on ${source.name}: ${ex.message}")
            null
        }
        if (track != null) {
            return index to track
        }
    }
    return null
}

/**
 * Return a catalog [TrackResponse] for [traktorId], hydrating from sources when the in-memory
 * mapping is missing or has no cover origin.
 */
internal fun ensureCatalogTrack(traktorId: Long): TrackResponse? {
    val existing = traktorIdToTrackResponse[traktorId]
    if (existing != null && traktorIdToCoverOrigin[traktorId] != null) {
        return existing
    }
    val rawId = resolveFullTrackId(traktorId)
    if (rawId.isNullOrBlank()) {
        return existing
    }

    val preferredSource = trackIdToSource[rawId]
    val fromPreferred = preferredSource?.let { sourceId ->
        sources.getOrNull(sourceId)?.let { source ->
            try {
                source.lookupTrack(rawId)?.let { sourceId to it }
            } catch (ex: Exception) {
                println("lookupTrack failed for $rawId on ${source.name}: ${ex.message}")
                null
            }
        }
    }
    val resolved = fromPreferred ?: resolveTrackFromSources(rawId) ?: return existing
    val merged = mergeCatalogLookup(existing, resolved.second)
    return processTracks(resolved.first, listOf(merged)).firstOrNull() ?: existing
}

/**
 * When refreshing a known track (e.g. YouTube extractor fallback with name=id / duration=0),
 * keep existing title/artists/duration and only adopt cover (and better fields) from [lookedUp].
 */
internal fun mergeCatalogLookup(existing: TrackResponse?, lookedUp: Track): Track {
    if (existing == null) return lookedUp

    val lookupNameIsPlaceholder =
        lookedUp.name.isBlank() || lookedUp.name == lookedUp.id
    val name = when {
        existing.name.isNotBlank() && lookupNameIsPlaceholder -> existing.name
        lookedUp.name.isNotBlank() && !lookupNameIsPlaceholder -> lookedUp.name
        existing.name.isNotBlank() -> existing.name
        else -> lookedUp.name
    }
    val lengthMs = when {
        existing.length_ms > 0L && lookedUp.length_ms <= 0L -> existing.length_ms
        lookedUp.length_ms > 0L -> lookedUp.length_ms
        else -> existing.length_ms
    }
    val lookupArtistIsPlaceholder = lookedUp.artists.isEmpty() ||
        lookedUp.artists.all { it.name.isBlank() || it.name.equals("YouTube", ignoreCase = true) }
    val artists = when {
        existing.artists.isNotEmpty() && lookupArtistIsPlaceholder -> existing.artists
        lookedUp.artists.isNotEmpty() -> lookedUp.artists
        else -> existing.artists
    }

    val releaseName = existing.release?.name.orEmpty()
        .ifBlank { lookedUp.release.name }
        .ifBlank { name }
    val lookupCover = lookedUp.release.image?.uri?.takeIf { it.isNotBlank() }
        ?: lookedUp.release.image?.dynamic_uri?.takeIf { it.isNotBlank() }
    val release = if (lookupCover != null) {
        releaseWithArt(
            releaseName,
            lookupCover,
            lookedUp.release.image?.dynamic_uri?.takeIf { it.isNotBlank() }
        )
    } else {
        existing.release ?: lookedUp.release
    }

    return Track(
        id = lookedUp.id.ifBlank { resolveFullTrackId(existing.id) ?: existing.id.toString() },
        artists = artists,
        name = name,
        length_ms = lengthMs,
        release = release
    )
}

/**
 * Executes a search across enabled sources.
 *
 * @param q The raw query string from the request.
 * @param hasMoreParameter A flag indicating if the 'more' parameter is present.
 * @return A list of processed track responses.
 */
private fun executeSearch(q: String, hasMoreParameter: Boolean): List<TrackResponse> {
    var query = q
    val enabledSources = if (q.contains(":")) {
        val (sourceName, actualQuery) = q.split(":", limit = 2)
        query = actualQuery
        listOf(allSources[sourceName])
    } else {
        prop.getProperty("search.enabled", "").split(",").map { name -> allSources[name] }
    }

    return sources.mapIndexed { id, source ->
        if (enabledSources.contains(null) || source::class.java in enabledSources) {
            processTracks(id, source.query(query, !hasMoreParameter))
        } else {
            emptyList()
        }
    }.flatten()
}

fun main() {
    BasicConfigurator.configure()

    Config.readConfig()
    Runtime.getRuntime().addShutdownHook(object : Thread() {
        override fun run() {
            Config.saveConfig()
        }
    })

    prop.getProperty("sources.enabled", "").split(",").map { name -> allSources[name] }.forEach {
        if (it != null)
            register(it)
    }

    val alias = "foo"
    var serverConfiguration: NettyApplicationEngine.Configuration.() -> Unit

    if (prop.getProperty("server.useKeystore", "false").toBoolean()) {
        val keystorePassword = "changeit"

        val keyStore = KeyStore.getInstance("JKS").apply {
            File("cert/keystore.jks").inputStream().use {
                load(it, keystorePassword.toCharArray())
            }
        }
        serverConfiguration = {
            sslConnector(
                keyStore,
                alias,
                { keystorePassword.toCharArray() },
                { keystorePassword.toCharArray() }
            ) {
                port = 8443
            }
        }
    } else {
        serverConfiguration = {
            sslConnector(buildKeyStore {
                certificate(alias) {
                    password = alias
                    domains = listOf("api.beatport.com")
                }
            }, alias, { "".toCharArray() }, { alias.toCharArray() }) {
                port = 8443
            }
        }
    }

    embeddedServer(Netty, applicationEnvironment(), serverConfiguration, module = Application::module)
        .start(wait = true)
}

/**
 * Shared JSON settings for Beatport-shaped API responses.
 *
 * - [encodeDefaults] = true: do not globally drop default-valued fields (safer for Traktor).
 * - [explicitNulls] = false: omit nullable art fields (`image` / `release.image`) when absent.
 */
internal val apiJson = Json {
    prettyPrint = true
    isLenient = true
    encodeDefaults = true
    explicitNulls = false
}

/**
 * Ktor application module (shared by the HTTPS server and [io.ktor.server.testing.testApplication]).
 */
fun Application.module() {
    install(CallLogging) {
        format { call ->
            val status = call.response.status()
            val method = call.request.httpMethod.value
            val path = call.request.path()
            val query = call.request.queryString()
            val uri = if (query.isEmpty()) path else "$path?$query"
            val statusText = status?.toString() ?: "Unhandled"
            "$statusText: $method - $uri"
        }
    }
    install(ContentNegotiation) {
        json(apiJson)
    }
    routing {

        get("/v4/auth/o/authorize/") {
            call.respondRedirect("traktor://bp_oauth?code=foo")
        }

        post("/v4/auth/o/token/") {
            call.respond(Auth("foo", 36000, "Bearer", "app:locker user:dj", "bar"))
        }

        get("/v4/auth/logout/") {
            call.respond(HttpStatusCode.OK)
        }

        get("/v4/my/account/") {
            call.respond(Account(prop.getProperty("beatport.accountId").toInt()))
        }

        get("/v4/my/license/") {
            val licenseName = prop.getProperty("beatport.license", "macos")
            val licenseFile = Config::class.java.getResource("licenses/${licenseName}.json")

            if (licenseFile == null) {
                call.respond(HttpStatusCode.InternalServerError, "License file '${licenseName}' not found")
            } else {
                call.respondBytes(licenseFile.readBytes())
            }
        }

        get("/v4/catalog/search") {
            val q = call.parameters["q"] ?: return@get call.respond(HttpStatusCode.BadRequest)
            val results = executeSearch(q, call.parameters.contains("more"))
            val nextUrl = if (results.isNotEmpty()) "api.beatport.com/v4/catalog/search?q=$q&more" else ""
            call.respond(QueryTrackResponse(results, nextUrl))
        }

        get("/search/v1/tracks") {
            val q = call.parameters["q"] ?: return@get call.respond(HttpStatusCode.BadRequest)
            val results = executeSearch(q, call.parameters.contains("more"))
            call.respond(BeatportSearchResponse(results.toNewSearchApi()))
        }

        get("/v4/catalog/genres") {
            call.respondRedirect("/v4/catalog/genres/")
        }

        get("/v4/catalog/genres/") {
            call.respond(Genres(sources.mapIndexed { id, source -> Genre(id + 1, source.name) }))
        }

        get("/v4/catalog/genres/{id}/tracks/") {
            call.parameters["id"]?.let {
                call.respond(
                    GenreTrackResponse(
                        processTracks(it.toInt() - 1, sources[it.toInt() - 1].getGenre()),
                        "" /* unused by Traktor */
                    )
                )
            }
        }

        get("/v4/curation/playlists/") {
            call.parameters["genre_id"]?.let {
                val results = sources[it.toInt() - 1].getCuratedPlaylists(!call.parameters.contains("more")).map { playlist ->
                    Playlist((it + playlist.id).toLong(), playlist.name)
                }
                call.respond(
                    CuratedPlaylistsResponse(
                        results,
                        if (results.isNotEmpty()) "api.beatport.com/v4/curation/playlists/?genre_id=$it&more" else ""
                    )
                )
            }
        }

        get("/v4/curation/playlists/{id}/tracks/") {
            call.parameters["id"]?.let {
                val sourceId = it.substring(0, 1).toInt() - 1
                val results = processTracks(sourceId, sources[sourceId].getCuratedPlaylist(it.substring(1)))
                call.respond(
                    CuratedPlaylistResponse(
                        results.map { track -> PlaylistItem(track) },
                        "" /* unused by Traktor */
                    )
                )
            }
        }

        get("/v4/my/playlists/") {
            call.respond(
                CuratedPlaylistsResponse(
                    sources.mapIndexed { id, source ->
                        source.getPlaylists().map { playlist ->
                            Playlist("${id + 1}${playlist.id}".toLong(), playlist.name)
                        }
                    }.flatten(),
                    "" /* not needed */
                )
            )
        }

        get("/v4/my/playlists/{id}/tracks/") {
            call.parameters["id"]?.let {
                val sourceId = it.substring(0, 1).toInt() - 1
                val results = processTracks(sourceId, sources[sourceId].getPlaylist(it.substring(1)))
                call.respond(
                    CuratedPlaylistResponse(
                        results.map { track -> PlaylistItem(track) },
                        "" /* unused by Traktor */
                    )
                )
            }
        }

        get("/v4/catalog/genres/{id}/top/100/") {
            call.parameters["id"]?.let {
                call.respond(
                    GenreTrackResponse(
                        processTracks(it.toInt() - 1, sources[it.toInt() - 1].getTop100()),
                        "" /* unused by Traktor */
                    )
                )
            }
        }

        get("/v4/catalog/tracks/") {
            val ids = parseCatalogTrackIds(call.request.queryParameters)
            if (ids.isEmpty()) {
                call.respond(GenreTrackResponse(emptyList(), ""))
                return@get
            }
            val results = withContext(Dispatchers.IO) {
                ids.mapNotNull { ensureCatalogTrack(it) }
            }
            call.respond(GenreTrackResponse(results, ""))
        }

        get("/v4/catalog/tracks/{id}/") {
            call.parameters["id"]?.let { id ->
                val traktorId = id.toLongOrNull()
                    ?: return@let call.respond(HttpStatusCode.BadRequest)
                val track = withContext(Dispatchers.IO) { ensureCatalogTrack(traktorId) }
                if (track != null) {
                    call.respond(track)
                } else {
                    call.respond(HttpStatusCode.NotFound)
                }
            }
        }

        get("/v4/catalog/tracks/{id}/cover/") {
            val traktorId = call.parameters["id"]?.toLongOrNull()
                ?: return@get call.respond(HttpStatusCode.BadRequest)
            val origin = withContext(Dispatchers.IO) {
                ensureCatalogTrack(traktorId)
                getCoverOriginUrl(traktorId)
            } ?: return@get call.respond(HttpStatusCode.NotFound)
            val cover = try {
                withContext(Dispatchers.IO) { getCachedOrDownloadCover(origin) }
            } catch (ex: Exception) {
                println("Cover fetch failed for $traktorId ($origin): ${ex.message}")
                return@get call.respond(HttpStatusCode.BadGateway)
            }
            call.respondBytes(cover.bytes, cover.contentType)
        }

        get("/v4/catalog/tracks/{id}/download/") {
            call.parameters["id"]?.let {
                val traktorId = it.toLongOrNull()
                    ?: return@let call.respond(HttpStatusCode.BadRequest)
                withContext(Dispatchers.IO) { ensureCatalogTrack(traktorId) }
                val trackId = resolveFullTrackId(traktorId)
                    ?: return@let call.respond(HttpStatusCode.NotFound)
                val sourceId = trackIdToSource[trackId]
                    ?: return@let call.respond(HttpStatusCode.NotFound)
                lastDownloadData = withContext(Dispatchers.IO) {
                    sources[sourceId].download(trackId)
                }
                val lengthMs = getStoredTrack(traktorId)?.length_ms
                    ?.coerceIn(0L, Int.MAX_VALUE.toLong())
                    ?.toInt()
                    ?: 1337
                call.respond(Download("https://api.beatport.com/output.mp4", "foo", lengthMs))
            }
        }

        // Serve the last downloaded track
        head("/output.mp4") {
            call.response.header("content-type", "video/mp4")
            call.respondBytes(lastDownloadData)
        }

        get("/output.mp4") {
            call.response.header("content-type", "video/mp4")
            call.respondBytes(lastDownloadData)
        }
    }
}

private fun List<TrackResponse>.toNewSearchApi(): List<BeatportTrack> {
    return this.map {
        BeatportTrack(
            artists = it.artists.map { artist ->
                BeatportArtist(
                    artist.name,
                    "Artist" // Required by traktor
                )
            },
            track_name = it.name,
            track_id = it.id,
            length = it.length_ms,
            release = it.release,
            image = it.image
        )
    }
}

private const val COVER_MAX_REDIRECTS = 5

/**
 * Resolve a redirect [Location] against [currentUrl] and accept it only when the result
 * still passes [isAllowedCoverOriginUrl].
 */
internal fun resolveCoverRedirectUrl(currentUrl: String, location: String): String? {
    val resolved = try {
        URI(currentUrl).resolve(location.trim()).normalize().toString()
    } catch (_: Exception) {
        return null
    }
    return resolved.takeIf { isAllowedCoverOriginUrl(it) }
}

internal fun downloadCover(url: String): CoverDownload {
    var current = url.trim()
    if (!isAllowedCoverOriginUrl(current)) {
        throw IOException("Cover URL not allowed: $current")
    }
    repeat(COVER_MAX_REDIRECTS + 1) { hop ->
        val con = URI.create(current).toURL().openConnection() as HttpURLConnection
        con.connectTimeout = 10_000
        con.readTimeout = 15_000
        con.instanceFollowRedirects = false
        con.setRequestProperty("User-Agent", "traktor-streaming-proxy/cover")
        val code = try {
            con.responseCode
        } catch (ex: Exception) {
            con.disconnect()
            throw ex
        }
        when {
            code in 200..299 -> {
                try {
                    val bytes = con.inputStream.use { it.readBytes() }
                    if (bytes.isEmpty()) {
                        throw IOException("Empty cover body from $current")
                    }
                    return CoverDownload(bytes, contentType = coverContentType(con.contentType))
                } finally {
                    con.disconnect()
                }
            }
            code in 300..399 -> {
                val location = con.getHeaderField("Location")
                con.errorStream?.close()
                con.disconnect()
                if (hop >= COVER_MAX_REDIRECTS) {
                    throw IOException("Too many redirects fetching cover from $url")
                }
                if (location.isNullOrBlank()) {
                    throw IOException("Cover redirect missing Location from $current (HTTP $code)")
                }
                current = resolveCoverRedirectUrl(current, location)
                    ?: throw IOException("Cover redirect not allowed: $location (from $current)")
            }
            else -> {
                con.errorStream?.close()
                con.disconnect()
                throw IOException("Cover download failed HTTP $code from $current")
            }
        }
    }
    throw IOException("Cover download failed from $url")
}

internal fun coverContentType(raw: String?): ContentType {
    val mime = raw?.substringBefore(';')?.trim()?.lowercase().orEmpty()
    return when {
        mime.isEmpty() || mime == "image/jpg" -> ContentType.Image.JPEG
        mime.startsWith("image/") -> try {
            ContentType.parse(mime)
        } catch (_: Exception) {
            ContentType.Image.JPEG
        }
        else -> ContentType.Image.JPEG
    }
}
