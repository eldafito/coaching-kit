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
import lib.EnvLoader
import lib.IntervalsApi
import lib.PathResolver
import java.io.File
import java.time.LocalDate

/**
 * Discover unpaired activities in a date range and pair them with a matching
 * planned event when there is exactly one candidate (same date + same type).
 *
 * intervals.icu does NOT auto-pair, so after the athlete trains the coach
 * should run this to keep planned ↔ actual linked. Activities with 0 or >1
 * candidates are reported but skipped (need manual `pair` call).
 */
class AutoPair : Subcommand("auto-pair", "Discover unpaired activities and pair them with planned events by date+type") {

    private val athleteName by option(
        ArgType.String, shortName = "a", fullName = "athlete",
        description = "Athlete folder name"
    ).required()

    private val from by option(
        ArgType.String, fullName = "from",
        description = "Start date YYYY-MM-DD (default: 7 days ago)"
    )

    private val to by option(
        ArgType.String, fullName = "to",
        description = "End date YYYY-MM-DD (default: today)"
    )

    private val athleteIdOverride by option(
        ArgType.String, fullName = "athlete-id",
        description = "Override intervals.icu athlete ID"
    )

    private val dryRun by option(
        ArgType.Boolean, fullName = "dry-run",
        description = "Print proposed pairings without sending PUTs"
    ).default(false)

    override fun execute() = runBlocking {
        val athleteId = athleteIdOverride ?: extractAthleteId(PathResolver.athleteProfile(athleteName))
        val toDate = to?.let { LocalDate.parse(it) } ?: LocalDate.now()
        val fromDate = from?.let { LocalDate.parse(it) } ?: toDate.minusDays(7)

        println("Auto-pairing activities for $athleteName ($athleteId)")
        println("  range: $fromDate → $toDate")
        println("  mode:  ${if (dryRun) "DRY-RUN" else "LIVE PUT"}")
        println()

        val json = Json { ignoreUnknownKeys = true; isLenient = true }
        val api = IntervalsApi(EnvLoader.get("INTERVALS_API_KEY"))
        try {
            val actsRaw = api.getRaw(
                "/api/v1/athlete/$athleteId/activities",
                mapOf("oldest" to fromDate.toString(), "newest" to toDate.toString())
            )
            val evsRaw = api.getEventsRaw(athleteId, fromDate.toString(), toDate.toString())

            val activities = (json.parseToJsonElement(actsRaw) as? JsonArray) ?: JsonArray(emptyList())
            val events = (json.parseToJsonElement(evsRaw) as? JsonArray) ?: JsonArray(emptyList())

            // Index events by (date, type) for quick lookup. Only WORKOUT category.
            data class Ev(val id: String, val date: String, val type: String, val name: String)
            val plannedByKey = mutableMapOf<Pair<String, String>, MutableList<Ev>>()
            for (e in events) {
                val obj = e as? JsonObject ?: continue
                val cat = obj["category"]?.jsonPrimitive?.contentOrNull
                if (cat != "WORKOUT") continue
                val id = obj["id"]?.jsonPrimitive?.contentOrNull ?: continue
                val date = obj["start_date_local"]?.jsonPrimitive?.contentOrNull?.take(10) ?: continue
                val type = obj["type"]?.jsonPrimitive?.contentOrNull ?: continue
                val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: ""
                plannedByKey.getOrPut(date to type) { mutableListOf() }.add(Ev(id, date, type, name))
            }

            var paired = 0
            var noMatch = 0
            var ambiguous = 0
            var alreadyPaired = 0
            var failed = 0

            for (a in activities) {
                val obj = a as? JsonObject ?: continue
                val actId = obj["id"]?.jsonPrimitive?.contentOrNull ?: continue
                val date = obj["start_date_local"]?.jsonPrimitive?.contentOrNull?.take(10) ?: continue
                val type = obj["type"]?.jsonPrimitive?.contentOrNull ?: continue
                val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: ""
                val pairedId = obj["paired_event_id"]?.jsonPrimitive?.contentOrNull

                if (pairedId != null && pairedId != "null") {
                    alreadyPaired++
                    continue
                }

                val candidates = plannedByKey[date to type].orEmpty()
                when (candidates.size) {
                    0 -> {
                        println("  · $date  $actId  $type  \"$name\"  → no planned event for this date+type")
                        noMatch++
                    }
                    1 -> {
                        val ev = candidates.single()
                        println("  ✎ $date  $actId  $type  \"$name\"  → event ${ev.id}  \"${ev.name}\"")
                        if (!dryRun) {
                            try {
                                api.pairActivity(actId, ev.id)
                                println("    ✓ paired")
                                paired++
                            } catch (e: Exception) {
                                println("    ✗ FAILED: ${e.message?.take(200)}")
                                failed++
                            }
                        } else {
                            paired++
                        }
                    }
                    else -> {
                        println("  ? $date  $actId  $type  \"$name\"  → ${candidates.size} candidates, skipping (use 'pair' manually):")
                        candidates.forEach { println("       - ${it.id}  \"${it.name}\"") }
                        ambiguous++
                    }
                }
            }

            println()
            println("Summary: $paired paired${if (dryRun) " (dry-run)" else ""}, $alreadyPaired already paired, $noMatch no match, $ambiguous ambiguous, $failed failed")
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
