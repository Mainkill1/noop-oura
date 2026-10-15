package com.noop.ui

internal enum class OuraViewMode { History, Live }
internal enum class OuraCategory { Health, Sleep, Movement, Compare }

/** One catalog drives category summaries, charts, and the cross-category comparison picker. */
internal fun ouraCategoryKeys(imported: Boolean, category: OuraCategory): List<String> {
    val health = if (imported) listOf("rhr", "hrv", "spo2", "resp_rate", "skin_temp_dev_c", "ref_readiness_score", "vo2max")
        else listOf("hr", "oxygen", "temperature", "hrv", "breathing", "battery")
    val sleep = if (imported) listOf("sleep_total_min", "sleep_deep_min", "sleep_rem_min", "ref_sleep_score") else emptyList()
    val movement = if (imported) listOf("steps", "energy_kcal", "total_kcal", "ref_activity_score")
        else listOf("movement", "motion_x", "motion_y", "motion_z", "motion_low", "motion_high")
    return when (category) {
        OuraCategory.Health -> health
        OuraCategory.Sleep -> sleep
        OuraCategory.Movement -> movement
        OuraCategory.Compare -> health + sleep + movement
    }
}
