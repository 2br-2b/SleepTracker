package codegito.xyz.healthconnector.logic

import androidx.health.connect.client.records.SleepSessionRecord
import codegito.xyz.healthconnector.data.db.ScreenEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class SleepDetectionEngineTest {

    @Test
    fun detectSleep_ignoresTemporaryWakeupAndUsesFinalWakeup() {
        // 05:00–05:10 wake-up (10-min gap before next check), threshold=60 → merged into one awake period
        val events = listOf(
            event("2026-01-10T22:30", "LOCK"),
            event("2026-01-11T05:00", "UNLOCK"),
            event("2026-01-11T05:10", "LOCK"),
            event("2026-01-11T07:00", "UNLOCK")
        )

        val result = SleepDetectionEngine.detectSleep(
            events = events,
            bedtimeWindowStart = 21 * 60,
            bedtimeWindowEnd = 2 * 60,
            wakeupWindowStart = 5 * 60,
            wakeupWindowEnd = 12 * 60,
            awakeningThresholdMinutes = 60,
            defaultAwakeMinutes = 15,
            targetDate = LocalDate.of(2026, 1, 10)
        )

        assertNotNull(result)
        val (_, segments) = result!!

        assertEquals(4, segments.size)
        assertEquals(SleepSessionRecord.STAGE_TYPE_AWAKE_IN_BED, segments[0].sleepStage)
        assertEquals(LocalDateTime.parse("2026-01-10T22:45"), segments[0].endTime)

        assertEquals(SleepSessionRecord.STAGE_TYPE_SLEEPING, segments[1].sleepStage)
        assertEquals(LocalDateTime.parse("2026-01-11T05:00"), segments[1].endTime)

        // Wake-up is now AWAKE_IN_BED (not AWAKE)
        assertEquals(SleepSessionRecord.STAGE_TYPE_AWAKE_IN_BED, segments[2].sleepStage)
        assertEquals(LocalDateTime.parse("2026-01-11T05:10"), segments[2].endTime)

        assertEquals(SleepSessionRecord.STAGE_TYPE_SLEEPING, segments[3].sleepStage)
        assertEquals(LocalDateTime.parse("2026-01-11T07:00"), segments[3].endTime)
    }

    @Test
    fun detectSleep_usesFirstWakeupWhenUserStaysAwake() {
        val events = listOf(
            event("2026-01-10T22:30", "LOCK"),
            event("2026-01-11T05:00", "UNLOCK")
        )

        val result = SleepDetectionEngine.detectSleep(
            events = events,
            bedtimeWindowStart = 21 * 60,
            bedtimeWindowEnd = 2 * 60,
            wakeupWindowStart = 5 * 60,
            wakeupWindowEnd = 12 * 60,
            awakeningThresholdMinutes = 60,
            defaultAwakeMinutes = 15,
            targetDate = LocalDate.of(2026, 1, 10)
        )

        assertNotNull(result)
        val (_, segments) = result!!

        assertEquals(2, segments.size)
        assertEquals(SleepSessionRecord.STAGE_TYPE_AWAKE_IN_BED, segments[0].sleepStage)
        assertEquals(LocalDateTime.parse("2026-01-10T22:45"), segments[0].endTime)

        assertEquals(SleepSessionRecord.STAGE_TYPE_SLEEPING, segments[1].sleepStage)
        assertEquals(LocalDateTime.parse("2026-01-11T05:00"), segments[1].endTime)
    }

    /**
     * Two brief wake-ups separated by a gap of 5 minutes (≤ threshold of 10 minutes) should be
     * merged into one continuous AWAKE_IN_BED segment spanning from the first UNLOCK to the
     * last LOCK.
     *
     * Timeline:
     *   22:30 LOCK  → bedtime
     *   03:00 UNLOCK → start of first wake-up
     *   03:05 LOCK   → fell back asleep
     *   03:10 UNLOCK → second wake-up (5-min gap from 03:05 → within 10-min threshold)
     *   03:15 LOCK   → fell back asleep again
     *   07:00 UNLOCK → final wakeup (stays awake)
     *
     * Expected: one merged AWAKE_IN_BED from 03:00 to 03:15
     */
    @Test
    fun detectSleep_mergesCloseAwakenings() {
        val events = listOf(
            event("2026-01-10T22:30", "LOCK"),
            event("2026-01-11T03:00", "UNLOCK"),
            event("2026-01-11T03:05", "LOCK"),
            event("2026-01-11T03:10", "UNLOCK"),
            event("2026-01-11T03:15", "LOCK"),
            event("2026-01-11T07:00", "UNLOCK")
        )

        val result = SleepDetectionEngine.detectSleep(
            events = events,
            bedtimeWindowStart = 21 * 60,
            bedtimeWindowEnd = 2 * 60,
            wakeupWindowStart = 5 * 60,
            wakeupWindowEnd = 12 * 60,
            awakeningThresholdMinutes = 10,
            defaultAwakeMinutes = 15,
            targetDate = LocalDate.of(2026, 1, 10)
        )

        assertNotNull(result)
        val (_, segments) = result!!

        // AWAKE_IN_BED (initial) | SLEEPING | AWAKE_IN_BED (merged) | SLEEPING
        assertEquals(4, segments.size)

        assertEquals(SleepSessionRecord.STAGE_TYPE_AWAKE_IN_BED, segments[0].sleepStage)
        assertEquals(LocalDateTime.parse("2026-01-10T22:45"), segments[0].endTime)

        assertEquals(SleepSessionRecord.STAGE_TYPE_SLEEPING, segments[1].sleepStage)
        assertEquals(LocalDateTime.parse("2026-01-11T03:00"), segments[1].endTime)

        // Merged awakening: 03:00 → 03:15 (two close wake-ups collapsed into one)
        assertEquals(SleepSessionRecord.STAGE_TYPE_AWAKE_IN_BED, segments[2].sleepStage)
        assertEquals(LocalDateTime.parse("2026-01-11T03:15"), segments[2].endTime)

        assertEquals(SleepSessionRecord.STAGE_TYPE_SLEEPING, segments[3].sleepStage)
        assertEquals(LocalDateTime.parse("2026-01-11T07:00"), segments[3].endTime)
    }

    /**
     * Two wake-ups separated by a gap larger than the threshold should remain as two separate
     * AWAKE_IN_BED segments.
     *
     * Timeline:
     *   22:30 LOCK  → bedtime
     *   02:00 UNLOCK → first wake-up
     *   02:05 LOCK
     *   03:00 UNLOCK → second wake-up (55-min gap > 10-min threshold → separate)
     *   03:05 LOCK
     *   07:00 UNLOCK → final wakeup
     *
     * Expected: two separate AWAKE_IN_BED segments
     */
    @Test
    fun detectSleep_keepsDistantAwakeningsSeparate() {
        val events = listOf(
            event("2026-01-10T22:30", "LOCK"),
            event("2026-01-11T02:00", "UNLOCK"),
            event("2026-01-11T02:05", "LOCK"),
            event("2026-01-11T03:00", "UNLOCK"),
            event("2026-01-11T03:05", "LOCK"),
            event("2026-01-11T07:00", "UNLOCK")
        )

        val result = SleepDetectionEngine.detectSleep(
            events = events,
            bedtimeWindowStart = 21 * 60,
            bedtimeWindowEnd = 2 * 60,
            wakeupWindowStart = 5 * 60,
            wakeupWindowEnd = 12 * 60,
            awakeningThresholdMinutes = 10,
            defaultAwakeMinutes = 15,
            targetDate = LocalDate.of(2026, 1, 10)
        )

        assertNotNull(result)
        val (_, segments) = result!!

        // AWAKE_IN_BED (initial) | SLEEPING | AWAKE_IN_BED | SLEEPING | AWAKE_IN_BED | SLEEPING
        assertEquals(6, segments.size)

        assertEquals(SleepSessionRecord.STAGE_TYPE_AWAKE_IN_BED, segments[0].sleepStage)
        assertEquals(LocalDateTime.parse("2026-01-10T22:45"), segments[0].endTime)

        assertEquals(SleepSessionRecord.STAGE_TYPE_SLEEPING, segments[1].sleepStage)
        assertEquals(LocalDateTime.parse("2026-01-11T02:00"), segments[1].endTime)

        assertEquals(SleepSessionRecord.STAGE_TYPE_AWAKE_IN_BED, segments[2].sleepStage)
        assertEquals(LocalDateTime.parse("2026-01-11T02:05"), segments[2].endTime)

        assertEquals(SleepSessionRecord.STAGE_TYPE_SLEEPING, segments[3].sleepStage)
        assertEquals(LocalDateTime.parse("2026-01-11T03:00"), segments[3].endTime)

        assertEquals(SleepSessionRecord.STAGE_TYPE_AWAKE_IN_BED, segments[4].sleepStage)
        assertEquals(LocalDateTime.parse("2026-01-11T03:05"), segments[4].endTime)

        assertEquals(SleepSessionRecord.STAGE_TYPE_SLEEPING, segments[5].sleepStage)
        assertEquals(LocalDateTime.parse("2026-01-11T07:00"), segments[5].endTime)
    }

    private fun event(dateTime: String, type: String): ScreenEvent {
        val millis = LocalDateTime.parse(dateTime)
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
        return ScreenEvent(timestampMillis = millis, type = type)
    }
}
