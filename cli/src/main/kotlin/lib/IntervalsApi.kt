package lib

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.auth.*
import io.ktor.client.plugins.auth.providers.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.Json

/**
 * Wrapper around the intervals.icu REST API.
 *
 * Authentication: HTTP Basic. Username = "API_KEY", password = the actual API key.
 * Reference: https://intervals.icu/api
 */
class IntervalsApi(
    private val apiKey: String,
    private val baseUrl: String = "https://intervals.icu"
) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = false
        explicitNulls = false
    }

    val client: HttpClient = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(json)
        }
        install(Auth) {
            basic {
                credentials {
                    BasicAuthCredentials(username = "API_KEY", password = apiKey)
                }
                sendWithoutRequest { true }
            }
        }
        install(HttpTimeout) {
            requestTimeoutMillis = 60_000
            connectTimeoutMillis = 15_000
        }
        expectSuccess = false
    }

    suspend inline fun <reified T> getAs(path: String, params: Map<String, String> = emptyMap()): T {
        val response: HttpResponse = client.get(buildUrl(path)) {
            params.forEach { (k, v) -> parameter(k, v) }
            accept(ContentType.Application.Json)
        }
        if (!response.status.isSuccess()) {
            error("GET $path failed: ${response.status}")
        }
        return response.body()
    }

    fun buildUrl(path: String): String = "$baseUrl$path"

    /** Returns raw JSON body (useful for snapshots). */
    suspend fun getRaw(path: String, params: Map<String, String> = emptyMap()): String {
        val response: HttpResponse = client.get("$baseUrl$path") {
            params.forEach { (k, v) -> parameter(k, v) }
            accept(ContentType.Application.Json)
        }
        if (!response.status.isSuccess()) {
            error("GET $path failed: ${response.status} — ${response.bodyAsText().take(500)}")
        }
        return response.bodyAsText()
    }

    suspend fun postJson(path: String, body: String): String {
        val response: HttpResponse = client.post("$baseUrl$path") {
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        if (!response.status.isSuccess()) {
            error("POST $path failed: ${response.status} — ${response.bodyAsText().take(500)}")
        }
        return response.bodyAsText()
    }

    suspend fun putJson(path: String, body: String): String {
        val response: HttpResponse = client.put("$baseUrl$path") {
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        if (!response.status.isSuccess()) {
            error("PUT $path failed: ${response.status} — ${response.bodyAsText().take(500)}")
        }
        return response.bodyAsText()
    }

    suspend fun delete(path: String): String {
        val response: HttpResponse = client.delete("$baseUrl$path")
        if (!response.status.isSuccess()) {
            error("DELETE $path failed: ${response.status} — ${response.bodyAsText().take(500)}")
        }
        return response.bodyAsText()
    }

    fun close() = client.close()

    // ---------- High-level helpers ----------

    suspend fun getAthleteProfileRaw(athleteId: String): String =
        getRaw("/api/v1/athlete/$athleteId")

    suspend fun getActivitiesRaw(athleteId: String, oldest: String, newest: String): String =
        getRaw(
            "/api/v1/athlete/$athleteId/activities",
            mapOf("oldest" to oldest, "newest" to newest)
        )

    suspend fun getWellnessRaw(athleteId: String, oldest: String, newest: String): String =
        getRaw(
            "/api/v1/athlete/$athleteId/wellness",
            mapOf("oldest" to oldest, "newest" to newest)
        )

    suspend fun getEventsRaw(athleteId: String, oldest: String, newest: String): String =
        getRaw(
            "/api/v1/athlete/$athleteId/events",
            mapOf("oldest" to oldest, "newest" to newest)
        )

    /**
     * Pair an executed activity with a planned event.
     *
     * intervals.icu does NOT auto-pair activities to planned events even when
     * type and date match. The link is set on the activity via the
     * `paired_event_id` field. After pairing, the activity inherits the
     * planned event's name.
     *
     * Endpoint: PUT /api/v1/activity/{activityId}
     */
    suspend fun pairActivity(activityId: String, eventId: String): String =
        putJson("/api/v1/activity/$activityId", "{\"paired_event_id\":$eventId}")
}
