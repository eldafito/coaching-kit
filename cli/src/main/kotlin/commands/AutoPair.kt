package commands

import kotlinx.cli.ArgType
import kotlinx.cli.Subcommand
import kotlinx.cli.default
import kotlinx.cli.required
import kotlinx.coroutines.runBlocking
import lib.Credentials
import lib.IntervalsApi
import lib.Pairing
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

        val api = IntervalsApi(Credentials.requireApiKey())
        try {
            val results = Pairing.autoPair(api, athleteId, fromDate, toDate, dryRun)
            for (r in results) {
                val head = "${r.date}  ${r.activityId}  ${r.type}  \"${r.activityName}\""
                when (r.outcome) {
                    Pairing.Outcome.ALREADY_PAIRED -> Unit
                    Pairing.Outcome.NO_MATCH -> println("  · $head  → no planned event for this date+type")
                    Pairing.Outcome.PAIRED, Pairing.Outcome.WOULD_PAIR, Pairing.Outcome.FAILED -> {
                        println("  ✎ $head  → event ${r.event?.id}  \"${r.event?.name}\"")
                        when (r.outcome) {
                            Pairing.Outcome.PAIRED -> println("    ✓ paired")
                            Pairing.Outcome.FAILED -> println("    ✗ FAILED: ${r.error}")
                            else -> Unit
                        }
                    }
                    Pairing.Outcome.AMBIGUOUS -> {
                        println("  ? $head  → ${r.candidates.size} candidates, skipping (use 'pair' manually):")
                        r.candidates.forEach { println("       - ${it.id}  \"${it.name}\"") }
                    }
                }
            }
            val count = { o: Pairing.Outcome -> results.count { it.outcome == o } }
            val paired = count(Pairing.Outcome.PAIRED) + count(Pairing.Outcome.WOULD_PAIR)
            println()
            println(
                "Summary: $paired paired${if (dryRun) " (dry-run)" else ""}, ${count(Pairing.Outcome.ALREADY_PAIRED)} already paired, " +
                    "${count(Pairing.Outcome.NO_MATCH)} no match, ${count(Pairing.Outcome.AMBIGUOUS)} ambiguous, ${count(Pairing.Outcome.FAILED)} failed"
            )
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
