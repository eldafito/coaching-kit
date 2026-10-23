package commands

import kotlinx.cli.ArgParser
import kotlinx.cli.ArgType
import kotlinx.cli.Subcommand
import kotlinx.cli.default
import kotlinx.cli.required
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import lib.EnvLoader
import lib.IntervalsApi
import lib.PathResolver
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

/**
 * Fetches profile, activities (last 26 weeks), wellness (last 90 days),
 * and events (next 6 months) from intervals.icu, and writes:
 *   - athletes/[name]/data-snapshots/ (raw API responses, JSON)
 *   - athletes/[name]/current-state.md (human-readable digest)
 */
class SyncAthleteData : Subcommand("sync", "Sync athlete data from intervals.icu") {

    private val athleteName by option(
        ArgType.String,
        shortName = "a",
        fullName = "athlete",
        description = "Athlete folder name under athletes/"
    ).required()

    private val athleteIdOverride by option(
        ArgType.String,
        fullName = "athlete-id",
        description = "Override intervals.icu athlete ID (otherwise read from profile.md)"
    )

    private val historyDays by option(
        ArgType.Int,
        fullName = "history-days",
        description = "Days of activity history to fetch"
    ).default(26 * 7)

    private val wellnessDays by option(
        ArgType.Int,
        fullName = "wellness-days",
        description = "Days of wellness history to fetch"
    ).default(90)

    private val futureEventDays by option(
        ArgType.Int,
        fullName = "future-event-days",
        description = "Days ahead to fetch scheduled events"
    ).default(180)

    override fun execute() = runBlocking {
        val athleteDir = PathResolver.athleteDir(athleteName)
        val athleteId = athleteIdOverride ?: extractAthleteId(PathResolver.athleteProfile(athleteName))
        require(athleteId.isNotBlank()) { "Athlete ID not found in profile.md and not provided via --athlete-id" }

        val today = LocalDate.now()
        val historyStart = today.minusDays(historyDays.toLong())
        val wellnessStart = today.minusDays(wellnessDays.toLong())
        val eventsEnd = today.plusDays(futureEventDays.toLong())
        val df = DateTimeFormatter.ISO_LOCAL_DATE

        println("Syncing athlete '$athleteName' ($athleteId)")
        println("  history:  ${historyStart.format(df)} → ${today.format(df)}")
        println("  wellness: ${wellnessStart.format(df)} → ${today.format(df)}")
        println("  events:   ${today.format(df)} → ${eventsEnd.format(df)}")

        val api = IntervalsApi(EnvLoader.get("INTERVALS_API_KEY"))
        val snapshotDir = PathResolver.athleteSnapshotDir(athleteName)
        val stamp = today.format(df)

        try {
            val profileJson = api.getAthleteProfileRaw(athleteId)
            File(snapshotDir, "profile-$stamp.json").writeText(profileJson)
            println("  ✓ profile.json (${profileJson.length} bytes)")

            val activitiesJson = api.getActivitiesRaw(
                athleteId,
                historyStart.format(df),
                today.format(df)
            )
            File(snapshotDir, "activities-$stamp.json").writeText(activitiesJson)
            println("  ✓ activities.json (${activitiesJson.length} bytes)")

            val wellnessJson = api.getWellnessRaw(
                athleteId,
                wellnessStart.format(df),
                today.format(df)
            )
            File(snapshotDir, "wellness-$stamp.json").writeText(wellnessJson)
            println("  ✓ wellness.json (${wellnessJson.length} bytes)")

            val eventsJson = api.getEventsRaw(
                athleteId,
                today.format(df),
                eventsEnd.format(df)
            )
            File(snapshotDir, "events-$stamp.json").writeText(eventsJson)
            println("  ✓ events.json (${eventsJson.length} bytes)")

            // Build digest
            val digest = buildDigest(
                athleteName = athleteName,
                athleteId = athleteId,
                today = today,
                profileJson = profileJson,
                activitiesJson = activitiesJson,
                wellnessJson = wellnessJson,
                eventsJson = eventsJson
            )
            PathResolver.athleteCurrentState(athleteName).writeText(digest)
            println("  ✓ current-state.md regenerated")
            println("Done.")
        } finally {
            api.close()
        }
    }

    private fun extractAthleteId(profileFile: File): String {
        require(profileFile.exists()) { "Profile not found: ${profileFile.absolutePath}" }
        val regex = Regex("Athlete ID:\\s*`?([A-Za-z0-9]+)`?")
        for (line in profileFile.readLines()) {
            regex.find(line)?.let { return it.groupValues[1] }
        }
        error("Could not parse 'Athlete ID' from ${profileFile.name}")
    }

    private fun buildDigest(
        athleteName: String,
        athleteId: String,
        today: LocalDate,
        profileJson: String,
        activitiesJson: String,
        wellnessJson: String,
        eventsJson: String
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
            appendLine("# Current State — $athleteName")
            appendLine()
            appendLine("_Auto-generated by `sync` command — last updated ${today}_")
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

            appendLine("## Volume — last ${historyDays / 7} weeks (per discipline)")
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

            appendLine("## Snapshot files")
            appendLine("- `data-snapshots/profile-$today.json`")
            appendLine("- `data-snapshots/activities-$today.json`  (${activities.size} activities)")
            appendLine("- `data-snapshots/wellness-$today.json`  (${wellness.size} entries)")
            appendLine("- `data-snapshots/events-$today.json`  (${events.size} entries)")
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

    private fun volumeRow(sport: String, list: List<kotlinx.serialization.json.JsonElement>): String {
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

