package ooo.klae.connex.backend.config;

import org.springframework.boot.jackson.autoconfigure.JsonFactoryBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import tools.jackson.core.StreamReadConstraints;

/**
 * Aligns Jackson's document bound with the largest route-specific API body limit.
 */
@Configuration(proxyBeanMethods = false)
public class JacksonRequestBodySizeConfiguration {

    @Bean
    JsonFactoryBuilderCustomizer requestBodyStreamReadConstraints(RequestBodySizeProperties properties) {
        long limitBytes = properties.getLargestBodyLimit();
        return builder -> builder.streamReadConstraints(StreamReadConstraints.defaults().rebuild()
            .maxDocumentLength(limitBytes)
            .build());
    }
}
