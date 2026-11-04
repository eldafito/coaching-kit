package mcp

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import lib.AthleteDigest
import lib.BuildInfo
import lib.ConnectPage
import lib.Credentials
import lib.Draft
import lib.Drafts
import lib.IntervalsApi
import lib.IntervalsApiException
import lib.Pairing
import lib.WorkoutDef
import lib.WorkoutLint
import lib.WorkoutOps
import java.time.LocalDate
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.roundToInt

/**
 * The tools Coaching Kit exposes to Claude. They talk to intervals.icu only: the
 * coach's workspace files are read and written by Claude itself, so the server
 * works wherever the folder lives (Claude Code, Cowork's sandbox).
 */
class CoachTools {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true; explicitNulls = false }

    @Volatile
    private var connectPage: ConnectPage? = null

    fun all(): List<Tool> = listOf(
        checkConnection, connectIntervals, listAthletes, athleteOverview,
        getActivities, getActivityIntervals, getWellness, getEvents, verifyEvent,
        proposeWeek, uploadWeek, updateWorkouts, setVisibility, deleteEvent,
        pairActivities, pairActivity, updateSportSettings
    )

    // ---------------------------------------------------------------- connection

    private val checkConnection = Tool(
        name = "check_connection",
        title = "Check intervals.icu connection",
        description = "Checks whether Coaching Kit is connected to intervals.icu and as which account. Also returns " +
            "today's date. Call it first when starting a session, after connect_intervals, or when a call fails " +
            "with an authentication error.",
        inputSchema = Schema.obj(),
        readOnly = true
    ) {
        val today = LocalDate.now()
        val header = "Coaching Kit ${BuildInfo.VERSION}. Today is ${weekday(today)} $today."
        val resolved = Credentials.resolve()
            ?: return@Tool ToolResult("$header\nNot connected to intervals.icu yet: call connect_intervals.")
        try {
            val me = withKey(resolved.key) { api -> api.getRaw("/api/v1/athlete/0") }
            val obj = json.parseToJsonElement(me) as JsonObject
            ToolResult(
                "$header\nConnected to intervals.icu as ${obj.str("name") ?: "?"} (account ${obj.str("id") ?: "?"}). " +
                    "API key read from: ${resolved.source.label}."
            )
        } catch (e: IntervalsApiException) {
            if (e.status == 401 || e.status == 403) {
                ToolResult("$header\nA key is saved (${resolved.source.label}) but intervals.icu rejects it. Call connect_intervals to connect again.")
            } else {
                throw e
            }
        }
    }

    private val connectIntervals = Tool(
        name = "connect_intervals",
        title = "Connect to intervals.icu",
        description = "Opens a page in the coach's web browser where they paste their intervals.icu API key. The key " +
            "is checked and stored securely on this computer; it never goes through the chat. Use it during setup or " +
            "when the key stops working. NEVER ask the coach to paste the key in the conversation.",
        inputSchema = Schema.obj(
            "language" to Schema.string("Language of the page, matching the conversation", listOf("en", "it"))
        ),
        readOnly = false,
        openWorld = false
    ) { args ->
        connectPage?.stop()
        val page = ConnectPage(args.optString("language") ?: "en")
        val url = page.start()
        connectPage = page
        val opened = ConnectPage.openInBrowser(url)
        val how = if (opened) {
            "A page opened in the coach's browser."
        } else {
            "The browser could not be opened automatically: ask the coach to open $url"
        }
        ToolResult(
            "$how It explains where to find the API key on intervals.icu and has a field to paste it. " +
                "The page stays available for ${ConnectPage.TIMEOUT_MINUTES} minutes. When the coach says it is done, " +
                "call check_connection. If they cannot find the page, the link is: $url"
        )
    }

    private val listAthletes = Tool(
        name = "list_athletes",
        title = "List athletes on intervals.icu",
        description = "Lists the athletes the coach follows or coaches on intervals.icu, with their athlete IDs. Use it " +
            "when onboarding an athlete instead of asking the coach for the ID. An athlete who is missing has not " +
            "shared their account with the coach yet.",
        inputSchema = Schema.obj(),
        readOnly = true
    ) {
        withApi { api ->
            val me = (json.parseToJsonElement(api.getRaw("/api/v1/athlete/0")) as JsonObject).str("id")
            val list = json.parseToJsonElement(api.getRaw("/api/v1/athletes")) as? JsonArray ?: JsonArray(emptyList())
            val rows = list.mapNotNull { it as? JsonObject }.map { a ->
                val name = a.str("name") ?: listOfNotNull(a.str("firstname"), a.str("lastname")).joinToString(" ").ifBlank { "?" }
                Triple(name, a.str("id") ?: "?", a.str("id") == me)
            }.sortedWith(compareBy({ it.third }, { it.first.lowercase() }))
            val others = rows.count { !it.third }
            ToolResult(buildString {
                appendLine("$others athlete(s) besides the coach:")
                appendLine("| Name | Athlete ID | |")
                appendLine("|---|---|---|")
                rows.forEach { (name, id, self) -> appendLine("| $name | $id | ${if (self) "the coach's own account" else ""} |") }
            })
        }
    }

    // ---------------------------------------------------------------- reads

    private val athleteOverview = Tool(
        name = "athlete_overview",
        title = "Athlete overview",
        description = "Summary of an athlete's intervals.icu data: thresholds and zones, fitness (CTL/ATL/TSB), " +
            "14-day wellness, volume per sport (last 4 weeks and longer), races ahead, sport settings IDs. Save the " +
            "result as athletes/<name>/current-state.md (replacing the old one).",
        inputSchema = Schema.obj(
            "athlete_id" to Schema.string("intervals.icu athlete ID, e.g. i123456"),
            "athlete_label" to Schema.string("The athlete's folder name in the workspace, used as title"),
            "history_weeks" to Schema.integer("Weeks of activity history (default 26)"),
            required = listOf("athlete_id")
        ),
        readOnly = true
    ) { args ->
        val id = args.athleteId()
        val weeks = (args.optInt("history_weeks") ?: 26).coerceIn(1, 104)
        val today = LocalDate.now()
        withApi { api ->
            val profile = api.getAthleteProfileRaw(id)
            val activities = api.getActivitiesRaw(id, today.minusWeeks(weeks.toLong()).toString(), today.toString())
            val wellness = api.getWellnessRaw(id, today.minusDays(90).toString(), today.toString())
            val events = api.getEventsRaw(id, today.toString(), today.plusDays(180).toString())
            val digest = AthleteDigest.build(
                athleteLabel = args.optString("athlete_label") ?: id,
                athleteId = id,
                today = today,
                profileJson = profile,
                activitiesJson = activities,
                wellnessJson = wellness,
                eventsJson = events,
                historyWeeks = weeks,
                snapshotFiles = false
            )
            val settings = ((json.parseToJsonElement(profile) as JsonObject)["sportSettings"] as? JsonArray).orEmpty()
            ToolResult(buildString {
                append(digest.trimEnd())
                appendLine()
                appendLine()
                appendLine("## Sport settings IDs (for update_sport_settings)")
                settings.mapNotNull { it as? JsonObject }.forEach { s ->
                    val types = (s["types"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }?.joinToString("/")
                    appendLine("- ${s.str("id")}: $types")
                }
            })
        }
    }

    private val getActivities = Tool(
        name = "get_activities",
        title = "Get activities",
        description = "Executed activities in a date range (max 200 days), compact: date, sport, duration, distance, " +
            "HR, pace/power, load, RPE/feel, the athlete's notes, paired planned event. Activities imported from " +
            "Strava carry no data (intervals.icu limitation): ask the athlete to sync from the device instead.",
        inputSchema = rangeSchema(),
        readOnly = true
    ) { args ->
        val id = args.athleteId()
        val (oldest, newest) = range(args)
        withApi { api ->
            val list = json.parseToJsonElement(api.getActivitiesRaw(id, oldest.toString(), newest.toString())) as? JsonArray
            ToolResult(compactList(list.orEmpty()) { a ->
                pick(a, ACTIVITY_FIELDS, mapOf("description" to 400)).withPace(a, a.str("type"))
            })
        }
    }

    private val getActivityIntervals = Tool(
        name = "get_activity_intervals",
        title = "Get activity intervals",
        description = "The intervals (work/recovery blocks) of one executed activity, with HR, pace/power and " +
            "cadence: use it to check how the prescribed blocks were executed.",
        inputSchema = Schema.obj(
            "activity_id" to Schema.string("Activity ID from get_activities"),
            "sport" to Schema.string("The activity's sport, used to format pace", listOf("Run", "Ride", "Swim", "Other")),
            required = listOf("activity_id")
        ),
        readOnly = true
    ) { args ->
        val activityId = args.entityId("activity_id")
        withApi { api ->
            val obj = json.parseToJsonElement(api.getRaw("/api/v1/activity/$activityId/intervals")) as? JsonObject
            val intervals = obj?.get("icu_intervals") as? JsonArray
            ToolResult(compactList(intervals.orEmpty()) { i ->
                pick(i, INTERVAL_FIELDS).withPace(i, args.optString("sport") ?: "Run")
            })
        }
    }

    private val getWellness = Tool(
        name = "get_wellness",
        title = "Get wellness",
        description = "Daily wellness in a date range (max 200 days): CTL/ATL, resting HR, HRV, sleep, readiness, " +
            "soreness, fatigue, stress, mood, injury, weight, the athlete's comments.",
        inputSchema = rangeSchema(),
        readOnly = true
    ) { args ->
        val id = args.athleteId()
        val (oldest, newest) = range(args)
        withApi { api ->
            val list = json.parseToJsonElement(api.getWellnessRaw(id, oldest.toString(), newest.toString())) as? JsonArray
            ToolResult(compactList(list.orEmpty()) { w ->
                val picked = pick(w, WELLNESS_FIELDS, mapOf("comments" to 400))
                val sleep = w.num("sleepSecs")?.let { JsonPrimitive(((it / 360.0).roundToInt()) / 10.0) }
                JsonObject(picked.filterKeys { it != "sleepSecs" } + listOfNotNull(sleep?.let { "sleep_hours" to it }))
            })
        }
    }

    private val getEvents = Tool(
        name = "get_events",
        title = "Get planned events",
        description = "Planned workouts, notes and races on the athlete's calendar in a date range (max 200 days), " +
            "with event IDs and visibility. Set include_descriptions to read the full workout text.",
        inputSchema = Schema.obj(
            "athlete_id" to Schema.string("intervals.icu athlete ID, e.g. i123456"),
            "oldest" to Schema.string("First day, YYYY-MM-DD"),
            "newest" to Schema.string("Last day, YYYY-MM-DD"),
            "include_descriptions" to Schema.boolean("Include each workout's full description (default false)"),
            required = listOf("athlete_id", "oldest", "newest")
        ),
        readOnly = true
    ) { args ->
        val id = args.athleteId()
        val (oldest, newest) = range(args)
        val fields = if (args.optBool("include_descriptions") == true) EVENT_FIELDS + "description" else EVENT_FIELDS
        withApi { api ->
            val list = json.parseToJsonElement(api.getEventsRaw(id, oldest.toString(), newest.toString())) as? JsonArray
            ToolResult(compactList(list.orEmpty()) { e -> pick(e, fields) })
        }
    }

    private val verifyEvent = Tool(
        name = "verify_event",
        title = "Verify a planned workout",
        description = "Reads one planned workout back from intervals.icu and checks how the description was parsed: " +
            "phantom steps from prose, steps without a target, moving_time far from the steps, visibility.",
        inputSchema = Schema.obj(
            "athlete_id" to Schema.string("intervals.icu athlete ID, e.g. i123456"),
            "event_id" to Schema.string("Event ID"),
            required = listOf("athlete_id", "event_id")
        ),
        readOnly = true
    ) { args ->
        val id = args.athleteId()
        val eventId = args.entityId("event_id")
        withApi { api -> ToolResult(describe(WorkoutOps.verify(api, id, eventId))) }
    }

    // ---------------------------------------------------------------- writes

    private val proposeWeek = Tool(
        name = "propose_week",
        title = "Propose a week (no upload)",
        description = "Checks a week of NEW workouts against the intervals.icu parser rules and saves it as a draft. " +
            "Nothing is uploaded. Call it before presenting the week to the coach: fix any blocking problem it " +
            "reports, then show the coach the week. After the coach's explicit OK, upload with upload_week. If the " +
            "coach asks for changes, call propose_week again (it creates a new draft).",
        inputSchema = Schema.obj(
            "athlete_id" to Schema.string("intervals.icu athlete ID, e.g. i123456"),
            "athlete_label" to Schema.string("The athlete's folder name in the workspace"),
            "week_label" to Schema.string("e.g. \"week-12 (2026-11-09 to 2026-11-15)\""),
            "workouts" to Schema.array(WORKOUT_SCHEMA, "The workouts, in the week-N.json format"),
            required = listOf("athlete_id", "athlete_label", "week_label", "workouts")
        ),
        readOnly = false,
        openWorld = false
    ) { args ->
        val id = args.athleteId()
        val workouts = decodeWorkouts(args["workouts"])
        require(workouts.isNotEmpty()) { "workouts is empty" }
        require(workouts.none { it.intervals_event_id != null }) {
            "Some workouts already have an intervals_event_id: they exist on intervals.icu. Use update_workouts for those."
        }
        val issues = WorkoutLint.check(workouts)
        val blocking = issues.filter { it.blocking }
        if (blocking.isNotEmpty()) {
            return@Tool ToolResult(
                "Draft NOT saved: fix these problems first (they would break the workouts on the athlete's watch):\n" +
                    blocking.joinToString("\n") { "- ${it.workout}: ${it.message}" } +
                    warningsText(issues),
                isError = true
            )
        }
        val draft = Drafts.create(id, args.reqString("athlete_label"), args.reqString("week_label"), workouts)
        ToolResult(buildString {
            appendLine("Draft ${draft.id} saved (nothing uploaded) — ${draft.athlete_label}, ${draft.week_label}.")
            appendLine()
            append(weekTable(draft))
            append(warningsText(issues))
            appendLine()
            appendLine("Next: present this week to the coach and STOP. Upload only after their explicit OK, with " +
                "upload_week(draft_id=\"${draft.id}\", coach_approved=true).")
        })
    }

    private val uploadWeek = Tool(
        name = "upload_week",
        title = "Upload an approved week",
        description = "Uploads a draft saved by propose_week to the athlete's intervals.icu calendar, then verifies " +
            "every created workout. Only after the coach explicitly approved that draft in the conversation. A " +
            "draft is never uploaded twice; if some workouts failed, calling it again uploads only those.",
        inputSchema = Schema.obj(
            "draft_id" to Schema.string("ID returned by propose_week"),
            APPROVAL_PROPERTY,
            required = listOf("draft_id", "coach_approved")
        ),
        readOnly = false
    ) { args ->
        args.requireApproval()
        val draftId = args.reqString("draft_id")
        var draft: Draft = Drafts.load(draftId) ?: throw IllegalArgumentException("Draft $draftId not found: call propose_week first.")
        if (draft.fullyUploaded) {
            return@Tool ToolResult("Draft ${draft.id} was already uploaded:\n" + idsText(draft))
        }
        withApi { api ->
            val failures = mutableListOf<String>()
            for ((index, w) in draft.workouts.withIndex()) {
                if (index in draft.event_ids) continue
                try {
                    val eventId = WorkoutOps.create(api, draft.athlete_id, w) ?: "unknown"
                    draft = draft.copy(event_ids = draft.event_ids + (index to eventId))
                    Drafts.save(draft)
                } catch (e: Exception) {
                    failures += "- ${w.start_date_local.take(10)} ${w.name}: ${Errors.explain(e)}"
                }
            }
            val checks = draft.event_ids.entries.sortedBy { it.key }.map { (index, eventId) ->
                val w = draft.workouts[index]
                val line = if (eventId == "unknown") {
                    "created, but intervals.icu returned no ID: check the calendar"
                } else {
                    try {
                        val v = WorkoutOps.verify(api, draft.athlete_id, eventId, w)
                        if (v.ok) "✓ ${v.stepTexts.size} steps" else "⚠ " + v.problems.joinToString("; ")
                    } catch (e: Exception) {
                        "could not verify: ${Errors.explain(e)}"
                    }
                }
                "| ${w.start_date_local.take(10)} | ${w.name} | $eventId | ${if (w.hide_from_athlete) "hidden" else "visible"} | $line |"
            }
            val hidden = draft.event_ids.filter { draft.workouts[it.key].hide_from_athlete }.values.filter { it != "unknown" }
            ToolResult(
                buildString {
                    appendLine("Uploaded ${draft.event_ids.size}/${draft.workouts.size} workouts of ${draft.week_label} for ${draft.athlete_label}.")
                    appendLine()
                    appendLine("| Date | Workout | Event ID | Athlete sees it | Check |")
                    appendLine("|---|---|---|---|---|")
                    checks.forEach { appendLine(it) }
                    if (failures.isNotEmpty()) {
                        appendLine()
                        appendLine("FAILED (call upload_week again with the same draft to retry only these):")
                        failures.forEach { appendLine(it) }
                    }
                    appendLine()
                    appendLine("Write each event ID into week-N.json as \"intervals_event_id\":")
                    append(idsText(draft))
                    if (checks.any { "⚠" in it }) {
                        appendLine()
                        appendLine("Fix the ⚠ workouts with update_workouts (spell out prose numbers, align moving_time), then verify_event again.")
                    }
                    if (hidden.isNotEmpty()) {
                        appendLine()
                        appendLine("${hidden.size} workout(s) are hidden from the athlete. When the coach has checked them " +
                            "on intervals.icu and says so, make them visible with set_visibility.")
                    }
                },
                isError = failures.isNotEmpty() && draft.event_ids.isEmpty()
            )
        }
    }

    private val updateWorkouts = Tool(
        name = "update_workouts",
        title = "Update existing workouts",
        description = "Pushes edited content (name, description, date, duration) to workouts that already exist on " +
            "intervals.icu, identified by intervals_event_id, then verifies them. Visibility is left as it is. " +
            "Only after the coach's explicit OK for these changes.",
        inputSchema = Schema.obj(
            "athlete_id" to Schema.string("intervals.icu athlete ID, e.g. i123456"),
            "workouts" to Schema.array(WORKOUT_SCHEMA, "The edited workouts, each with its intervals_event_id"),
            APPROVAL_PROPERTY,
            required = listOf("athlete_id", "workouts", "coach_approved")
        ),
        readOnly = false
    ) { args ->
        args.requireApproval()
        val id = args.athleteId()
        val workouts = decodeWorkouts(args["workouts"])
        require(workouts.all { it.intervals_event_id != null }) {
            "Every workout needs its intervals_event_id. New workouts go through propose_week / upload_week."
        }
        val issues = WorkoutLint.check(workouts)
        val blocking = issues.filter { it.blocking }
        if (blocking.isNotEmpty()) {
            return@Tool ToolResult(
                "Nothing updated: fix these problems first:\n" + blocking.joinToString("\n") { "- ${it.workout}: ${it.message}" },
                isError = true
            )
        }
        withApi { api ->
            val lines = workouts.map { w ->
                val eventId = w.intervals_event_id!!
                try {
                    WorkoutOps.update(api, id, eventId, w)
                    val v = WorkoutOps.verify(api, id, eventId)
                    "| ${w.start_date_local.take(10)} | ${w.name} | $eventId | " +
                        (if (v.ok) "✓ updated, ${v.stepTexts.size} steps" else "⚠ updated: " + v.problems.joinToString("; ")) + " |"
                } catch (e: Exception) {
                    "| ${w.start_date_local.take(10)} | ${w.name} | $eventId | ✗ ${Errors.explain(e)} |"
                }
            }
            ToolResult("| Date | Workout | Event ID | Result |\n|---|---|---|---|\n" + lines.joinToString("\n") + warningsText(issues))
        }
    }

    private val setVisibility = Tool(
        name = "set_visibility",
        title = "Show or hide workouts",
        description = "Makes planned workouts visible to the athlete (or hides them again). Only after the coach's " +
            "explicit OK, typically once they have checked the hidden week on intervals.icu.",
        inputSchema = Schema.obj(
            "athlete_id" to Schema.string("intervals.icu athlete ID, e.g. i123456"),
            "event_ids" to Schema.array(Schema.string("Event ID"), "Workouts to change"),
            "visible" to Schema.boolean("true = the athlete sees them, false = hidden"),
            APPROVAL_PROPERTY,
            required = listOf("athlete_id", "event_ids", "visible", "coach_approved")
        ),
        readOnly = false
    ) { args ->
        args.requireApproval()
        val id = args.athleteId()
        val visible = args.optBool("visible") ?: throw IllegalArgumentException("Missing argument: visible")
        val ids = (args["event_ids"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
        require(ids.isNotEmpty()) { "event_ids is empty" }
        ids.forEach { require(Regex("^[A-Za-z0-9_-]{1,40}$").matches(it)) { "Invalid event ID: $it" } }
        withApi { api ->
            val lines = ids.map { eventId ->
                try {
                    api.putJson("/api/v1/athlete/$id/events/$eventId", "{\"hide_from_athlete\":${!visible}}")
                    "- $eventId: ${if (visible) "visible to the athlete" else "hidden"}"
                } catch (e: Exception) {
                    "- $eventId: ✗ ${Errors.explain(e)}"
                }
            }
            ToolResult(lines.joinToString("\n"))
        }
    }

    private val deleteEvent = Tool(
        name = "delete_event",
        title = "Delete a planned workout",
        description = "Deletes one planned workout or note from the athlete's calendar. Cannot be undone. Only after " +
            "the coach's explicit OK to delete this specific workout.",
        inputSchema = Schema.obj(
            "athlete_id" to Schema.string("intervals.icu athlete ID, e.g. i123456"),
            "event_id" to Schema.string("Event ID"),
            APPROVAL_PROPERTY,
            required = listOf("athlete_id", "event_id", "coach_approved")
        ),
        readOnly = false,
        destructive = true
    ) { args ->
        args.requireApproval()
        val id = args.athleteId()
        val eventId = args.entityId("event_id")
        withApi { api ->
            val event = json.parseToJsonElement(api.getRaw("/api/v1/athlete/$id/events/$eventId")) as JsonObject
            api.delete("/api/v1/athlete/$id/events/$eventId")
            ToolResult("Deleted ${event.str("start_date_local")?.take(10)} \"${event.str("name")}\" ($eventId). " +
                "Remove its intervals_event_id from the week file and note it in the decisions log.")
        }
    }

    private val pairActivities = Tool(
        name = "pair_activities",
        title = "Pair activities with planned workouts",
        description = "intervals.icu does not link executed activities to planned workouts by itself. This pairs each " +
            "unpaired activity in the range with the planned workout of the same day and sport, when there is " +
            "exactly one; ambiguous cases are listed for pair_activity.",
        inputSchema = Schema.obj(
            "athlete_id" to Schema.string("intervals.icu athlete ID, e.g. i123456"),
            "oldest" to Schema.string("First day, YYYY-MM-DD"),
            "newest" to Schema.string("Last day, YYYY-MM-DD"),
            "dry_run" to Schema.boolean("Only show what would be paired (default false)"),
            required = listOf("athlete_id", "oldest", "newest")
        ),
        readOnly = false
    ) { args ->
        val id = args.athleteId()
        val (oldest, newest) = range(args)
        val dryRun = args.optBool("dry_run") == true
        withApi { api ->
            val results = Pairing.autoPair(api, id, oldest, newest, dryRun)
            ToolResult(buildString {
                for (r in results) {
                    val head = "${r.date} ${r.type} \"${r.activityName}\" (${r.activityId})"
                    when (r.outcome) {
                        Pairing.Outcome.PAIRED -> appendLine("- $head → paired with \"${r.event?.name}\" (${r.event?.id})")
                        Pairing.Outcome.WOULD_PAIR -> appendLine("- $head → would pair with \"${r.event?.name}\" (${r.event?.id})")
                        Pairing.Outcome.FAILED -> appendLine("- $head → ✗ ${r.error}")
                        Pairing.Outcome.AMBIGUOUS -> appendLine("- $head → ${r.candidates.size} candidates: " +
                            r.candidates.joinToString { "\"${it.name}\" (${it.id})" } + " — use pair_activity")
                        Pairing.Outcome.NO_MATCH -> appendLine("- $head → no planned workout that day for this sport")
                        Pairing.Outcome.ALREADY_PAIRED -> Unit
                    }
                }
                val already = results.count { it.outcome == Pairing.Outcome.ALREADY_PAIRED }
                if (already > 0) appendLine("$already activities were already paired.")
                if (results.isEmpty()) appendLine("No activities in this range.")
            })
        }
    }

    private val pairActivity = Tool(
        name = "pair_activity",
        title = "Pair one activity",
        description = "Links one executed activity to one planned workout (the activity takes the workout's name).",
        inputSchema = Schema.obj(
            "activity_id" to Schema.string("Activity ID"),
            "event_id" to Schema.string("Planned workout event ID"),
            required = listOf("activity_id", "event_id")
        ),
        readOnly = false
    ) { args ->
        val activityId = args.entityId("activity_id")
        val eventId = args.entityId("event_id")
        withApi { api ->
            api.pairActivity(activityId, eventId)
            ToolResult("Activity $activityId paired with planned workout $eventId.")
        }
    }

    private val updateSportSettings = Tool(
        name = "update_sport_settings",
        title = "Update thresholds",
        description = "Updates an athlete's thresholds for one sport (e.g. after a test): FTP, LTHR, max HR, threshold " +
            "pace, W'. Zones follow automatically. Only after the coach's explicit OK. Sport settings IDs are listed " +
            "by athlete_overview.",
        inputSchema = Schema.obj(
            "athlete_id" to Schema.string("intervals.icu athlete ID, e.g. i123456"),
            "sport_settings_id" to Schema.string("Sport settings ID from athlete_overview"),
            "changes" to Schema.looseObject(
                "Only the values to change",
                mapOf(
                    "ftp" to Schema.integer("FTP in watts"),
                    "indoor_ftp" to Schema.integer("Indoor FTP in watts"),
                    "lthr" to Schema.integer("Lactate threshold HR in bpm"),
                    "max_hr" to Schema.integer("Max HR in bpm"),
                    "threshold_pace" to Schema.number("Threshold pace in metres per second (e.g. 4:30/km = 3.704)"),
                    "w_prime" to Schema.integer("W' in joules")
                ),
                required = emptyList()
            ),
            APPROVAL_PROPERTY,
            required = listOf("athlete_id", "sport_settings_id", "changes", "coach_approved")
        ),
        readOnly = false
    ) { args ->
        args.requireApproval()
        val id = args.athleteId()
        val settingsId = args.entityId("sport_settings_id")
        val changes = args["changes"] as? JsonObject ?: throw IllegalArgumentException("Missing argument: changes")
        val allowed = setOf("ftp", "indoor_ftp", "lthr", "max_hr", "threshold_pace", "w_prime")
        val unknown = changes.keys - allowed
        require(unknown.isEmpty()) { "Only ${allowed.joinToString()} can be changed (got ${unknown.joinToString()})" }
        require(changes.isNotEmpty()) { "changes is empty" }
        changes.forEach { (k, v) -> require((v as? JsonPrimitive)?.doubleOrNull != null) { "$k must be a number" } }
        withApi { api ->
            val response = json.parseToJsonElement(api.putJson("/api/v1/athlete/$id/sport-settings/$settingsId", JsonObject(changes).toString())) as? JsonObject
            ToolResult("Updated sport settings $settingsId: " + changes.keys.joinToString { "$it = ${response?.get(it) ?: changes[it]}" } +
                ". Record the change in tests-history.md and the decisions log.")
        }
    }

    // ---------------------------------------------------------------- helpers

    private suspend fun <T> withApi(block: suspend (IntervalsApi) -> T): T =
        withKey(Credentials.apiKeyOrNull() ?: throw NotConnectedException(), block)

    private suspend fun <T> withKey(key: String, block: suspend (IntervalsApi) -> T): T {
        val api = IntervalsApi(key)
        try {
            return block(api)
        } finally {
            api.close()
        }
    }

    private fun decodeWorkouts(element: JsonElement?): List<WorkoutDef> {
        require(element is JsonArray) { "workouts must be an array of workouts" }
        return try {
            json.decodeFromJsonElement(ListSerializer(WorkoutDef.serializer()), element)
        } catch (e: Exception) {
            throw IllegalArgumentException("workouts are not in the week-N.json format: ${e.message?.take(300)}")
        }
    }

    private fun range(args: JsonObject): Pair<LocalDate, LocalDate> {
        val oldest = args.date("oldest")
        val newest = args.date("newest")
        require(!newest.isBefore(oldest)) { "newest must not be before oldest" }
        require(ChronoUnit.DAYS.between(oldest, newest) <= 200) { "The range is limited to 200 days" }
        return oldest to newest
    }

    private fun rangeSchema() = Schema.obj(
        "athlete_id" to Schema.string("intervals.icu athlete ID, e.g. i123456"),
        "oldest" to Schema.string("First day, YYYY-MM-DD"),
        "newest" to Schema.string("Last day, YYYY-MM-DD"),
        required = listOf("athlete_id", "oldest", "newest")
    )

    private fun compactList(list: JsonArray, map: (JsonObject) -> JsonObject): String {
        val items = list.mapNotNull { it as? JsonObject }.map(map)
        if (items.isEmpty()) return "[] (nothing in this range)"
        return items.joinToString(",\n", prefix = "[\n", postfix = "\n]") { it.toString() }
    }

    private fun pick(obj: JsonObject, fields: List<String>, truncate: Map<String, Int> = emptyMap()): JsonObject {
        val out = LinkedHashMap<String, JsonElement>()
        for (f in fields) {
            val v = obj[f] ?: continue
            if (v is JsonNull) continue
            if (v is JsonPrimitive && v.isString && v.content.isEmpty()) continue
            val max = truncate[f]
            out[f] = if (max != null && v is JsonPrimitive && v.isString && v.content.length > max) {
                JsonPrimitive(v.content.take(max) + "…")
            } else {
                v
            }
        }
        return JsonObject(out)
    }

    private fun JsonObject.withPace(source: JsonObject, sport: String?): JsonObject {
        val speed = source.num("average_speed") ?: return this
        if (speed <= 0) return this
        val pace = when (sport) {
            "Run", "VirtualRun", "TrailRun", "Walk", "Hike" -> mmss(1000 / speed) + "/km"
            "Swim", "OpenWaterSwim" -> mmss(100 / speed) + "/100m"
            else -> return this
        }
        return JsonObject(this + ("pace" to JsonPrimitive(pace)))
    }

    private fun mmss(seconds: Double): String {
        val total = seconds.roundToInt()
        return "${total / 60}:${(total % 60).toString().padStart(2, '0')}"
    }

    private fun weekTable(draft: Draft): String = buildString {
        appendLine("| Day | Date | Sport | Workout | Duration | Athlete sees it |")
        appendLine("|---|---|---|---|---|---|")
        for (w in draft.workouts) {
            val date = runCatching { LocalDate.parse(w.start_date_local.take(10)) }.getOrNull()
            val minutes = w.moving_time / 60
            appendLine("| ${date?.let(::weekday) ?: "?"} | ${w.start_date_local.take(10)} | ${w.type} | ${w.name} | " +
                "${minutes / 60}h${(minutes % 60).toString().padStart(2, '0')} | ${if (w.hide_from_athlete) "hidden" else "visible"} |")
        }
        val total = draft.workouts.sumOf { it.moving_time } / 60
        appendLine("Total: ${total / 60}h${(total % 60).toString().padStart(2, '0')}")
    }

    private fun warningsText(issues: List<WorkoutLint.Issue>): String {
        val warnings = issues.filter { !it.blocking }
        if (warnings.isEmpty()) return ""
        return "\n\nWarnings (check them, they do not block):\n" + warnings.joinToString("\n") { "- ${it.workout}: ${it.message}" } + "\n"
    }

    private fun idsText(draft: Draft): String = buildString {
        draft.event_ids.entries.sortedBy { it.key }.forEach { (index, eventId) ->
            val w = draft.workouts[index]
            appendLine("- workouts[$index] ${w.start_date_local.take(10)} \"${w.name}\" → \"intervals_event_id\": \"$eventId\"")
        }
    }

    private fun describe(v: WorkoutOps.Verification): String = buildString {
        appendLine("Event ${v.eventId}: \"${v.name}\" (${v.type}), ${if (v.hidden == true) "hidden from" else "visible to"} the athlete, moving_time ${v.movingTime}s.")
        appendLine("Parsed steps (${v.stepTexts.size}): " + v.stepTexts.joinToString(" | ") { it.ifEmpty { "(no text)" } })
        if (v.ok) appendLine("✓ No problems found.") else {
            appendLine("⚠ Problems:")
            v.problems.forEach { appendLine("- $it") }
            appendLine("Fix: spell out numbers in the notes, remove '- ' prose lines, align moving_time; then update_workouts.")
        }
    }

    private fun weekday(d: LocalDate) = d.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.ENGLISH)

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.num(key: String): Double? = (this[key] as? JsonPrimitive)?.doubleOrNull

    companion object {
        val INSTRUCTIONS = """
            Coaching Kit connects Claude, working as assistant coach, to the coach's athletes on intervals.icu.
            Rules that always apply:
            1. Never upload, change, show/hide or delete anything on intervals.icu without the coach's explicit OK in this conversation. Write tools require coach_approved=true: set it only after that OK, for that exact change.
            2. New workouts: propose_week first (it checks the parser rules and saves a draft), present the week, wait for the OK, then upload_week with the draft ID. Never skip the draft.
            3. Never ask the coach to paste the API key in the chat: connect_intervals opens a secure page in their browser.
            4. If you are unsure whether intervals.icu is connected, call check_connection.
            The workflows (setup, new athlete, weekly review) are in the coaching-kit skills; workout-writing rules are in the intervals-workouts skill. Speak to the coach in plain, non-technical language.
        """.trimIndent()

        private val ACTIVITY_FIELDS = listOf(
            "id", "start_date_local", "type", "name", "description", "moving_time", "elapsed_time", "distance",
            "total_elevation_gain", "average_heartrate", "max_heartrate", "average_speed", "average_cadence",
            "icu_average_watts", "icu_weighted_avg_watts", "icu_training_load", "icu_intensity", "icu_rpe",
            "session_rpe", "feel", "trainer", "race", "paired_event_id", "compliance", "decoupling",
            "icu_efficiency_factor", "source", "_note"
        )
        private val INTERVAL_FIELDS = listOf(
            "type", "label", "elapsed_time", "moving_time", "distance", "average_heartrate", "max_heartrate",
            "average_speed", "average_watts", "weighted_average_watts", "average_cadence", "zone", "intensity",
            "training_load", "decoupling"
        )
        private val WELLNESS_FIELDS = listOf(
            "id", "ctl", "atl", "rampRate", "restingHR", "hrv", "hrvSDNN", "sleepSecs", "sleepScore", "sleepQuality",
            "avgSleepingHR", "readiness", "soreness", "fatigue", "stress", "mood", "motivation", "injury", "weight",
            "spO2", "menstrualPhase", "comments"
        )
        private val EVENT_FIELDS = listOf(
            "id", "start_date_local", "category", "type", "name", "moving_time", "distance", "icu_training_load",
            "hide_from_athlete"
        )

        private val WORKOUT_SCHEMA = Schema.looseObject(
            "One workout (week-N.json format)",
            mapOf(
                "start_date_local" to Schema.string("e.g. 2026-11-09T00:00:00"),
                "name" to Schema.string("Workout name"),
                "type" to Schema.string("Run, Ride, VirtualRide, Swim, WeightTraining, Other"),
                "description" to Schema.string("Structured steps, then a '---' line, then the notes"),
                "moving_time" to Schema.integer("Planned duration in seconds"),
                "hide_from_athlete" to Schema.boolean("true = hidden until the coach makes it visible"),
                "category" to Schema.string("WORKOUT (default) or NOTE"),
                "icu_training_load" to Schema.integer("Only if the server estimate is clearly wrong"),
                "intervals_event_id" to Schema.string("Only for update_workouts: the existing event ID")
            ),
            required = listOf("start_date_local", "name", "type", "description", "moving_time")
        )
    }
}

private fun JsonArray?.orEmpty(): JsonArray = this ?: JsonArray(emptyList())
