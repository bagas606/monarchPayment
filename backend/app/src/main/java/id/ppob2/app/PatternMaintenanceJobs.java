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
 * <em>today</em> only and a day with none serves no orders.
 *
 * <ul>
 *   <li><b>Just after midnight, business zone</b>: full re-score of the ACTIVE generation, overwriting, so prices that
 *       changed overnight are reflected.</li>
 *   <li><b>Hourly (and shortly after start)</b>: fill in patterns that have no row for today and touch nothing else. This
 *       is the safety net for downtime across midnight or a missed run. It never overwrites, so a restart in the
 *       middle of the day cannot clobber economics that were set on purpose.</li>
 *   <li><b>At start</b>: a generation left BUILDING by a crash is marked FAILED, since nothing is building it any more
 *       and it would otherwise block every new run.</li>
 * </ul>
 * Switch off with {@code ppob2.pattern.maintenance-enabled=false}.
 */
@Component
public class PatternMaintenanceJobs {

    private static final Logger log = LoggerFactory.getLogger(PatternMaintenanceJobs.class);

    private final NamedParameterJdbcTemplate jdbc;
    private final PatternScoringService scoring;
    private final PatternGenerationService generations;
    private final Clock businessClock;
    private final boolean enabled;

    public PatternMaintenanceJobs(NamedParameterJdbcTemplate jdbc, PatternScoringService scoring, PatternGenerationService generations,
                                  Clock businessClock,
                                  @org.springframework.beans.factory.annotation.Value("${ppob2.pattern.maintenance-enabled:true}") boolean enabled) {
        this.jdbc = jdbc;
        this.scoring = scoring;
        this.generations = generations;
        this.businessClock = businessClock;
        this.enabled = enabled;
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

    @Scheduled(cron = "${ppob2.pattern.rescore-cron:0 5 0 * * *}", zone = "${ppob2.business-zone:UTC}")
    public void dailyRescore() {
        if (!enabled) {
            return;
        }
        activeGeneration().ifPresent(gen -> {
            int rows = scoring.rescore(gen, LocalDate.now(businessClock));
            log.info("Daily re-score of generation {}: {} patterns", gen, rows);
        });
    }

    @Scheduled(fixedDelayString = "${ppob2.pattern.ensure-interval-ms:3600000}", initialDelay = 120_000)
    public void ensureToday() {
        if (!enabled) {
            return;
        }
        activeGeneration().ifPresent(gen -> {
            int rows = scoring.ensure(gen, LocalDate.now(businessClock));
            if (rows > 0) {
                log.warn("Generation {} had {} pattern(s) with no economics for today; filled in", gen, rows);
            }
        });
    }

    private java.util.Optional<Long> activeGeneration() {
        List<Long> ids = jdbc.queryForList("SELECT id FROM pattern_generation WHERE status = 'ACTIVE'", Map.of(), Long.class);
        return ids.stream().findFirst();
    }
}
