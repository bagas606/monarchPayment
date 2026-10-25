package id.ppob2.app;

import id.ppob2.decomposition.generation.PatternGenerationService;
import id.ppob2.decomposition.scoring.PatternScoringService;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Keeps pattern economics current (PRD Section 30.2 / 40.4 "Daily Full Re-Score"), because routing reads economics for
 * <em>today</em> only and a day with none serves no orders: every order paid with no eligible pattern is refunded.
 *
 * <p><b>Tomorrow is scored before it starts.</b> A job that only runs after midnight leaves a gap from 00:00 until it
 * commits (about 25 s at a million patterns), and every order paid in that gap would be refused. So:
 * <ul>
 *   <li><b>23:30, business zone</b>: write tomorrow's economics (overwriting), so the day begins fully scored.</li>
 *   <li><b>00:05</b>: refresh today with the latest prices (readers keep seeing the existing rows until the commit),
 *       make sure the new tomorrow exists, and prune old data (below).</li>
 *   <li><b>Hourly</b>: fill in today's and tomorrow's missing rows and touch nothing else. The safety net for downtime
 *       across midnight; never overwrites, so a restart cannot clobber economics set on purpose.</li>
 *   <li><b>At start</b>: a generation left BUILDING by a crash is marked FAILED, since nothing is building it any more.</li>
 * </ul>
 * Activation and a price edit score both days too (see {@code PatternGenerationService.activate}).
 *
 * <p><b>Retention.</b> Measured at ~1M patterns, each scored day is about 280 MB of {@code pattern_economics} (table and
 * indexes), so 90 days would be ~25 GB. Economics older than {@code ppob2.pattern.economics-retention-days} (default 30,
 * minimum 7) are deleted, and so are the
 * patterns of generations that FAILED (never served, so nothing refers to them). SUPERSEDED generations are kept: they
 * are the rollback targets. Margin reconciliation compares an order with the snapshot on or before its date, so keep the
 * retention well above the longest time an order can wait to be reconciled.
 *
 * <p>Switch off with {@code ppob2.pattern.maintenance-enabled=false}.
 */
@Component
public class PatternMaintenanceJobs {

    private static final Logger log = LoggerFactory.getLogger(PatternMaintenanceJobs.class);

    private final NamedParameterJdbcTemplate jdbc;
    private final PatternScoringService scoring;
    private final PatternGenerationService generations;
    private final Clock businessClock;
    private final boolean enabled;
    private final int retentionDays;

    public PatternMaintenanceJobs(NamedParameterJdbcTemplate jdbc, PatternScoringService scoring, PatternGenerationService generations,
                                  Clock businessClock,
                                  @org.springframework.beans.factory.annotation.Value("${ppob2.pattern.maintenance-enabled:true}") boolean enabled,
                                  @org.springframework.beans.factory.annotation.Value("${ppob2.pattern.economics-retention-days:30}") int retentionDays) {
        this.jdbc = jdbc;
        this.scoring = scoring;
        this.generations = generations;
        this.businessClock = businessClock;
        this.enabled = enabled;
        this.retentionDays = Math.max(7, retentionDays);
    }

    @EventListener(ApplicationReadyEvent.class)
    void onReady() {
        if (!enabled) {
            return;
        }
        int failed = generations.failInterrupted();
        if (failed > 0) {
            log.warn("{} pattern generation(s) were interrupted by the last shutdown and are now FAILED", failed);
        }
    }

    /** 23:30 business zone: tomorrow's rows, so the new day does not begin with a gap. */
    @Scheduled(cron = "${ppob2.pattern.score-tomorrow-cron:0 30 23 * * *}", zone = "${ppob2.business-zone:UTC}")
    public void scoreTomorrow() {
        if (!enabled) {
            return;
        }
        activeGeneration().ifPresent(gen -> {
            LocalDate tomorrow = LocalDate.now(businessClock).plusDays(1);
            int rows = scoring.rescore(gen, tomorrow);
            log.info("Scored tomorrow ({}) for generation {}: {} patterns", tomorrow, gen, rows);
        });
    }

    /** 00:05 business zone: refresh today, make sure tomorrow exists, prune. */
    @Scheduled(cron = "${ppob2.pattern.rescore-cron:0 5 0 * * *}", zone = "${ppob2.business-zone:UTC}")
    public void dailyRescore() {
        if (!enabled) {
            return;
        }
        LocalDate today = LocalDate.now(businessClock);
        activeGeneration().ifPresent(gen -> {
            int rows = scoring.rescore(gen, today);
            scoring.ensure(gen, today.plusDays(1));
            log.info("Daily re-score of generation {}: {} patterns", gen, rows);
        });
        prune(today);
    }

    @Scheduled(fixedDelayString = "${ppob2.pattern.ensure-interval-ms:3600000}", initialDelay = 120_000)
    public void ensureUpcoming() {
        if (!enabled) {
            return;
        }
        LocalDate today = LocalDate.now(businessClock);
        activeGeneration().ifPresent(gen -> {
            int missing = scoring.ensure(gen, today) + scoring.ensure(gen, today.plusDays(1));
            if (missing > 0) {
                log.warn("Generation {} was missing economics for {} pattern-day(s); filled in", gen, missing);
            }
        });
    }

    void prune(LocalDate today) {
        int old = jdbc.update("DELETE FROM pattern_economics WHERE snapshot_date < :cutoff", Map.of("cutoff", today.minusDays(retentionDays)));
        List<Long> dead = jdbc.queryForList("""
                SELECT id FROM pattern_generation WHERE status = 'FAILED' AND completed_at < now() - interval '1 day'
                  AND EXISTS (SELECT 1 FROM decomposition_pattern p WHERE p.generation_id = pattern_generation.id)""", Map.of(), Long.class);
        for (Long id : dead) {
            Map<String, Object> g = Map.of("g", id);
            jdbc.update("DELETE FROM pattern_economics WHERE pattern_id IN (SELECT id FROM decomposition_pattern WHERE generation_id = :g)", g);
            jdbc.update("DELETE FROM pattern_usage WHERE pattern_id IN (SELECT id FROM decomposition_pattern WHERE generation_id = :g)", g);
            jdbc.update("DELETE FROM decomposition_component WHERE pattern_id IN (SELECT id FROM decomposition_pattern WHERE generation_id = :g)", g);
            jdbc.update("DELETE FROM decomposition_pattern WHERE generation_id = :g", g);
        }
        if (old > 0 || !dead.isEmpty()) {
            log.info("Pruned {} economics row(s) older than {} days and the patterns of {} failed generation(s)", old, retentionDays, dead.size());
        }
    }

    private java.util.Optional<Long> activeGeneration() {
        List<Long> ids = jdbc.queryForList("SELECT id FROM pattern_generation WHERE status = 'ACTIVE'", Map.of(), Long.class);
        return ids.stream().findFirst();
    }
}
