package commands

import kotlinx.cli.ArgType
import kotlinx.cli.Subcommand
import kotlinx.cli.default
import kotlinx.cli.required
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import lib.EnvLoader
import lib.IntervalsApi
import lib.PathResolver
import java.io.File

/**
 * Reads workout definitions from a JSON file under
 *   athletes/<athlete>/plans/<plan-id>/workouts/<workouts-file>.json
 * and POSTs each one to intervals.icu as a hidden event on the athlete's calendar.
 *
 * Successfully created workouts are logged in
 *   athletes/<athlete>/plans/<plan-id>/workouts-generated.json
 * so we can later unhide / delete / track them.
 */
class GenerateWorkouts : Subcommand("generate", "POST workouts to intervals.icu (hidden by default)") {

    private val athleteName by option(
        ArgType.String,
        shortName = "a",
        fullName = "athlete",
        description = "Athlete folder name"
    ).required()

    private val planId by option(
        ArgType.String,
        shortName = "p",
        fullName = "plan",
        description = "Plan folder name (e.g. 2027-04-10-city-marathon)"
    ).required()

    private val workoutsFile by option(
        ArgType.String,
        shortName = "f",
        fullName = "file",
        description = "Workouts JSON file name (relative to plans/<plan>/workouts/)"
    ).required()

    private val athleteIdOverride by option(
        ArgType.String,
        fullName = "athlete-id",
        description = "Override intervals.icu athlete ID"
    )

    private val dryRun by option(
        ArgType.Boolean,
        fullName = "dry-run",
        description = "Print payloads without POSTing"
    ).default(false)

    override fun execute() = runBlocking {
        val athleteId = athleteIdOverride ?: extractAthleteId(PathResolver.athleteProfile(athleteName))
        val planDir = File(PathResolver.athleteDir(athleteName), "plans/$planId")
        require(planDir.exists()) { "Plan dir not found: ${planDir.absolutePath}" }

        val workoutsPath = File(planDir, "workouts/$workoutsFile")
        require(workoutsPath.exists()) { "Workouts file not found: ${workoutsPath.absolutePath}" }

        val json = Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = false; explicitNulls = false; prettyPrint = true }
        val workouts: List<WorkoutDef> = json.decodeFromString(workoutsPath.readText())

        println("Loaded ${workouts.size} workouts from ${workoutsPath.name}")
        println("Athlete: $athleteName ($athleteId) — Plan: $planId")
        println("Mode: ${if (dryRun) "DRY-RUN (no POST)" else "LIVE POST"}")
        println()

        val api = IntervalsApi(EnvLoader.get("INTERVALS_API_KEY"))
        val results = mutableListOf<GeneratedWorkout>()
        val unlogged = mutableListOf<GeneratedWorkout>()
        val logFile = File(planDir, "workouts-generated.json")

        try {
            for ((idx, w) in workouts.withIndex()) {
                if (w.intervals_event_id != null) {
                    println("[${idx + 1}/${workouts.size}] ${w.start_date_local} — ${w.name}")
                    println("    ⤳ already has intervals_event_id=${w.intervals_event_id} — skipping (use 'update' for existing events)")
                    continue
                }
                val payload = w.toPayload()
                val payloadJson = json.encodeToString(IntervalsEventPayload.serializer(), payload)
                println("[${idx + 1}/${workouts.size}] ${w.start_date_local} — ${w.name}")

                if (dryRun) {
                    println(payloadJson.prependIndent("    "))
                } else {
                    try {
                        val response = api.postJson("/api/v1/athlete/$athleteId/events", payloadJson)
                        val createdId = parseCreatedId(response)
                        // intervals.icu sometimes ignores hide_from_athlete and moving_time on POST.
                        // Patch immediately via PUT to enforce them.
                        if (createdId != null) {
                            val patchPayload = buildString {
                                append("{")
                                append("\"hide_from_athlete\":${w.hide_from_athlete},")
                                append("\"moving_time\":${w.moving_time}")
                                w.icu_training_load?.let { append(",\"icu_training_load\":$it") }
                                append("}")
                            }
                            api.putJson("/api/v1/athlete/$athleteId/events/$createdId", patchPayload)
                            println("    ✓ created id=$createdId (patched hidden=${w.hide_from_athlete}, moving_time=${w.moving_time})")
                        } else {
                            println("    ✓ created (no id parsed)")
                        }
                        val entry = GeneratedWorkout(
                            local_index = idx,
                            name = w.name,
                            start_date_local = w.start_date_local,
                            category = w.category,
                            type = w.type,
                            intervals_event_id = createdId,
                            hide_from_athlete = w.hide_from_athlete
                        )
                        results.add(entry)
                        // Persist immediately, one event at a time: the event already exists
                        // on intervals.icu, so a later failure must never lose its record.
                        if (!appendToLog(logFile, json, entry)) unlogged.add(entry)
                    } catch (e: Exception) {
                        println("    ✗ FAILED: ${e.message?.take(300)}")
                    }
                }
            }

        } finally {
            api.close()
        }

        if (!dryRun && results.isNotEmpty()) {
            println()
            println("Created ${results.size} workouts — ${results.size - unlogged.size} logged to ${logFile.name}")
            if (unlogged.isNotEmpty()) {
                println()
                println("!! ${unlogged.size} event(s) EXIST on intervals.icu but could not be written to ${logFile.name}.")
                println("!! Add them by hand or 'delete'/'unhide' will not see them:")
                println(
                    json.encodeToString(
                        kotlinx.serialization.builtins.ListSerializer(GeneratedWorkout.serializer()),
                        unlogged
                    ).prependIndent("    ")
                )
            }
        }
    }

    /**
     * Appends one entry to workouts-generated.json, re-reading it each time so the file stays
     * consistent even if the run dies half way. Returns false if it could not be persisted —
     * never throws, because by the time we get here the event is already live on intervals.icu.
     */
    private fun appendToLog(logFile: File, json: Json, entry: GeneratedWorkout): Boolean = try {
        val serializer = kotlinx.serialization.builtins.ListSerializer(GeneratedWorkout.serializer())
        val existing = if (logFile.exists() && logFile.readText().isNotBlank()) {
            json.decodeFromString(serializer, logFile.readText()).toMutableList()
        } else mutableListOf()
        existing.add(entry)
        logFile.writeText(json.encodeToString(serializer, existing))
        true
    } catch (e: Exception) {
        println("    ! could not update ${logFile.name}: ${e.message?.take(200)}")
        false
    }

    private fun parseCreatedId(response: String): String? {
        // intervals.icu returns either an object {id:...} or an array of one event
        return try {
            val element = Json.parseToJsonElement(response)
            val obj = if (element is kotlinx.serialization.json.JsonArray) element.firstOrNull() else element
            obj?.let {
                (it as? kotlinx.serialization.json.JsonObject)
                    ?.get("id")
                    ?.let { v -> (v as? kotlinx.serialization.json.JsonPrimitive)?.content }
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun extractAthleteId(profileFile: File): String {
        val regex = Regex("Athlete ID:\\s*`?([A-Za-z0-9]+)`?")
        for (line in profileFile.readLines()) {
            regex.find(line)?.let { return it.groupValues[1] }
        }
        error("Could not parse 'Athlete ID' from ${profileFile.name}")
    }
}

/**
 * Definition of a workout as loaded from the planner JSON.
 * Maps directly to the intervals.icu event payload but with friendlier defaults.
 */
@Serializable
data class WorkoutDef(
    val start_date_local: String,         // "2026-04-28T19:00:00"
    val name: String,
    val type: String,                     // Ride | Run | Swim | WeightTraining | Other
    val description: String,
    val moving_time: Int,                 // seconds
    val icu_training_load: Int? = null,
    val hide_from_athlete: Boolean = true,
    val category: String = "WORKOUT",
    val workout_doc: String? = null,      // optional structured steps doc
    val intervals_event_id: String? = null  // optional: explicit event ID, used by 'update' for date-changing PUTs
) {
    fun toPayload() = IntervalsEventPayload(
        start_date_local = start_date_local,
        category = category,
        name = name,
        type = type,
        description = description,
        moving_time = moving_time,
        icu_training_load = icu_training_load,
        hide_from_athlete = hide_from_athlete,
        not_on_fitness_chart = false,
        show_as_note = false
    )
}

@Serializable
data class IntervalsEventPayload(
    val start_date_local: String,
    val category: String,
    val name: String,
    val type: String,
    val description: String,
    val moving_time: Int,
    val icu_training_load: Int? = null,
    val hide_from_athlete: Boolean = true,
    val not_on_fitness_chart: Boolean = false,
    val show_as_note: Boolean = false
)

@Serializable
data class GeneratedWorkout(
    // Nullable: entries added by hand (or by other tools) may carry a null index.
    // A non-nullable Int here makes the whole log unreadable and takes 'generate' down
    // AFTER the events have already been created on intervals.icu.
    val local_index: Int? = null,
    val name: String,
    val start_date_local: String,
    val category: String = "WORKOUT",
    val type: String,
    val intervals_event_id: String?,
    val hide_from_athlete: Boolean = false
)
