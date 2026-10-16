package ooo.klae.connex.backend.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/** Keeps reconciliation lock waits away from unrelated scheduled maintenance. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "connex.maintenance", name = "mode", havingValue = "off", matchIfMissing = true)
public class AiIdentifierKeyBackfillSchedulingConfig {
    /** A single fixed-delay worker cannot overlap pages or accumulate recurring reconciliation tasks. */
    @Bean
    public ThreadPoolTaskScheduler aiIdentifierKeyBackfillScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("ai-identifier-backfill-");
        return scheduler;
    }
}
