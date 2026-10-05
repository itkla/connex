package ooo.klae.connex.backend.controllers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.lang.reflect.Method;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import ooo.klae.connex.backend.config.SequenceProperties;
import ooo.klae.connex.backend.services.SequencePreviewService;
import ooo.klae.connex.backend.services.SequenceService;
import ooo.klae.connex.backend.services.SequenceVersionService;
import ooo.klae.connex.backend.tenant.Permission;
import ooo.klae.connex.backend.tenant.RequirePermission;
import ooo.klae.connex.backend.tenant.TenantJournalAttributable;

class SequenceControllerTest {
    private final WebApplicationContextRunner contextRunner = new WebApplicationContextRunner()
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

    @Test
    void previewRouteIsNotFoundWhenTheFeatureGateIsOff() {
        contextRunner
            .withPropertyValues("connex.sequences.enabled=false", "spring.task.scheduling.enabled=false")
            .run(context -> {
                assertNull(context.getBeanProvider(SequenceController.class).getIfAvailable());
                MockMvc mockMvc = MockMvcBuilders.webAppContextSetup(context).build();

                mockMvc.perform(post("/api/sequences/41/versions/2/preview")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"personId\":73}"))
                    .andExpect(status().isNotFound());
            });
    }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableConfigurationProperties(SequenceProperties.class)
    @EnableWebMvc
    static class SequencePropertiesConfiguration {
    }
}
