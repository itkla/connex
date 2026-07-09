package ooo.klae.connex.backend.config;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.core.annotation.Order;
import org.springframework.mock.env.MockEnvironment;

import ooo.klae.connex.backend.mappers.SecretValueMapper;
import ooo.klae.connex.backend.secrets.SecretStore;
import ooo.klae.connex.backend.secrets.SecretStoreProperties;

class SecretStoreStartupValidatorTest {

    @Test
    void failsOutsideDevAndTestWhenSecretStoreKeyIsMissing() {
        SecretStore secretStore = mock(SecretStore.class);
        SecretValueMapper secretValueMapper = mock(SecretValueMapper.class);
        when(secretStore.isAvailable()).thenReturn(false);
        SecretStoreStartupValidator validator = new SecretStoreStartupValidator(secretStore,
                secretValueMapper, new MockEnvironment());

        assertThrows(IllegalStateException.class, () -> validator.run(null));
    }

    @Test
    void allowsDevProfileWithoutSecretStoreKey() {
        SecretStore secretStore = mock(SecretStore.class);
        SecretValueMapper secretValueMapper = mock(SecretValueMapper.class);
        when(secretStore.isAvailable()).thenReturn(false);
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("dev");
        SecretStoreStartupValidator validator = new SecretStoreStartupValidator(secretStore,
                secretValueMapper, environment);

        assertDoesNotThrow(() -> validator.run(null));
    }

    @Test
    void allowsConfiguredSecretStoreOutsideDev() {
        SecretStore secretStore = mock(SecretStore.class);
        SecretValueMapper secretValueMapper = mock(SecretValueMapper.class);
        when(secretStore.isAvailable()).thenReturn(true);
        when(secretValueMapper.listKeyIds()).thenReturn(List.of("prod-v1"));
        when(secretStore.hasKey("prod-v1")).thenReturn(true);
        SecretStoreStartupValidator validator = new SecretStoreStartupValidator(secretStore,
                secretValueMapper, new MockEnvironment());

        assertDoesNotThrow(() -> validator.run(null));
    }

    @Test
    void failsWhenStoredSecretKeyIdIsUnavailable() {
        SecretStore secretStore = mock(SecretStore.class);
        SecretValueMapper secretValueMapper = mock(SecretValueMapper.class);
        when(secretStore.isAvailable()).thenReturn(true);
        when(secretValueMapper.listKeyIds()).thenReturn(List.of("old-v1"));
        when(secretStore.hasKey("old-v1")).thenReturn(false);
        SecretStoreStartupValidator validator = new SecretStoreStartupValidator(secretStore,
                secretValueMapper, new MockEnvironment());

        assertThrows(IllegalStateException.class, () -> validator.run(null));
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
    void validatorRunsBeforeMutatingStartupRunners() {
        Order order = AnnotationUtils.findAnnotation(SecretStoreStartupValidator.class, Order.class);

        assertEquals(Ordered.HIGHEST_PRECEDENCE, order.value());
    }
}
