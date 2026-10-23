package lib

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Minimal subset of intervals.icu API models we care about.
 * Most fields are optional because the API returns many nullable values.
 */

@Serializable
data class AthleteProfile(
    val id: String? = null,
    val name: String? = null,
    val email: String? = null,
    val sex: String? = null,
    val bio: String? = null,
    val weight: Double? = null,
    val icu_ftp: Int? = null,
    val icu_resting_hr: Int? = null,
    val icu_w_prime: Int? = null,
    val threshold_hr: Int? = null,
    val max_hr: Int? = null,
    val lthr: Int? = null,
    val pace_threshold: Double? = null, // m/s for run threshold
    val sportSettings: List<SportSettings>? = null
)

@Serializable
data class SportSettings(
    val types: List<String>? = null,
    val ftp: Int? = null,
    val lthr: Int? = null,
    val threshold_pace: Double? = null,
    val max_hr: Int? = null,
    val resting_hr: Int? = null,
    val pace_zones: List<Double>? = null,
    val hr_zones: List<Int>? = null,
    val power_zones: List<Double>? = null
)

@Serializable
data class Activity(
    val id: String? = null,
    val start_date_local: String? = null,
    val type: String? = null,
    val name: String? = null,
    val description: String? = null,
    val moving_time: Int? = null,
    val elapsed_time: Int? = null,
    val distance: Double? = null,
    val total_elevation_gain: Double? = null,
    val average_heartrate: Double? = null,
    val max_heartrate: Double? = null,
    val average_speed: Double? = null,
    val icu_training_load: Int? = null,
    val icu_atl: Double? = null,
    val icu_ctl: Double? = null,
    val icu_intensity: Int? = null,
    val icu_efficiency_factor: Double? = null,
    val icu_pm_ftp: Int? = null,
    val average_watts: Double? = null,
    val weighted_average_watts: Double? = null,
    val pace: Double? = null
)

@Serializable
data class Wellness(
    val id: String? = null,
    val ctl: Double? = null,
    val atl: Double? = null,
    val rampRate: Double? = null,
    val ctlLoad: Double? = null,
    val atlLoad: Double? = null,
    val sportInfo: JsonElement? = null,
    val updated: String? = null,
    val weight: Double? = null,
    val restingHR: Int? = null,
    val hrv: Double? = null,
    val hrvSDNN: Double? = null,
    val sleepSecs: Int? = null,
    val sleepScore: Int? = null,
    val readiness: Int? = null,
    val fatigue: Int? = null,
    val soreness: Int? = null,
    val stress: Int? = null,
    val mood: Int? = null
)

@Serializable
data class Event(
    val id: String? = null,
    val start_date_local: String? = null,
    val end_date_local: String? = null,
    val category: String? = null,
    val type: String? = null,
    val name: String? = null,
    val description: String? = null,
    val moving_time: Int? = null,
    val icu_training_load: Int? = null,
    val hide_from_athlete: Boolean? = null
)
