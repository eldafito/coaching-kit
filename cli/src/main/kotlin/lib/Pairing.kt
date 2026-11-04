package lib

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.time.LocalDate

/**
 * Pairs executed activities with planned events. intervals.icu does NOT
 * auto-pair, so planned ↔ actual must be linked explicitly.
 */
object Pairing {

    enum class Outcome { PAIRED, WOULD_PAIR, ALREADY_PAIRED, NO_MATCH, AMBIGUOUS, FAILED }

    data class Planned(val id: String, val date: String, val type: String, val name: String)

    data class Result(
        val date: String,
        val activityId: String,
        val type: String,
        val activityName: String,
        val outcome: Outcome,
        val event: Planned? = null,
        val candidates: List<Planned> = emptyList(),
        val error: String? = null
    )

    /**
     * Pairs each unpaired activity in [from]..[to] with the planned WORKOUT of the
     * same date and type, when there is exactly one. Others are reported, not touched.
     */
    suspend fun autoPair(api: IntervalsApi, athleteId: String, from: LocalDate, to: LocalDate, dryRun: Boolean): List<Result> {
        val json = Json { ignoreUnknownKeys = true; isLenient = true }
        val actsRaw = api.getActivitiesRaw(athleteId, from.toString(), to.toString())
        val evsRaw = api.getEventsRaw(athleteId, from.toString(), to.toString())
        val activities = (json.parseToJsonElement(actsRaw) as? JsonArray) ?: JsonArray(emptyList())
        val events = (json.parseToJsonElement(evsRaw) as? JsonArray) ?: JsonArray(emptyList())

        val plannedByKey = mutableMapOf<Pair<String, String>, MutableList<Planned>>()
        for (e in events) {
            val obj = e as? JsonObject ?: continue
            if (obj.str("category") != "WORKOUT") continue
            val id = obj.str("id") ?: continue
            val date = obj.str("start_date_local")?.take(10) ?: continue
            val type = obj.str("type") ?: continue
            plannedByKey.getOrPut(date to type) { mutableListOf() }.add(Planned(id, date, type, obj.str("name") ?: ""))
        }

        val results = mutableListOf<Result>()
        for (a in activities) {
            val obj = a as? JsonObject ?: continue
            val actId = obj.str("id") ?: continue
            val date = obj.str("start_date_local")?.take(10) ?: continue
            val type = obj.str("type") ?: continue
            val name = obj.str("name") ?: ""
            val pairedId = obj.str("paired_event_id")
            val base = Result(date, actId, type, name, Outcome.NO_MATCH)

            if (pairedId != null && pairedId != "null") {
                results += base.copy(outcome = Outcome.ALREADY_PAIRED)
                continue
            }
            val candidates = plannedByKey[date to type].orEmpty()
            results += when (candidates.size) {
                0 -> base
                1 -> {
                    val ev = candidates.single()
                    if (dryRun) {
                        base.copy(outcome = Outcome.WOULD_PAIR, event = ev)
                    } else {
                        try {
                            api.pairActivity(actId, ev.id)
                            base.copy(outcome = Outcome.PAIRED, event = ev)
                        } catch (e: Exception) {
                            base.copy(outcome = Outcome.FAILED, event = ev, error = e.message?.take(200))
                        }
                    }
                }
                else -> base.copy(outcome = Outcome.AMBIGUOUS, candidates = candidates)
            }
        }
        return results
    }

    private fun JsonObject.str(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull
}
