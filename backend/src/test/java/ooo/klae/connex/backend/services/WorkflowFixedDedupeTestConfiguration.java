package ooo.klae.connex.backend.services;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

@TestConfiguration(proxyBeanMethods = false)
class WorkflowFixedDedupeTestConfiguration {

    @Bean
    @Primary
    WorkflowDedupeKey fixedWorkflowDedupeKey() {
        return new WorkflowDedupeKey(Clock.fixed(
            Instant.parse("2026-08-03T12:00:00Z"), ZoneOffset.UTC));
    }
}
