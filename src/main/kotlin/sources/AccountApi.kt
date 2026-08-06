package sources

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.io.IOException

internal class AccountApi(
    private val credentials: OAuthCredentialStore,
    private val authorizationScheme: String
) {
    fun getJson(url: String): JsonObject {
        val connection = WebRequests.createConnection(
            url,
            headers = mapOf(
                "Accept" to "application/json",
                "Authorization" to "$authorizationScheme ${credentials.accessToken()}"
            )
        )
        return try {
            Json.parseToJsonElement(WebRequests.request(connection).value).jsonObject
        } catch (exception: WebRequests.HttpException) {
            throw IOException(
                "GET $url failed with HTTP ${exception.code}: ${exception.message}",
                exception
            )
        }
    }
}
