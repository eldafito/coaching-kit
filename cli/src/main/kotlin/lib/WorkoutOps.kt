package lib

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/** Create, update and verify planned workouts on intervals.icu. */
object WorkoutOps {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = false; explicitNulls = false }

    /**
     * POSTs [w] as a new event and returns its ID (null if the response carried none).
     * intervals.icu sometimes ignores hide_from_athlete and moving_time on POST, so they
     * are enforced right after with a PUT.
     */
    suspend fun create(api: IntervalsApi, athleteId: String, w: WorkoutDef): String? {
        val payloadJson = json.encodeToString(IntervalsEventPayload.serializer(), w.toPayload())
        val response = api.postJson("/api/v1/athlete/$athleteId/events", payloadJson)
        val createdId = parseCreatedId(response) ?: return null
        val patch = buildJsonObject {
            put("hide_from_athlete", JsonPrimitive(w.hide_from_athlete))
            put("moving_time", JsonPrimitive(w.moving_time))
            w.icu_training_load?.let { put("icu_training_load", JsonPrimitive(it)) }
        }
        api.putJson("/api/v1/athlete/$athleteId/events/$createdId", patch.toString())
        return createdId
    }

    /**
     * Current hide_from_athlete of an event, or null if it cannot be read.
     * Updates must preserve it: re-running an update on a workout the athlete already
     * sees must never hide it again.
     */
    suspend fun currentHidden(api: IntervalsApi, athleteId: String, eventId: String): Boolean? = try {
        val raw = api.getRaw("/api/v1/athlete/$athleteId/events/$eventId")
        (Json.parseToJsonElement(raw) as? JsonObject)?.get("hide_from_athlete")?.jsonPrimitive?.booleanOrNull
    } catch (_: Exception) {
        null
    }

    fun updatePayload(w: WorkoutDef, hidden: Boolean): String = buildJsonObject {
        put("name", JsonPrimitive(w.name))
        put("type", JsonPrimitive(w.type))
        put("description", JsonPrimitive(w.description))
        put("start_date_local", JsonPrimitive(w.start_date_local))
        put("moving_time", JsonPrimitive(w.moving_time))
        w.icu_training_load?.let { put("icu_training_load", JsonPrimitive(it)) }
        put("hide_from_athlete", JsonPrimitive(hidden))
        put("category", JsonPrimitive(w.category))
    }.toString()

    /** PUTs [w] over an existing event, preserving its current visibility. */
    suspend fun update(api: IntervalsApi, athleteId: String, eventId: String, w: WorkoutDef) {
        val hidden = currentHidden(api, athleteId, eventId) ?: w.hide_from_athlete
        api.putJson("/api/v1/athlete/$athleteId/events/$eventId", updatePayload(w, hidden))
    }

    fun parseCreatedId(response: String): String? = try {
        // intervals.icu returns either an object {id:...} or an array of one event
        val element = Json.parseToJsonElement(response)
        val obj = if (element is JsonArray) element.firstOrNull() else element
        ((obj as? JsonObject)?.get("id") as? JsonPrimitive)?.content
    } catch (_: Exception) {
        null
    }

    /** Result of checking an uploaded event against what was planned. */
    data class Verification(
        val eventId: String,
        val name: String?,
        val type: String?,
        val hidden: Boolean?,
        val movingTime: Int?,
        val stepTexts: List<String>,
        val problems: List<String>
    ) {
        val ok: Boolean get() = problems.isEmpty()
    }

    /**
     * Fetches an event and applies the checks of the intervals-workouts skill:
     * phantom steps (huge durations, empty steps), steps without a target, and a
     * moving_time far from the parsed workout duration.
     */
    suspend fun verify(api: IntervalsApi, athleteId: String, eventId: String, expected: WorkoutDef? = null): Verification {
        val raw = api.getRaw("/api/v1/athlete/$athleteId/events/$eventId")
        val event = Json.parseToJsonElement(raw) as JsonObject
        val problems = mutableListOf<String>()
        val texts = mutableListOf<String>()

        val type = event.str("type")
        val hidden = event["hide_from_athlete"]?.jsonPrimitive?.booleanOrNull
        val movingTime = event["moving_time"]?.jsonPrimitive?.intOrNull
        val doc = event["workout_doc"] as? JsonObject
        val steps = doc?.get("steps") as? JsonArray

        if (expected != null) {
            if (type != expected.type) problems += "type is $type, expected ${expected.type}"
            if (hidden != null && hidden != expected.hide_from_athlete) {
                problems += "hide_from_athlete is $hidden, expected ${expected.hide_from_athlete}"
            }
        }

        if (steps == null || steps.isEmpty()) {
            if (type in setOf("Run", "Ride", "VirtualRide", "Swim")) {
                problems += "no structured steps parsed: the watch will get no targets"
            }
        } else {
            fun walk(list: JsonArray, insideRepeat: Boolean) {
                for (s in list) {
                    val step = s as? JsonObject ?: continue
                    val nested = step["steps"] as? JsonArray
                    val text = step.str("text")?.trim().orEmpty()
                    if (nested != null) {
                        walk(nested, insideRepeat = true)
                        continue
                    }
                    texts += text
                    val duration = step["duration"]?.jsonPrimitive?.doubleOrNull
                    val distance = step["distance"]?.jsonPrimitive?.doubleOrNull
                    if (duration == null && distance == null) {
                        problems += "empty step${if (text.isNotEmpty()) " \"$text\"" else ""}: usually a prose line starting with '- '"
                    }
                    if (!insideRepeat && duration != null && duration > 10_000) {
                        problems += "step \"$text\" lasts ${duration.toInt()} s: a prose duration (e.g. '200m') was probably parsed as a step"
                    }
                    val hasTarget = listOf("hr", "pace", "power", "cadence").any { step[it] != null }
                    val isRest = text.contains("rest", ignoreCase = true) || text.contains("recover", ignoreCase = true)
                    if (!hasTarget && !isRest && (duration != null || distance != null)) {
                        problems += "step \"$text\" has no target (the chart will not render it)"
                    }
                }
            }
            walk(steps, insideRepeat = false)
            val docDuration = doc["duration"]?.jsonPrimitive?.doubleOrNull
            if (movingTime != null && docDuration != null && docDuration > 0) {
                val ratio = movingTime / docDuration
                if (ratio < 0.8 || ratio > 1.25) {
                    problems += "moving_time ${movingTime}s differs from the parsed steps (${docDuration.toInt()}s): align moving_time with the steps"
                }
            }
        }

        return Verification(eventId, event.str("name"), type, hidden, movingTime, texts, problems)
    }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
}
