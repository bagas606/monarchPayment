package id.ppob2.pricing.repository;

import id.ppob2.pricing.domain.PatternUsage;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PatternUsageRepository extends JpaRepository<PatternUsage, Long> {

    List<PatternUsage> findByPatternIdInAndUsageDate(List<Long> patternIds, LocalDate usageDate);

    Optional<PatternUsage> findByPatternIdAndUsageDate(Long patternId, LocalDate usageDate);

    /** Conflict-safe upsert — see {@code PaymentEventRepository.insertIfAbsent}'s Javadoc for why
     * this codebase uses {@code ON CONFLICT} rather than read-then-write for concurrent counters. */
    @Modifying
    @Query(value = """
            INSERT INTO pattern_usage (pattern_id, usage_date, daily_usage, lifetime_usage)
            VALUES (:patternId, :usageDate, 1, 1)
            ON CONFLICT (pattern_id, usage_date)
            DO UPDATE SET daily_usage = pattern_usage.daily_usage + 1, lifetime_usage = pattern_usage.lifetime_usage + 1
            """, nativeQuery = true)
    void increment(@Param("patternId") Long patternId, @Param("usageDate") LocalDate usageDate);
}
