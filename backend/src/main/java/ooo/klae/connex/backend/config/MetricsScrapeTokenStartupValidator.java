package ooo.klae.connex.backend.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

/**
 * Announces at startup that the metrics endpoint is unreachable without a scrape token.
 *
 * <p>{@code /api/metrics} is gated on the scrape-token authority alone, so a deployment that never
 * set {@code CONNEX_METRICS_SCRAPE_TOKEN} serves nothing to its monitoring stack. Warning here
 * makes that a boot-time signal instead of a silent gap in an operator dashboard.
 */
@Component
public class MetricsScrapeTokenStartupValidator implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(MetricsScrapeTokenStartupValidator.class);

    private final String scrapeToken;
    private final Environment environment;

    public MetricsScrapeTokenStartupValidator(
            @Value("${connex.metrics.scrape-token:}") String scrapeToken,
            Environment environment) {
        this.scrapeToken = scrapeToken;
        this.environment = environment;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (scrapeToken != null && !scrapeToken.isBlank()) {
            return;
        }
        if (environment.acceptsProfiles(Profiles.of("dev", "test"))) {
            log.debug("connex.metrics.scrape-token is unset (dev/test) — GET /api/metrics is unavailable");
            return;
        }
        log.warn("CONNEX_METRICS_SCRAPE_TOKEN is unset, so GET /api/metrics is unavailable to every caller. "
            + "Set it to a long random value and send it as 'Authorization: Bearer <token>' from your scraper");
    }
}
