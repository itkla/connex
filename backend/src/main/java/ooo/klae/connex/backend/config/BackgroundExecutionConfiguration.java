package ooo.klae.connex.backend.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Enables scheduled and asynchronous application work outside isolated maintenance invocations.
 */
@Configuration(proxyBeanMethods = false)
@EnableAsync
@EnableScheduling
@ConditionalOnProperty(
    prefix = "connex.maintenance",
    name = "mode",
    havingValue = "off",
    matchIfMissing = true)
public class BackgroundExecutionConfiguration {
}
