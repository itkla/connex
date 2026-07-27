package ooo.klae.connex.backend.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import ooo.klae.connex.backend.observability.ErrorReporter;
import ooo.klae.connex.backend.observability.LoggingErrorReporter;

/**
 * Replaceable observability integration points.
 */
@Configuration(proxyBeanMethods = false)
public class ObservabilityConfiguration {

    /**
     * Supplies the structured logging fallback when no vendor reporter is configured.
     *
     * @return the fallback error reporter
     */
    @Bean
    @ConditionalOnMissingBean(ErrorReporter.class)
    ErrorReporter errorReporter() {
        return new LoggingErrorReporter();
    }
}
