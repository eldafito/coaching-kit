package commands

import kotlinx.cli.ArgType
import kotlinx.cli.Subcommand
import kotlinx.cli.default
import kotlinx.cli.required
import kotlinx.coroutines.runBlocking
import lib.AthleteDigest
import lib.Credentials
import lib.IntervalsApi
import lib.PathResolver
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter

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

        val api = IntervalsApi(Credentials.requireApiKey())
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
            val digest = AthleteDigest.build(
                athleteLabel = athleteName,
                athleteId = athleteId,
                today = today,
                profileJson = profileJson,
                activitiesJson = activitiesJson,
                wellnessJson = wellnessJson,
                eventsJson = eventsJson,
                historyWeeks = historyDays / 7,
                snapshotFiles = true
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
}
