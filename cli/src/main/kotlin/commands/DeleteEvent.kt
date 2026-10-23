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
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.add
import lib.EnvLoader
import lib.IntervalsApi
import lib.PathResolver
import java.io.File

/**
 * Deletes an event from intervals.icu by its event ID and removes the matching
 * entry from the athlete's workouts-generated.json log (across all plans).
 *
 * Use when a planned workout no longer makes sense and must be replaced.
 */
class DeleteEvent : Subcommand("delete", "Delete an intervals.icu event by ID and clean local log") {

    private val athleteName by option(
        ArgType.String, shortName = "a", fullName = "athlete",
        description = "Athlete folder name"
    ).required()

    private val eventId by option(
        ArgType.String, fullName = "event-id",
        description = "intervals.icu event ID to delete"
    ).required()

    private val athleteIdOverride by option(
        ArgType.String, fullName = "athlete-id",
        description = "Override intervals.icu athlete ID"
    )

    private val dryRun by option(
        ArgType.Boolean, fullName = "dry-run",
        description = "Print what would happen without sending DELETE"
    ).default(false)

    override fun execute() = runBlocking {
        val athleteId = athleteIdOverride ?: extractAthleteId(PathResolver.athleteProfile(athleteName))
        println("Deleting event $eventId for $athleteName ($athleteId)")
        println("  mode: ${if (dryRun) "DRY-RUN" else "LIVE DELETE"}")

        val api = IntervalsApi(EnvLoader.get("INTERVALS_API_KEY"))
        try {
            if (dryRun) {
                println("  [dry-run] would DELETE /api/v1/athlete/$athleteId/events/$eventId")
            } else {
                api.delete("/api/v1/athlete/$athleteId/events/$eventId")
                println("  ✓ event deleted on intervals.icu")
            }
        } catch (e: Exception) {
            println("  ✗ DELETE failed: ${e.message?.take(300)}")
            return@runBlocking
        } finally {
            api.close()
        }

        // Clean local logs
        val plansDir = File(PathResolver.athleteDir(athleteName), "plans")
        if (!plansDir.exists()) {
            println("  (no plans dir, nothing to clean locally)")
            return@runBlocking
        }
        val json = Json { ignoreUnknownKeys = true; isLenient = true; prettyPrint = true }
        var removed = 0
        plansDir.listFiles { f -> f.isDirectory }?.forEach { planDir ->
            val log = File(planDir, "workouts-generated.json")
            if (!log.exists()) return@forEach
            val arr = (json.parseToJsonElement(log.readText()) as? JsonArray) ?: return@forEach
            val filtered = arr.filterNot {
                val obj = it as? JsonObject ?: return@filterNot false
                obj["intervals_event_id"]?.jsonPrimitive?.contentOrNull == eventId
            }
            if (filtered.size != arr.size) {
                val newArr = buildJsonArray { filtered.forEach { add(it) } }
                if (dryRun) {
                    println("  [dry-run] would remove ${arr.size - filtered.size} entry from ${log.relativeTo(plansDir.parentFile)}")
                } else {
                    log.writeText(json.encodeToString(JsonArray.serializer(), newArr))
                    println("  ✓ removed ${arr.size - filtered.size} entry from ${log.relativeTo(plansDir.parentFile)}")
                }
                removed += arr.size - filtered.size
            }
        }
        if (removed == 0) println("  (no matching entry found in local logs)")
    }

    private fun extractAthleteId(profileFile: File): String {
        val regex = Regex("Athlete ID:\\s*`?([A-Za-z0-9]+)`?")
        for (line in profileFile.readLines()) {
            regex.find(line)?.let { return it.groupValues[1] }
        }
        error("Could not parse 'Athlete ID' from ${profileFile.name}")
    }
}
