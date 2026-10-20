package ooo.klae.connex.backend.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.servlet.config.annotation.AsyncSupportConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import ooo.klae.connex.backend.storage.ObjectStorageProperties;

/**
 * Provides bounded asynchronous execution and a hard lifecycle timeout for streamed objects.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnBean(ObjectStorageProperties.class)
public class ManagedContentAsyncConfiguration implements WebMvcConfigurer {
    private final ObjectStorageProperties properties;
    private final ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();

    public ManagedContentAsyncConfiguration(ObjectStorageProperties properties) {
        this.properties = properties;
    }

    @PostConstruct
    void initialize() {
        int concurrency = properties.getMaxConcurrentReads();
        executor.setCorePoolSize(concurrency);
        executor.setMaxPoolSize(concurrency);
        executor.setQueueCapacity(concurrency);
        executor.setThreadNamePrefix("managed-content-");
        executor.setWaitForTasksToCompleteOnShutdown(false);
        executor.initialize();
    }

    @PreDestroy
    void shutdown() {
        executor.shutdown();
    }

    @Override
    public void configureAsyncSupport(AsyncSupportConfigurer configurer) {
        configurer.setTaskExecutor(executor);
        configurer.setDefaultTimeout(properties.getReadTimeoutMs());
    }
}
