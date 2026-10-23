package commands

import kotlinx.cli.ArgType
import kotlinx.cli.Subcommand
import kotlinx.cli.default
import kotlinx.cli.required
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive
import lib.EnvLoader
import lib.IntervalsApi
import lib.PathResolver
import java.io.File

/**
 * Updates already-created intervals.icu events with the latest content from a workouts JSON file.
 *
 * Reads the source workouts JSON (same format as GenerateWorkouts) and the matching
 * workouts-generated.json log, pairs entries by start_date_local, and PUTs description,
 * name, type, moving_time, icu_training_load, hide_from_athlete to each event.
 *
 * Use this after editing workout content (e.g. fixing structured-steps syntax) to
 * push corrections without recreating events (which would lose the IDs and confuse
 * the athlete if already unhidden).
 */
class UpdateWorkouts : Subcommand("update", "Push edited workout content to existing intervals.icu events") {

    private val athleteName by option(
        ArgType.String, shortName = "a", fullName = "athlete",
        description = "Athlete folder name"
    ).required()

    private val planId by option(
        ArgType.String, shortName = "p", fullName = "plan",
        description = "Plan folder name"
    ).required()

    private val workoutsFile by option(
        ArgType.String, shortName = "f", fullName = "file",
        description = "Workouts JSON file name (relative to plans/<plan>/workouts/)"
    ).required()

    private val athleteIdOverride by option(
        ArgType.String, fullName = "athlete-id",
        description = "Override intervals.icu athlete ID"
    )

    private val dryRun by option(
        ArgType.Boolean, fullName = "dry-run",
        description = "Print what would be PUT without sending"
    ).default(false)

    override fun execute() = runBlocking {
        val athleteId = athleteIdOverride ?: extractAthleteId(PathResolver.athleteProfile(athleteName))
        val planDir = File(PathResolver.athleteDir(athleteName), "plans/$planId")
        require(planDir.exists()) { "Plan dir not found: ${planDir.absolutePath}" }

        val workoutsPath = File(planDir, "workouts/$workoutsFile")
        require(workoutsPath.exists()) { "Workouts file not found: ${workoutsPath.absolutePath}" }

        val logPath = File(planDir, "workouts-generated.json")
        require(logPath.exists()) { "Generated log not found: ${logPath.absolutePath}" }

        val json = Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = false; explicitNulls = false }
        val source: List<WorkoutDef> = json.decodeFromString(workoutsPath.readText())
        val log = json.parseToJsonElement(logPath.readText()) as JsonArray

        // Build map: start_date_local -> intervals_event_id
        val idByDate = log.mapNotNull {
            val obj = it as? JsonObject ?: return@mapNotNull null
            val date = obj["start_date_local"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val id = obj["intervals_event_id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            date to id
        }.toMap()

        println("Updating workouts for $athleteName ($athleteId)")
        println("  source: ${workoutsPath.name} (${source.size} workouts)")
        println("  log:    ${logPath.name} (${idByDate.size} known IDs)")
        println("  mode:   ${if (dryRun) "DRY-RUN" else "LIVE PUT"}")
        println()

        val api = IntervalsApi(EnvLoader.get("INTERVALS_API_KEY"))
        var ok = 0; var fail = 0; var skipped = 0
        try {
            for (w in source) {
                // Strict: only PUT if explicit intervals_event_id is set on the workout def.
                // Date-based fallback is dangerous when the source has new entries (no id) that
                // collide on the date with an existing log entry — would silently overwrite a
                // different event.
                val id = w.intervals_event_id ?: idByDate[w.start_date_local]?.takeIf {
                    // Only allow date-based match when ALL workouts in source have ids,
                    // i.e. there are no "new" workouts that could collide.
                    source.all { it.intervals_event_id != null }
                }
                if (id == null) {
                    println("  - ${w.start_date_local}  ${w.name}")
                    println("    ⚠ no 'intervals_event_id' set on this workout — skipping (use 'generate' for new workouts)")
                    skipped++
                    continue
                }
                val payload = buildPayload(w, api, athleteId, id, dryRun)
                println("  ✎ $id  ${w.start_date_local}  ${w.name}")
                if (dryRun) {
                    println(payload.prependIndent("    "))
                } else {
                    try {
                        api.putJson("/api/v1/athlete/$athleteId/events/$id", payload)
                        println("    ✓ updated")
                        ok++
                    } catch (e: Exception) {
                        println("    ✗ FAILED: ${e.message?.take(300)}")
                        fail++
                    }
                }
            }
        } finally {
            api.close()
        }
        println()
        println("Summary: $ok updated, $fail failed, $skipped skipped")
    }

    private suspend fun buildPayload(
        w: WorkoutDef,
        api: IntervalsApi,
        athleteId: String,
        eventId: String,
        dryRun: Boolean
    ): String {
        // Preserve the current hide_from_athlete on intervals.icu — running 'update'
        // on a previously unhidden workout must NOT silently re-hide it. The source
        // JSON's hide_from_athlete is treated as the initial value at creation time,
        // not as a recurring setter. Use 'unhide' / a future 'hide' command to flip.
        val currentHidden: Boolean? = if (dryRun) {
            null
        } else {
            try {
                val raw = api.getRaw("/api/v1/athlete/$athleteId/events/$eventId")
                val obj = kotlinx.serialization.json.Json.parseToJsonElement(raw) as? JsonObject
                obj?.get("hide_from_athlete")?.jsonPrimitive?.booleanOrNull
            } catch (e: Exception) {
                println("    ⚠ could not GET current visibility: ${e.message?.take(120)} — falling back to source value")
                null
            }
        }
        val hideValue = currentHidden ?: w.hide_from_athlete

        // Use kotlinx.serialization to safely escape strings.
        val obj = kotlinx.serialization.json.buildJsonObject {
            put("name", kotlinx.serialization.json.JsonPrimitive(w.name))
            put("type", kotlinx.serialization.json.JsonPrimitive(w.type))
            put("description", kotlinx.serialization.json.JsonPrimitive(w.description))
            put("start_date_local", kotlinx.serialization.json.JsonPrimitive(w.start_date_local))
            put("moving_time", kotlinx.serialization.json.JsonPrimitive(w.moving_time))
            w.icu_training_load?.let { put("icu_training_load", kotlinx.serialization.json.JsonPrimitive(it)) }
            put("hide_from_athlete", kotlinx.serialization.json.JsonPrimitive(hideValue))
            put("category", kotlinx.serialization.json.JsonPrimitive(w.category))
        }
        return obj.toString()
    }

    private fun extractAthleteId(profileFile: File): String {
        val regex = Regex("Athlete ID:\\s*`?([A-Za-z0-9]+)`?")
        for (line in profileFile.readLines()) {
            regex.find(line)?.let { return it.groupValues[1] }
        }
        error("Could not parse 'Athlete ID' from ${profileFile.name}")
    }
}
