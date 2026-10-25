package id.ppob2.app;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import id.ppob2.decomposition.generation.PatternGenerationService;
import id.ppob2.decomposition.scoring.PatternScoringService;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * Routing reads economics for the current business day only, so the next day has to be scored BEFORE midnight: a gap of
 * even seconds sends every order paid in it to REFUND_PENDING. Clock-pinned, because no live suite ever crosses midnight.
 */
class PatternMaintenanceJobsTest {

    private static final ZoneId JAKARTA = ZoneId.of("Asia/Jakarta");
    private final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    private final PatternScoringService scoring = mock(PatternScoringService.class);
    private final PatternGenerationService generations = mock(PatternGenerationService.class);

    private PatternMaintenanceJobs jobsAt(String instant, boolean enabled) {
        when(jdbc.queryForList(any(String.class), anyMap(), eq(Long.class))).thenReturn(List.of(7L));
        return new PatternMaintenanceJobs(jdbc, scoring, generations, Clock.fixed(Instant.parse(instant), JAKARTA), enabled, 90);
    }

    @Test
    void theEveningJobScoresTheNextBusinessDayBeforeItStarts() {
        // 23:59 on 24 Oct in Jakarta is 16:59Z. "Tomorrow" is the 25th in the BUSINESS zone, not in UTC.
        jobsAt("2026-10-24T16:59:00Z", true).scoreTomorrow();
        verify(scoring).rescore(7L, LocalDate.of(2026, 10, 25));
        verify(scoring, never()).rescore(7L, LocalDate.of(2026, 10, 24));
    }

    @Test
    void theMidnightJobRefreshesTodayAndKeepsTomorrowPresent() {
        jobsAt("2026-10-24T17:05:00Z", true).dailyRescore(); // 00:05 on the 25th, Jakarta
        verify(scoring).rescore(7L, LocalDate.of(2026, 10, 25));
        verify(scoring).ensure(7L, LocalDate.of(2026, 10, 26));
    }

    @Test
    void theHourlySafetyNetFillsTodayAndTomorrowWithoutOverwriting() {
        jobsAt("2026-10-24T05:00:00Z", true).ensureUpcoming();
        verify(scoring).ensure(7L, LocalDate.of(2026, 10, 24));
        verify(scoring).ensure(7L, LocalDate.of(2026, 10, 25));
        verify(scoring, never()).rescore(any(Long.class), any(LocalDate.class));
    }

    @Test
    void nothingRunsWhenSwitchedOff() {
        PatternMaintenanceJobs jobs = jobsAt("2026-10-24T16:59:00Z", false);
        jobs.scoreTomorrow();
        jobs.dailyRescore();
        jobs.ensureUpcoming();
        verify(scoring, never()).rescore(any(Long.class), any(LocalDate.class));
        verify(scoring, never()).ensure(any(Long.class), any(LocalDate.class));
    }
}
