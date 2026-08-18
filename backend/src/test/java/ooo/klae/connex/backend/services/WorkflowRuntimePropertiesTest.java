package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.io.ClassPathResource;

class WorkflowRuntimePropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withInitializer(context -> context.getBeanFactory()
            .setConversionService(ApplicationConversionService.getSharedInstance()))
        .withConfiguration(AutoConfigurations.of(PropertyPlaceholderAutoConfiguration.class))
        .withUserConfiguration(WorkflowRuntimeProperties.class);

    @Test
    void canonicalRuntimeRequiresExplicitOperatorOptIn() throws IOException {
        ClassPathResource applicationConfig = new ClassPathResource("application.yml");
        String yaml = new String(
            applicationConfig.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

        contextRunner.run(context ->
            assertFalse(context.getBean(WorkflowRuntimeProperties.class).enabled()));
        contextRunner
            .withPropertyValues("connex.workflows.runtime.enabled=true")
            .run(context ->
                assertTrue(context.getBean(WorkflowRuntimeProperties.class).enabled()));
        assertTrue(yaml.contains(
            "enabled: ${CONNEX_WORKFLOWS_RUNTIME_ENABLED:false}"));
    }
}
