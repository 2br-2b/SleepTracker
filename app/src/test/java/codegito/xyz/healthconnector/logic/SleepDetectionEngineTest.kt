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

        assertEquals(SleepSessionRecord.STAGE_TYPE_AWAKE, segments[2].sleepStage)
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

    private fun event(dateTime: String, type: String): ScreenEvent {
        val millis = LocalDateTime.parse(dateTime)
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
        return ScreenEvent(timestampMillis = millis, type = type)
    }
}
