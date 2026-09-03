package ooo.klae.connex.backend.controllers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.lang.reflect.Method;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import ooo.klae.connex.backend.config.SequenceProperties;
import ooo.klae.connex.backend.services.SequencePreviewService;
import ooo.klae.connex.backend.services.SequenceService;
import ooo.klae.connex.backend.services.SequenceVersionService;
import ooo.klae.connex.backend.tenant.Permission;
import ooo.klae.connex.backend.tenant.RequirePermission;
import ooo.klae.connex.backend.tenant.TenantJournalAttributable;

class SequenceControllerTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withBean(SequenceService.class, () -> mock(SequenceService.class))
        .withBean(SequenceVersionService.class, () -> mock(SequenceVersionService.class))
        .withBean(SequencePreviewService.class, () -> mock(SequencePreviewService.class))
        .withUserConfiguration(
            SequencePropertiesConfiguration.class,
            SequenceController.class);

    @Test
    void everyHandlerCarriesTheIntendedPermissionAndControllerIsFlagGated() {
        Map<String, Permission> expected = Map.of(
            "list", Permission.SEQUENCE_VIEW,
            "create", Permission.SEQUENCE_MANAGE,
            "get", Permission.SEQUENCE_VIEW,
            "update", Permission.SEQUENCE_MANAGE,
            "archive", Permission.SEQUENCE_MANAGE,
            "publish", Permission.SEQUENCE_MANAGE,
            "listVersions", Permission.SEQUENCE_VIEW,
            "getVersion", Permission.SEQUENCE_VIEW,
            "preview", Permission.SEQUENCE_VIEW,
            "mergeFields", Permission.SEQUENCE_VIEW);
        assertEquals(expected.size(), SequenceController.class.getDeclaredMethods().length);
        for (Method method : SequenceController.class.getDeclaredMethods()) {
            RequirePermission annotation = method.getAnnotation(RequirePermission.class);
            assertNotNull(annotation, method.getName());
            assertEquals(expected.get(method.getName()), annotation.value(), method.getName());
        }
        ConditionalOnProperty flag = SequenceController.class.getAnnotation(ConditionalOnProperty.class);
        assertNotNull(flag);
        assertEquals("connex.sequences", flag.prefix());
        assertEquals("enabled", flag.name()[0]);
        assertEquals("true", flag.havingValue());
        assertNotNull(SequenceController.class.getAnnotation(TenantJournalAttributable.class));
    }

    @Test
    void controllerBeanIsAbsentWhenTheReadinessPropertyIsUnset() {
        contextRunner.run(context -> assertThat(context)
            .doesNotHaveBean(SequenceController.class));
    }

    @Test
    void controllerBeanIsPresentOnlyWhenTheReadinessPropertyIsTrue() {
        contextRunner
            .withPropertyValues("connex.sequences.enabled=true")
            .run(context -> assertThat(context)
                .hasSingleBean(SequenceController.class));
    }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableConfigurationProperties(SequenceProperties.class)
    static class SequencePropertiesConfiguration {
    }
}
