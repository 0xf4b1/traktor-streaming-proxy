import beatport.api.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.callloging.*
import kotlinx.serialization.json.*
import org.apache.log4j.BasicConfigurator
import sources.Youtube
import java.io.File

fun main() {
    BasicConfigurator.configure()
    val youtube = Youtube()
    embeddedServer(Netty, port = 8000) {
        install(CallLogging)
        install(ContentNegotiation) {
            json(Json {
                prettyPrint = true
                isLenient = true
            })
        }
        var data = ByteArray(0)
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
                call.respond(Account(System.getenv("BEATPORT_ACCOUNT_ID").toInt()))
            }

            get("/v4/my/license/") {
                call.respondBytes(
                    File("license").inputStream().readBytes()
                )
            }

            get("/v4/catalog/search") {
                call.respond(youtube.query(call.parameters["q"]!!))
            }

            get("/v4/catalog/genres") {
                call.respondRedirect("/v4/catalog/genres/")
            }

            get("/v4/catalog/genres/") {
                call.respond(Genres(listOf(Genre(1, "YouTube"))))
            }

            get("/v4/catalog/genres/{id}/tracks/") {
                call.respond(youtube.getTrending())
            }

            get("/v4/catalog/tracks/{id}/download/") {
                data = youtube.download(call.parameters["id"]!!.toLong())
                call.respond(Download("https://api.beatport.com/output.mp4", "foo", 1337))
            }

            head("/output.mp4") {
                call.response.header("content-type", "video/mp4")
                call.respondBytes(data)
            }

            get("/output.mp4") {
                call.response.header("content-type", "video/mp4")
                call.respondBytes(data)
            }
        }
    }.start(wait = true)
}