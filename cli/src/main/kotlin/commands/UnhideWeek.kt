package commands

import kotlinx.cli.ArgType
import kotlinx.cli.Subcommand
import kotlinx.cli.required
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import lib.EnvLoader
import lib.IntervalsApi
import lib.PathResolver
import java.io.File
import java.time.LocalDate

/**
 * Unhides workouts in a date window so the athlete can see them on their calendar.
 *
 * Looks at all workouts-generated.json files under athletes/[name]/plans/ and
 * unhides those whose start_date_local falls in [from, to] (inclusive).
 */
class UnhideWeek : Subcommand("unhide", "Unlock workouts for the athlete (set hide_from_athlete=false) in a date range") {

    private val athleteName by option(
        ArgType.String,
        shortName = "a",
        fullName = "athlete",
        description = "Athlete folder name"
    ).required()

    private val from by option(
        ArgType.String,
        fullName = "from",
        description = "Start date YYYY-MM-DD (default: today)"
    )

    private val to by option(
        ArgType.String,
        fullName = "to",
        description = "End date YYYY-MM-DD (default: from + 6 days)"
    )

    private val athleteIdOverride by option(
        ArgType.String,
        fullName = "athlete-id",
        description = "Override intervals.icu athlete ID"
    )

    override fun execute() = runBlocking {
        val athleteId = athleteIdOverride ?: extractAthleteId(PathResolver.athleteProfile(athleteName))
        val fromDate = from?.let { LocalDate.parse(it) } ?: LocalDate.now()
        val toDate = to?.let { LocalDate.parse(it) } ?: fromDate.plusDays(6)

        println("Unhiding workouts for $athleteName ($athleteId)")
        println("  range: $fromDate → $toDate")

        // Walk plans dirs and read workouts-generated.json from each
        val plansDir = File(PathResolver.athleteDir(athleteName), "plans")
        require(plansDir.exists()) { "No plans dir for $athleteName" }

        val json = Json { ignoreUnknownKeys = true; isLenient = true }
        val toUnhide = mutableListOf<Pair<String, String>>() // id, name

        plansDir.listFiles { f -> f.isDirectory }?.forEach { planDir ->
            val log = File(planDir, "workouts-generated.json")
            if (!log.exists()) return@forEach
            val arr = json.parseToJsonElement(log.readText()) as? JsonArray ?: return@forEach
            for (entry in arr) {
                val obj = entry as? JsonObject ?: continue
                val date = obj["start_date_local"]?.jsonPrimitive?.contentOrNull?.take(10) ?: continue
                val id = obj["intervals_event_id"]?.jsonPrimitive?.contentOrNull ?: continue
                val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: ""
                val d = LocalDate.parse(date)
                if (d in fromDate..toDate) toUnhide.add(id to name)
            }
        }

        if (toUnhide.isEmpty()) {
            println("No workouts found in that range.")
            return@runBlocking
        }
        println("Found ${toUnhide.size} workouts to unhide.")

        val api = IntervalsApi(EnvLoader.get("INTERVALS_API_KEY"))
        try {
            for ((id, name) in toUnhide) {
                try {
                    api.putJson(
                        "/api/v1/athlete/$athleteId/events/$id",
                        "{\"hide_from_athlete\":false}"
                    )
                    println("  ✓ $id  $name")
                } catch (e: Exception) {
                    println("  ✗ $id  $name  — ${e.message?.take(200)}")
                }
            }
        } finally {
            api.close()
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
