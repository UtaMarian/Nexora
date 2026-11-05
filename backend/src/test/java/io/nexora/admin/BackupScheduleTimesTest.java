package io.nexora.admin;

import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class BackupScheduleTimesTest {
    private static BackupScheduler.Stored daily(int hour) {
        return new BackupScheduler.Stored(true, BackupScheduler.Frequency.DAILY, hour, DayOfWeek.SUNDAY, 7, null);
    }

    private static BackupScheduler.Stored weekly(DayOfWeek day, int hour) {
        return new BackupScheduler.Stored(true, BackupScheduler.Frequency.WEEKLY, hour, day, 7, null);
    }

    @Test
    void aDailyRunIsLaterTodayOrTomorrow() {
        assertThat(BackupScheduler.nextAfter(daily(3), Instant.parse("2026-11-05T01:00:00Z"))).isEqualTo("2026-11-05T03:00:00Z");
        assertThat(BackupScheduler.nextAfter(daily(3), Instant.parse("2026-11-05T04:00:00Z"))).isEqualTo("2026-11-06T03:00:00Z");
    }

    @Test
    void theExactMomentCountsAsPassed() {
        assertThat(BackupScheduler.nextAfter(daily(3), Instant.parse("2026-11-05T03:00:00Z"))).isEqualTo("2026-11-06T03:00:00Z");
    }

    @Test
    void aWeeklyRunWaitsForItsDay() { // 5 November 2026 is a Thursday
        assertThat(BackupScheduler.nextAfter(weekly(DayOfWeek.SUNDAY, 2), Instant.parse("2026-11-05T12:00:00Z"))).isEqualTo("2026-11-08T02:00:00Z");
        assertThat(BackupScheduler.nextAfter(weekly(DayOfWeek.THURSDAY, 13), Instant.parse("2026-11-05T12:00:00Z"))).isEqualTo("2026-11-05T13:00:00Z");
        assertThat(BackupScheduler.nextAfter(weekly(DayOfWeek.THURSDAY, 11), Instant.parse("2026-11-05T12:00:00Z"))).isEqualTo("2026-11-12T11:00:00Z");
    }
}
