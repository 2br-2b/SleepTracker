package codegito.xyz.healthconnector.data.model

/**
 * A start-to-end time range stored as minutes from midnight (0–1439).
 * Used for both sleep detection windows and nutrition meal windows.
 */
data class TimeRange(val startMinutes: Int, val endMinutes: Int) {
    companion object {
        val BEDTIME  = TimeRange(21 * 60, 2 * 60)
        val WAKEUP   = TimeRange(5 * 60, 12 * 60)
        val BREAKFAST = TimeRange(6 * 60, 10 * 60)
        val LUNCH     = TimeRange(11 * 60, 14 * 60)
        val DINNER    = TimeRange(17 * 60, 21 * 60)
    }

    /** Returns true if [minuteOfDay] falls within this range (handles midnight wrap). */
    operator fun contains(minuteOfDay: Int): Boolean =
        if (startMinutes <= endMinutes) minuteOfDay in startMinutes until endMinutes
        else minuteOfDay >= startMinutes || minuteOfDay < endMinutes
}
