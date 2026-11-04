package lib

import kotlinx.serialization.Serializable

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
