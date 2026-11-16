package ooo.klae.connex.backend.config;

import java.util.concurrent.ThreadPoolExecutor;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/** Keeps routed provisioning independent of Boot's optional default executor and other workers. */
@Configuration(proxyBeanMethods = false)
public class AiAgentPrivacyInitializationConfiguration {

    /**
     * Bounds provisioning concurrency and drains accepted work during shutdown. Rejection throws
     * instead of running on the AFTER_COMMIT caller; scheduled reconciliation discovers the workspace.
     * Maintenance provisioning also needs this executor, independently of AI feature flags.
     */
    @Bean
    public ThreadPoolTaskExecutor aiAgentPrivacyInitializationExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("ai-privacy-initialization-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        return executor;
    }
}
