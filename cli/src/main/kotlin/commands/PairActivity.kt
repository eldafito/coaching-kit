package commands

import kotlinx.cli.ArgType
import kotlinx.cli.Subcommand
import kotlinx.cli.default
import kotlinx.cli.required
import kotlinx.coroutines.runBlocking
import lib.EnvLoader
import lib.IntervalsApi

/**
 * Manually pair an executed activity with a planned event on intervals.icu.
 *
 * Use when the auto-pair command can't infer the link (e.g. mismatched type
 * or date) and you want to set it explicitly.
 */
class PairActivity : Subcommand("pair", "Pair an activity with a planned event (paired_event_id)") {

    private val athleteName by option(
        ArgType.String, shortName = "a", fullName = "athlete",
        description = "Athlete folder name (informational, not required by API)"
    ).required()

    private val activityId by option(
        ArgType.String, fullName = "activity-id",
        description = "intervals.icu activity ID (e.g. i100000001)"
    ).required()

    private val eventId by option(
        ArgType.String, fullName = "event-id",
        description = "intervals.icu planned event ID (e.g. 100000001)"
    ).required()

    private val dryRun by option(
        ArgType.Boolean, fullName = "dry-run",
        description = "Print what would be PUT without sending"
    ).default(false)

    override fun execute() = runBlocking {
        println("Pairing activity $activityId → event $eventId for $athleteName")
        println("  mode: ${if (dryRun) "DRY-RUN" else "LIVE PUT"}")

        if (dryRun) {
            println("  [dry-run] would PUT /api/v1/activity/$activityId with {\"paired_event_id\":$eventId}")
            return@runBlocking
        }

        val api = IntervalsApi(EnvLoader.get("INTERVALS_API_KEY"))
        try {
            api.pairActivity(activityId, eventId)
            println("  ✓ paired")
        } catch (e: Exception) {
            println("  ✗ FAILED: ${e.message?.take(300)}")
        } finally {
            api.close()
        }
    }
}
