package lib

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.LocalDate
import kotlin.math.roundToInt

/**
 * Human-readable digest of an athlete's intervals.icu data (thresholds, fitness,
 * wellness, volume, upcoming races): the content of `current-state.md`.
 */
object AthleteDigest {

    fun build(
        athleteLabel: String,
        athleteId: String,
        today: LocalDate,
        profileJson: String,
        activitiesJson: String,
        wellnessJson: String,
        eventsJson: String,
        historyWeeks: Int,
        snapshotFiles: Boolean
    ): String {
        val json = Json { ignoreUnknownKeys = true; isLenient = true }
        val profile = json.parseToJsonElement(profileJson).jsonObject
        val activities = json.parseToJsonElement(activitiesJson).jsonArray
        val wellness = json.parseToJsonElement(wellnessJson).jsonArray
        val events = json.parseToJsonElement(eventsJson).jsonArray

        // ---------- Profile / thresholds ----------
        val name = profile["name"]?.jsonPrimitive?.contentOrNull
        val sex = profile["sex"]?.jsonPrimitive?.contentOrNull
        val weight = profile["icu_weight"]?.jsonPrimitive?.doubleOrNull ?: profile["weight"]?.jsonPrimitive?.doubleOrNull
        val ftp = profile["icu_ftp"]?.jsonPrimitive?.intOrNull
        val restingHr = profile["icu_resting_hr"]?.jsonPrimitive?.intOrNull
        val maxHr = profile["icu_max_hr"]?.jsonPrimitive?.intOrNull

        // sportSettings: array of per-sport configs
        val sportSettings = profile["sportSettings"]?.jsonArray
        val (bikeLthr, bikeFtp) = extractSport(sportSettings, listOf("Ride", "VirtualRide"))
            .let { (it["lthr"]?.jsonPrimitive?.intOrNull) to (it["ftp"]?.jsonPrimitive?.intOrNull) }
        val (runLthr, runThresholdPace) = extractSport(sportSettings, listOf("Run"))
            .let { (it["lthr"]?.jsonPrimitive?.intOrNull) to (it["threshold_pace"]?.jsonPrimitive?.doubleOrNull) }
        val swimThresholdPace = extractSport(sportSettings, listOf("Swim"))["threshold_pace"]?.jsonPrimitive?.doubleOrNull

        // ---------- Activity stats (last 4 weeks vs full window) ----------
        val cutoff4w = today.minusDays(28)
        val cutoff4wStr = cutoff4w.toString()

        val byTypeAll = activities.groupBy { sportType(it.jsonObject) }
        val recent = activities.filter { (it.jsonObject["start_date_local"]?.jsonPrimitive?.contentOrNull ?: "") >= cutoff4wStr }
        val byTypeRecent = recent.groupBy { sportType(it.jsonObject) }

        // ---------- Latest wellness ----------
        val latestWellness = wellness
            .map { it.jsonObject }
            .sortedByDescending { it["id"]?.jsonPrimitive?.contentOrNull ?: "" }
            .firstOrNull()
        val ctl = latestWellness?.get("ctl")?.jsonPrimitive?.doubleOrNull
        val atl = latestWellness?.get("atl")?.jsonPrimitive?.doubleOrNull
        val tsb = if (ctl != null && atl != null) ctl - atl else null
        val rampRate = latestWellness?.get("rampRate")?.jsonPrimitive?.doubleOrNull

        // wellness 14-day averages
        val last14 = wellness.map { it.jsonObject }
            .sortedByDescending { it["id"]?.jsonPrimitive?.contentOrNull ?: "" }
            .take(14)
        val avgRhr = last14.mapNotNull { it["restingHR"]?.jsonPrimitive?.intOrNull }.averageOrNull()
        val avgHrv = last14.mapNotNull { it["hrv"]?.jsonPrimitive?.doubleOrNull }.averageOrNullDouble()
        val avgSleepH = last14.mapNotNull { it["sleepSecs"]?.jsonPrimitive?.intOrNull }
            .map { it / 3600.0 }.averageOrNullDouble()

        // ---------- Future events ----------
        val futureEvents = events.map { it.jsonObject }
            .filter { (it["category"]?.jsonPrimitive?.contentOrNull ?: "") == "RACE_A" || (it["category"]?.jsonPrimitive?.contentOrNull ?: "").startsWith("RACE") }

        // ---------- Compose markdown ----------
        return buildString {
            appendLine("# Current State — $athleteLabel")
            appendLine()
            appendLine("_Auto-generated from intervals.icu — last updated ${today}_")
            appendLine()
            appendLine("## intervals.icu identity")
            appendLine("- Athlete ID: `$athleteId`")
            name?.let { appendLine("- Name on intervals.icu: $it") }
            sex?.let { appendLine("- Sex: $it") }
            weight?.let { appendLine("- Weight: $it kg") }
            appendLine()

            appendLine("## Current thresholds")
            appendLine("| Metric | Value |")
            appendLine("|---|---|")
            appendLine("| FTP (global) | ${ftp?.let { "$it W" } ?: "—"} |")
            appendLine("| Bike FTP (sport) | ${bikeFtp?.let { "$it W" } ?: "—"} |")
            appendLine("| Bike LTHR | ${bikeLthr?.let { "$it bpm" } ?: "—"} |")
            appendLine("| Run LTHR | ${runLthr?.let { "$it bpm" } ?: "—"} |")
            appendLine("| Run threshold pace | ${runThresholdPace?.let { paceFromMs(it) } ?: "—"} |")
            appendLine("| Swim threshold pace (CSS) | ${swimThresholdPace?.let { swimPaceFromMs(it) } ?: "—"} |")
            appendLine("| Max HR | ${maxHr?.let { "$it bpm" } ?: "—"} |")
            appendLine("| Resting HR | ${restingHr?.let { "$it bpm" } ?: "—"} |")
            appendLine()

            appendLine("## Fitness (latest)")
            appendLine("| Metric | Value |")
            appendLine("|---|---|")
            appendLine("| CTL (fitness) | ${ctl?.let { String.format("%.1f", it) } ?: "—"} |")
            appendLine("| ATL (fatigue) | ${atl?.let { String.format("%.1f", it) } ?: "—"} |")
            appendLine("| TSB (form) | ${tsb?.let { String.format("%.1f", it) } ?: "—"} |")
            appendLine("| Ramp rate (7d) | ${rampRate?.let { String.format("%.1f", it) } ?: "—"} |")
            appendLine()

            appendLine("## Wellness (last 14 days avg)")
            appendLine("| Metric | Value |")
            appendLine("|---|---|")
            appendLine("| Resting HR | ${avgRhr?.let { "${it.roundToInt()} bpm" } ?: "—"} |")
            appendLine("| HRV | ${avgHrv?.let { String.format("%.1f", it) } ?: "—"} |")
            appendLine("| Sleep | ${avgSleepH?.let { String.format("%.1f h", it) } ?: "—"} |")
            appendLine()

            appendLine("## Volume — last 4 weeks (per discipline)")
            appendLine("| Sport | Sessions | Time | Distance | TSS |")
            appendLine("|---|---|---|---|---|")
            for ((sport, list) in byTypeRecent.entries.sortedByDescending { it.value.size }) {
                appendLine(volumeRow(sport, list))
            }
            appendLine()

            appendLine("## Volume — last $historyWeeks weeks (per discipline)")
            appendLine("| Sport | Sessions | Time | Distance | TSS |")
            appendLine("|---|---|---|---|---|")
            for ((sport, list) in byTypeAll.entries.sortedByDescending { it.value.size }) {
                appendLine(volumeRow(sport, list))
            }
            appendLine()

            appendLine("## Scheduled races/events ahead")
            if (futureEvents.isEmpty()) {
                appendLine("- (none on intervals.icu)")
            } else {
                for (ev in futureEvents) {
                    val date = ev["start_date_local"]?.jsonPrimitive?.contentOrNull?.take(10)
                    val n = ev["name"]?.jsonPrimitive?.contentOrNull
                    val cat = ev["category"]?.jsonPrimitive?.contentOrNull
                    appendLine("- $date — $n ($cat)")
                }
            }
            appendLine()

            if (snapshotFiles) {
                appendLine("## Snapshot files")
                appendLine("- `data-snapshots/profile-$today.json`")
                appendLine("- `data-snapshots/activities-$today.json`  (${activities.size} activities)")
                appendLine("- `data-snapshots/wellness-$today.json`  (${wellness.size} entries)")
                appendLine("- `data-snapshots/events-$today.json`  (${events.size} entries)")
            } else {
                appendLine("## Data window")
                appendLine("- ${activities.size} activities, ${wellness.size} wellness entries, ${events.size} upcoming events")
            }
        }
    }

    private fun extractSport(sportSettings: JsonArray?, types: List<String>): JsonObject {
        if (sportSettings == null) return JsonObject(emptyMap())
        for (entry in sportSettings) {
            val obj = entry.jsonObject
            val ts = obj["types"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: continue
            if (ts.any { it in types }) return obj
        }
        return JsonObject(emptyMap())
    }

    private fun sportType(act: JsonObject): String =
        act["type"]?.jsonPrimitive?.contentOrNull ?: "Unknown"

    private fun volumeRow(sport: String, list: List<JsonElement>): String {
        val totalSec = list.sumOf { it.jsonObject["moving_time"]?.jsonPrimitive?.intOrNull ?: 0 }
        val totalDist = list.sumOf { it.jsonObject["distance"]?.jsonPrimitive?.doubleOrNull ?: 0.0 }
        val totalTss = list.sumOf { it.jsonObject["icu_training_load"]?.jsonPrimitive?.intOrNull ?: 0 }
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val timeStr = "${h}h ${m.toString().padStart(2, '0')}m"
        val distStr = if (totalDist > 0) "${(totalDist / 1000).roundToInt()} km" else "—"
        return "| $sport | ${list.size} | $timeStr | $distStr | $totalTss |"
    }

    private fun paceFromMs(mps: Double): String {
        if (mps <= 0) return "—"
        val secPerKm = 1000.0 / mps
        val m = (secPerKm / 60).toInt()
        val s = (secPerKm % 60).roundToInt()
        return "${m}:${s.toString().padStart(2, '0')}/km"
    }

    private fun swimPaceFromMs(mps: Double): String {
        if (mps <= 0) return "—"
        val secPer100 = 100.0 / mps
        val m = (secPer100 / 60).toInt()
        val s = (secPer100 % 60).roundToInt()
        return "${m}:${s.toString().padStart(2, '0')}/100m"
    }
}

private fun List<Int>.averageOrNull(): Double? = if (isEmpty()) null else average()
private fun List<Double>.averageOrNullDouble(): Double? = if (isEmpty()) null else average()

