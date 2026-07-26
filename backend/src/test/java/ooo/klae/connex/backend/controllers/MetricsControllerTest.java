package ooo.klae.connex.backend.controllers;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import io.micrometer.core.instrument.Counter;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;

class MetricsControllerTest {
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        PrometheusMeterRegistry meterRegistry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        Counter.builder("connex.metrics.test")
                .description("Controller scrape test")
                .register(meterRegistry)
                .increment(3.0);
        mockMvc = MockMvcBuilders.standaloneSetup(new MetricsController(meterRegistry)).build();
    }

    @Test
    void metricsReturnsPrometheusTextExposition() throws Exception {
        mockMvc.perform(get("/api/metrics"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(
                        MediaType.parseMediaType(MetricsController.PROMETHEUS_CONTENT_TYPE)))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "connex_metrics_test_total 3.0")));
    }
}
