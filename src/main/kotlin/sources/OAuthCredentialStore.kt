package sources

import Config.prop
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.Properties
import java.util.concurrent.ConcurrentHashMap

/**
 * Reads and refreshes an OAuth token stored outside the application image.
 *
 * The file is a Java properties file containing clientId, clientSecret,
 * refreshToken, accessToken and expiresAt. It must be writable because some
 * providers rotate refresh tokens.
 */
internal class OAuthCredentialStore(
    private val file: File,
    private val tokenEndpoint: String
) {
    @Synchronized
    fun accessToken(): String {
        val credentials = load()
        val accessToken = credentials.getProperty("accessToken", "").trim()
        val expiresAt = credentials.getProperty("expiresAt", "0").toLongOrNull() ?: 0
        if (accessToken.isNotEmpty() && (expiresAt == 0L || expiresAt > now() + EXPIRY_MARGIN_SECONDS)) {
            return accessToken
        }

        return refresh(credentials)
    }

    private fun refresh(credentials: Properties): String {
        val clientId = credentials.required("clientId")
        val clientSecret = credentials.required("clientSecret")
        val refreshToken = credentials.required("refreshToken")
        val body = formEncode(
            mapOf(
                "grant_type" to "refresh_token",
                "client_id" to clientId,
                "client_secret" to clientSecret,
                "refresh_token" to refreshToken
            )
        )
        val connection = WebRequests.createConnection(
            tokenEndpoint,
            "POST",
            mapOf(
                "Accept" to "application/json",
                "Content-Type" to "application/x-www-form-urlencoded"
            )
        )
        WebRequests.post(connection, body.toByteArray(StandardCharsets.UTF_8))
        val json = Json.parseToJsonElement(WebRequests.request(connection).value).jsonObject
        val newAccessToken = json["access_token"]?.jsonPrimitive?.content
            ?: error("OAuth refresh response did not contain an access token")
        val expiresIn = json["expires_in"]?.jsonPrimitive?.content?.toLongOrNull() ?: 3_600

        credentials.setProperty("accessToken", newAccessToken)
        credentials.setProperty("expiresAt", (now() + expiresIn).toString())
        json["refresh_token"]?.jsonPrimitive?.content?.takeIf(String::isNotBlank)?.let {
            credentials.setProperty("refreshToken", it)
        }
        save(credentials)
        return newAccessToken
    }

    private fun load(): Properties {
        check(file.isFile) { "OAuth credential file does not exist: ${file.path}" }
        return Properties().apply { file.inputStream().use(::load) }
    }

    private fun save(credentials: Properties) {
        file.outputStream().use { credentials.store(it, "OAuth credentials - keep private") }
    }

    private fun Properties.required(name: String): String =
        getProperty(name, "").trim().takeIf(String::isNotEmpty)
            ?: error("Missing '$name' in OAuth credential file ${file.path}")

    private fun formEncode(values: Map<String, String>): String = values.entries.joinToString("&") {
        "${encode(it.key)}=${encode(it.value)}"
    }

    private fun encode(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8)

    private fun now(): Long = Instant.now().epochSecond

    companion object {
        private const val EXPIRY_MARGIN_SECONDS = 60L
        private val stores = ConcurrentHashMap<String, OAuthCredentialStore>()

        fun fromConfig(propertyName: String, environmentName: String, tokenEndpoint: String): OAuthCredentialStore? {
            val path = System.getenv(environmentName)?.trim().orEmpty()
                .ifEmpty { prop.getProperty(propertyName, "").trim() }
            return path.takeIf(String::isNotEmpty)?.let {
                val file = File(it).absoluteFile
                stores.computeIfAbsent("${file.path}|$tokenEndpoint") {
                    OAuthCredentialStore(file, tokenEndpoint)
                }
            }
        }
    }
}
