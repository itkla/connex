package ooo.klae.connex.backend.config;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.core.annotation.Order;
import org.springframework.mock.env.MockEnvironment;

import ooo.klae.connex.backend.dto.SecretStoreDiagnosticsDto;
import ooo.klae.connex.backend.secrets.SecretStore;
import ooo.klae.connex.backend.secrets.SecretStoreLifecycleService;
import ooo.klae.connex.backend.secrets.SecretStoreProperties;

class SecretStoreStartupValidatorTest {

    @Test
    void failsOutsideDevAndTestWhenSecretStoreKeyIsMissing() {
        SecretStore secretStore = mock(SecretStore.class);
        SecretStoreLifecycleService lifecycleService = mock(SecretStoreLifecycleService.class);
        when(secretStore.isAvailable()).thenReturn(false);
        SecretStoreStartupValidator validator = new SecretStoreStartupValidator(secretStore,
                lifecycleService, new MockEnvironment());

        assertThrows(IllegalStateException.class, () -> validator.run(null));
    }

    @Test
    void allowsDevProfileWithoutSecretStoreKey() {
        SecretStore secretStore = mock(SecretStore.class);
        SecretStoreLifecycleService lifecycleService = mock(SecretStoreLifecycleService.class);
        when(secretStore.isAvailable()).thenReturn(false);
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("dev");
        SecretStoreStartupValidator validator = new SecretStoreStartupValidator(secretStore,
                lifecycleService, environment);

        assertDoesNotThrow(() -> validator.run(null));
    }

    @Test
    void allowsConfiguredSecretStoreOutsideDev() {
        SecretStore secretStore = mock(SecretStore.class);
        SecretStoreLifecycleService lifecycleService = mock(SecretStoreLifecycleService.class);
        SecretStoreDiagnosticsDto diagnostics = new SecretStoreDiagnosticsDto();
        diagnostics.setAvailable(true);
        diagnostics.setHealthy(true);
        when(secretStore.isAvailable()).thenReturn(true);
        when(lifecycleService.diagnostics()).thenReturn(diagnostics);
        when(lifecycleService.hasBlockingFailures(diagnostics)).thenReturn(false);
        SecretStoreStartupValidator validator = new SecretStoreStartupValidator(secretStore,
                lifecycleService, new MockEnvironment());

        assertDoesNotThrow(() -> validator.run(null));
    }

    @Test
    void allowsConfiguredStoreToBootWhenStoredSecretKeyIdIsUnavailable() {
        SecretStore secretStore = mock(SecretStore.class);
        SecretStoreLifecycleService lifecycleService = mock(SecretStoreLifecycleService.class);
        SecretStoreDiagnosticsDto diagnostics = new SecretStoreDiagnosticsDto();
        diagnostics.setAvailable(true);
        diagnostics.setMissingKeySecrets(1);
        when(secretStore.isAvailable()).thenReturn(true);
        when(lifecycleService.diagnostics()).thenReturn(diagnostics);
        when(lifecycleService.hasBlockingFailures(diagnostics)).thenReturn(true);
        SecretStoreStartupValidator validator = new SecretStoreStartupValidator(secretStore,
                lifecycleService, new MockEnvironment());

        assertDoesNotThrow(() -> validator.run(null));
    }

    @Test
    void bindsSecretStoreProperties() {
        MockEnvironment environment = new MockEnvironment()
            .withProperty("connex.secret-store.key-id", "prod-v2")
            .withProperty("connex.secret-store.master-key", "bound-secret-store-key");

        SecretStoreProperties properties = Binder.get(environment)
            .bind("connex.secret-store", Bindable.of(SecretStoreProperties.class))
            .orElseThrow(() -> new AssertionError("Secret store properties did not bind"));

        assertEquals("prod-v2", properties.getKeyId());
        assertEquals("bound-secret-store-key", properties.getMasterKey());
    }

    @Test
    void bindsSecretStoreKeyringProperties() {
        MockEnvironment environment = new MockEnvironment()
            .withProperty("connex.secret-store.keys.old-v1", "old-key")
            .withProperty("connex.secret-store.keys.prod-v2", "new-key");

        SecretStoreProperties properties = Binder.get(environment)
            .bind("connex.secret-store", Bindable.of(SecretStoreProperties.class))
            .orElseThrow(() -> new AssertionError("Secret store properties did not bind"));

        assertEquals("old-key", properties.getKeys().get("old-v1"));
        assertEquals("new-key", properties.getKeys().get("prod-v2"));
    }

    @Test
    void bindsSecretStoreLifecycleProperties() {
        MockEnvironment environment = new MockEnvironment()
            .withProperty("connex.secret-store.disabled-key-ids", "old-v1,compromised-v2")
            .withProperty("connex.secret-store.lazy-rewrap-enabled", "false")
            .withProperty("connex.secret-store.batch-rewrap-on-startup", "true")
            .withProperty("connex.secret-store.batch-rewrap-limit", "25")
            .withProperty("connex.secret-store.metadata.prod-v2.version", "2")
            .withProperty("connex.secret-store.metadata.prod-v2.owner", "security")
            .withProperty("connex.secret-store.metadata.prod-v2.scope", "instance")
            .withProperty("connex.secret-store.metadata.prod-v2.created-at", "2026-07-09T00:00:00Z");

        SecretStoreProperties properties = Binder.get(environment)
            .bind("connex.secret-store", Bindable.of(SecretStoreProperties.class))
            .orElseThrow(() -> new AssertionError("Secret store properties did not bind"));

        assertEquals(2, properties.getDisabledKeyIds().size());
        assertEquals(false, properties.isLazyRewrapEnabled());
        assertEquals(true, properties.isBatchRewrapOnStartup());
        assertEquals(25, properties.getBatchRewrapLimit());
        assertEquals("2", properties.getMetadata().get("prod-v2").getVersion());
        assertEquals("security", properties.getMetadata().get("prod-v2").getOwner());
    }

    @Test
    void validatorRunsBeforeMutatingStartupRunners() {
        Order order = AnnotationUtils.findAnnotation(SecretStoreStartupValidator.class, Order.class);

        assertEquals(Ordered.HIGHEST_PRECEDENCE, order.value());
    }
}
