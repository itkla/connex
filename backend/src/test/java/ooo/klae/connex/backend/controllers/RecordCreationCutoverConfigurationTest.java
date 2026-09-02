package ooo.klae.connex.backend.controllers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.TestConfiguration;

import ooo.klae.connex.backend.config.RecordCreationProperties;
import ooo.klae.connex.backend.services.CompanyService;
import ooo.klae.connex.backend.services.DealService;
import ooo.klae.connex.backend.services.GuidedRecordCreationService;
import ooo.klae.connex.backend.services.PersonService;

class RecordCreationCutoverConfigurationTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withBean(PersonService.class, () -> mock(PersonService.class))
        .withBean(CompanyService.class, () -> mock(CompanyService.class))
        .withBean(DealService.class, () -> mock(DealService.class))
        .withBean(GuidedRecordCreationService.class, () -> mock(GuidedRecordCreationService.class))
        .withUserConfiguration(
            CutoverPropertiesConfiguration.class,
            LegacyRecordCreationController.class,
            GuidedRecordCreationController.class);

    @Test
    void readinessDefaultsToLegacyOnly() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(LegacyRecordCreationController.class);
            assertThat(context).doesNotHaveBean(GuidedRecordCreationController.class);
        });
    }

    @Test
    void readinessTrueActivatesGuidedOnly() {
        contextRunner
            .withPropertyValues("connex.record-creation.guided-cutover-enabled=true")
            .run(context -> {
                assertThat(context).hasSingleBean(GuidedRecordCreationController.class);
                assertThat(context).doesNotHaveBean(LegacyRecordCreationController.class);
            });
    }

    @Test
    void malformedReadinessValuesFailStartup() {
        for (String value : new String[] {"treu", "", "1", "0"}) {
            contextRunner
                .withPropertyValues(
                    "connex.record-creation.guided-cutover-enabled=" + value)
                .run(context -> assertThat(context).hasFailed());
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableConfigurationProperties(RecordCreationProperties.class)
    static class CutoverPropertiesConfiguration {
    }
}
