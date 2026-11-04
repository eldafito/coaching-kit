package lib

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.security.SecureRandom
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Weeks proposed to the coach and not necessarily uploaded yet.
 *
 * `propose_week` stores the exact workouts shown to the coach; `upload_week` can
 * only upload a stored draft. What reaches intervals.icu is therefore always
 * what the coach saw and approved, and a draft is never uploaded twice.
 */
@Serializable
data class Draft(
    val id: String,
    val athlete_id: String,
    val athlete_label: String,
    val week_label: String,
    val created_at: String,
    val workouts: List<WorkoutDef>,
    /** intervals.icu event ID per workout index, filled in as uploads succeed. */
    val event_ids: Map<Int, String> = emptyMap()
) {
    val fullyUploaded: Boolean get() = workouts.indices.all { it in event_ids }
}

object Drafts {

    private const val KEEP_DAYS = 60L
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }
    private val ID_FORMAT = Regex("^d-\\d{8}-\\d{6}-[0-9a-f]{4}$")

    fun create(athleteId: String, athleteLabel: String, weekLabel: String, workouts: List<WorkoutDef>): Draft {
        val now = LocalDateTime.now()
        val suffix = ByteArray(2).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        val draft = Draft(
            id = "d-${now.format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))}-$suffix",
            athlete_id = athleteId,
            athlete_label = athleteLabel,
            week_label = weekLabel,
            created_at = now.withNano(0).toString(),
            workouts = workouts
        )
        save(draft)
        cleanup()
        return draft
    }

    fun load(id: String): Draft? {
        if (!ID_FORMAT.matches(id)) return null
        val file = file(id)
        if (!file.exists()) return null
        return json.decodeFromString(Draft.serializer(), file.readText())
    }

    fun save(draft: Draft) {
        AppDirs.writePrivateFile(file(draft.id), json.encodeToString(Draft.serializer(), draft))
    }

    private fun file(id: String) = File(AppDirs.draftsDir, "$id.json")

    private fun cleanup() {
        val cutoff = System.currentTimeMillis() - KEEP_DAYS * 24 * 3600 * 1000
        AppDirs.draftsDir.listFiles { f -> f.name.endsWith(".json") && f.lastModified() < cutoff }?.forEach { it.delete() }
    }
}
