package mcp

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import java.time.LocalDate
import java.time.format.DateTimeParseException

/** Small builders for tool input schemas (JSON Schema) and argument parsing. */
object Schema {

    fun obj(vararg properties: Pair<String, JsonObject>, required: List<String> = emptyList()) = buildJsonObject {
        put("type", "object")
        put("properties", JsonObject(properties.toMap()))
        if (required.isNotEmpty()) put("required", JsonArray(required.map(::JsonPrimitive)))
        put("additionalProperties", false)
    }

    fun string(description: String, enum: List<String>? = null) = buildJsonObject {
        put("type", "string")
        put("description", description)
        enum?.let { put("enum", JsonArray(it.map(::JsonPrimitive))) }
    }

    fun integer(description: String) = buildJsonObject {
        put("type", "integer")
        put("description", description)
    }

    fun number(description: String) = buildJsonObject {
        put("type", "number")
        put("description", description)
    }

    fun boolean(description: String) = buildJsonObject {
        put("type", "boolean")
        put("description", description)
    }

    fun array(items: JsonObject, description: String) = buildJsonObject {
        put("type", "array")
        put("description", description)
        put("items", items)
    }

    fun looseObject(description: String, properties: Map<String, JsonObject>, required: List<String>) = buildJsonObject {
        put("type", "object")
        put("description", description)
        put("properties", JsonObject(properties))
        put("required", JsonArray(required.map(::JsonPrimitive)))
    }
}

private val ATHLETE_ID = Regex("^[A-Za-z]?\\d+$")
private val EVENT_ID = Regex("^[A-Za-z0-9_-]{1,40}$")

fun JsonObject.optString(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

fun JsonObject.reqString(key: String): String = optString(key) ?: throw IllegalArgumentException("Missing argument: $key")

fun JsonObject.optBool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull

fun JsonObject.optInt(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull

fun JsonObject.athleteId(key: String = "athlete_id"): String {
    val id = reqString(key)
    require(ATHLETE_ID.matches(id)) { "$key must be an intervals.icu athlete ID like i123456 (got \"$id\")" }
    return id
}

fun JsonObject.entityId(key: String): String {
    val id = reqString(key)
    require(EVENT_ID.matches(id)) { "$key is not a valid intervals.icu ID (got \"$id\")" }
    return id
}

fun JsonObject.date(key: String): LocalDate {
    val raw = reqString(key)
    return try {
        LocalDate.parse(raw.take(10))
    } catch (_: DateTimeParseException) {
        throw IllegalArgumentException("$key must be a date like 2026-10-27 (got \"$raw\")")
    }
}

/** Write tools refuse to run unless Claude states that the coach approved in the conversation. */
fun JsonObject.requireApproval() {
    require(optBool("coach_approved") == true) {
        "Not done: this changes the athlete's intervals.icu calendar. Present the change to the coach, wait for " +
            "their explicit OK, then call again with coach_approved=true."
    }
}

val APPROVAL_PROPERTY = "coach_approved" to Schema.boolean(
    "Set to true ONLY after the coach explicitly approved this exact change in the conversation."
)
