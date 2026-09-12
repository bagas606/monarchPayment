package id.ppob2.app;

import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * Kept out of {@link Ppob2Application} deliberately: annotations declared directly on the
 * {@code @SpringBootApplication} class are not filtered out by {@code @WebMvcTest}/other test
 * slices, which would otherwise try to build an EntityManagerFactory (and fail) even for a
 * controller-only slice test.
 */
@Configuration
@EntityScan(basePackages = "id.ppob2")
@EnableJpaRepositories(basePackages = "id.ppob2")
public class JpaConfig {
}
