package ooo.klae.connex.backend.controllers;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import lombok.RequiredArgsConstructor;

/**
 * Operator-consumable Prometheus metrics endpoint.
 *
 * <p>Only a caller holding the operator-configured scrape token can read this instance-global
 * telemetry: the security chain gates every method on this path behind the
 * {@code METRICS_SCRAPE} authority, which the scrape-token filter is the sole source of. An
 * ordinary authenticated session cannot read it. The endpoint deliberately bypasses tenant
 * resolution because it contains no workspace data. With no scrape token configured the endpoint
 * is unavailable to everyone.
 */
@RestController
@RequestMapping("/api/metrics")
@RequiredArgsConstructor
public class MetricsController {
    public static final String PROMETHEUS_CONTENT_TYPE =
            "text/plain; version=0.0.4; charset=utf-8";

    private final PrometheusMeterRegistry meterRegistry;

    /**
     * Returns all registered meters in Prometheus text exposition format.
     *
     * @return Prometheus text exposition
     */
    @GetMapping(produces = PROMETHEUS_CONTENT_TYPE)
    public String metrics() {
        return meterRegistry.scrape();
    }
}
