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
 * <p>Session-authenticated users and callers holding the operator-configured scrape token can read
 * this instance-global telemetry. The endpoint deliberately bypasses tenant resolution because it
 * contains no workspace data. Connex has no login-capable platform-admin authority yet, so every
 * authenticated session can temporarily read it; a future instance-admin role must replace that
 * session gate.
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
