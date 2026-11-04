package lib

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeParseException

/**
 * Static checks for a workout before it reaches intervals.icu. They encode the
 * parser rules of the intervals-workouts skill: intervals.icu parses every line
 * of the description, prose included, so a stray `200m` or `- ` in the notes
 * becomes a phantom step on the athlete's watch.
 */
object WorkoutLint {

    data class Issue(val workout: String, val message: String, val blocking: Boolean)

    private val TYPES = setOf("Run", "Ride", "VirtualRide", "Swim", "WeightTraining", "Walk", "Hike", "Rowing", "Workout", "Other")
    private val CATEGORIES = setOf("WORKOUT", "NOTE")
    private val STRUCTURED = setOf("Run", "Ride", "VirtualRide", "Swim", "Rowing")

    /** `<number><unit>` as the parser reads it: 200m, 45', 30s, 5m30s, 4x80m, 1km, 500 ml. */
    private val NUMBER_UNIT = Regex("(?<![\\d.,])\\d+(?:[.,]\\d+)?\\s?(?:km|mtr|min|sec|ml|m|s|h|')(?![A-Za-z])")
    private val BPM_RANGE = Regex("\\b\\d{2,3}\\s?-\\s?\\d{2,3}\\b(?!\\s?%)")
    private val TARGET = Regex("(Z\\d|%|HR|LTHR|FTP|Pace|Power|/km|/100m|/mi|\\d\\s?W\\b|RPE|rpm|Rest|Recover)", RegexOption.IGNORE_CASE)

    fun check(workouts: List<WorkoutDef>, today: LocalDate = LocalDate.now()): List<Issue> {
        val issues = mutableListOf<Issue>()
        for (w in workouts) {
            val label = "${w.start_date_local.take(10)} ${w.name}"
            fun block(msg: String) { issues += Issue(label, msg, blocking = true) }
            fun warn(msg: String) { issues += Issue(label, msg, blocking = false) }

            val date = try {
                LocalDateTime.parse(w.start_date_local)
            } catch (_: DateTimeParseException) {
                block("start_date_local must look like 2026-10-27T00:00:00 (got \"${w.start_date_local}\")")
                null
            }
            if (date != null && date.toLocalDate().isBefore(today)) warn("date is in the past")
            if (w.type !in TYPES) block("type \"${w.type}\" is not one of ${TYPES.joinToString()}")
            if (w.category !in CATEGORIES) block("category must be WORKOUT or NOTE")
            if (w.name.isBlank()) block("name is empty")
            if (w.category == "WORKOUT" && w.moving_time <= 0) block("moving_time must be the planned duration in seconds")

            val lines = w.description.lines()
            val sep = lines.indexOfFirst { it.trim() == "---" }
            val stepLines = if (sep >= 0) lines.subList(0, sep) else lines
            val proseLines = if (sep >= 0) lines.subList(sep + 1, lines.size) else emptyList()

            if (sep < 0 && w.type in STRUCTURED) {
                val looksLikeProse = stepLines.any { line ->
                    val t = line.trim()
                    t.isNotEmpty() && !t.startsWith("- ") && !t.startsWith("#") && !Regex("^(.*\\s)?\\d+x$").matches(t) && t.length > 40
                }
                if (looksLikeProse) block("missing the `---` line between the structured steps and the notes")
            }

            if (w.type in STRUCTURED) {
                for (line in stepLines.map { it.trim() }.filter { it.startsWith("- ") }) {
                    if (BPM_RANGE.containsMatchIn(line) && !line.contains("rpm")) {
                        warn("step \"$line\" uses a bpm range: write HR targets as zones (Z2 HR, 95% LTHR)")
                    }
                    if (!TARGET.containsMatchIn(line)) {
                        warn("step \"$line\" has no target (zone, % or pace): the chart will not render it")
                    }
                }
            }

            for (line in proseLines) {
                val t = line.trimStart()
                if (t.startsWith("- ") || t.startsWith("* ")) {
                    block("notes line \"${t.take(60)}\" starts with '${t.take(1)} ': the parser turns it into an empty step. Use '1.' or '— '")
                }
                NUMBER_UNIT.findAll(line).forEach { m ->
                    block("notes contain \"${m.value.trim()}\": the parser reads it as a step duration. Spell it out (e.g. \"two hundred metres\", \"forty-five minutes\")")
                }
            }
        }
        return issues
    }
}
