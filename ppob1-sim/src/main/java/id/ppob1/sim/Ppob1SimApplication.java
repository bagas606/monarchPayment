package id.ppob1.sim;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * PPOB1 — the reseller side of the PRD Section 23 contract, simulated.
 *
 * <p>Runs as its own process against its own database, and reaches PPOB2 only over signed HTTP.
 * That isolation is the feature: it is what makes this a conformance test of the published contract
 * rather than a second front door onto PPOB2's internals. See {@code ppob1-sim/README.md}.
 */
@SpringBootApplication
public class Ppob1SimApplication {

    public static void main(String[] args) {
        SpringApplication.run(Ppob1SimApplication.class, args);
    }
}
