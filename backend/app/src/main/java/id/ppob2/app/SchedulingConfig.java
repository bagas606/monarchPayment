package id.ppob2.app;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Kept out of {@link Ppob2Application} for the same reason {@link JpaConfig} is separate: avoid
 * pulling infrastructure a slice test doesn't need into its context. Enables {@code
 * QrExpirySweepJob}'s {@code @Scheduled} method (`order` module, Section 48.3).
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
