package ooo.klae.connex.backend.config;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.config.TaskManagementConfigUtils;

import ooo.klae.connex.backend.notifications.NotificationPushListener;
import ooo.klae.connex.backend.notifications.SimpNotificationRealtimePublisher;
import ooo.klae.connex.backend.secrets.LegacySecretRewrapRunner;
import ooo.klae.connex.backend.secrets.SecretStoreRewrapRunner;
import ooo.klae.connex.backend.services.ClientErrorRetentionScheduler;

class BackgroundExecutionConfigurationTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withUserConfiguration(BackgroundExecutionConfiguration.class);

    @Test
    void normalModeEnablesScheduledAndAsyncProcessing() {
        contextRunner.withPropertyValues("connex.maintenance.mode=off").run(context -> {
            assertTrue(context.containsBean(
                TaskManagementConfigUtils.SCHEDULED_ANNOTATION_PROCESSOR_BEAN_NAME));
            assertTrue(context.containsBean(
                TaskManagementConfigUtils.ASYNC_ANNOTATION_PROCESSOR_BEAN_NAME));
        });
    }

    @Test
    void maintenanceModeDisablesScheduledAndAsyncProcessing() {
        for (String mode : List.of("legacy-upload-migration", "seeder")) {
            contextRunner.withPropertyValues("connex.maintenance.mode=" + mode)
                .run(context -> {
                    assertFalse(context.containsBean(
                        TaskManagementConfigUtils.SCHEDULED_ANNOTATION_PROCESSOR_BEAN_NAME));
                    assertFalse(context.containsBean(
                        TaskManagementConfigUtils.ASYNC_ANNOTATION_PROCESSOR_BEAN_NAME));
                });
        }
    }

    @Test
    void maintenanceModeDoesNotCreateRealtimeInfrastructure() {
        contextRunner
            .withUserConfiguration(
                WebSocketConfig.class,
                WebSocketSecurityConfig.class,
                NotificationPushListener.class,
                SimpNotificationRealtimePublisher.class)
            .withPropertyValues("connex.maintenance.mode=legacy-upload-migration")
            .run(context -> {
                assertFalse(context.containsBean("webSocketConfig"));
                assertFalse(context.containsBean("webSocketSecurityConfig"));
                assertFalse(context.containsBean("webSocketHeartbeatScheduler"));
                assertFalse(context.containsBean("notificationPushListener"));
                assertFalse(context.containsBean("simpNotificationRealtimePublisher"));
            });
    }

    @Test
    void maintenanceModeExcludesMutatingStartupRunners() {
        for (Class<?> type : List.of(
                BootstrapRunner.class,
                ClientErrorRetentionScheduler.class,
                LegacySecretRewrapRunner.class,
                SecretStoreRewrapRunner.class,
                NotificationPushListener.class,
                SimpNotificationRealtimePublisher.class,
                WebSocketConfig.class,
                WebSocketSecurityConfig.class)) {
            ConditionalOnProperty condition = type.getAnnotation(ConditionalOnProperty.class);
            assertNotNull(condition, type.getName());
            assertEquals("connex.maintenance", condition.prefix());
            assertArrayEquals(new String[] {"mode"}, condition.name());
            assertEquals("off", condition.havingValue());
            assertTrue(condition.matchIfMissing());
        }
    }
}
