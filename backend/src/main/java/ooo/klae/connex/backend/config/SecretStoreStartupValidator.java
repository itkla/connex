package ooo.klae.connex.backend.config;

import java.util.List;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.mappers.SecretValueMapper;
import ooo.klae.connex.backend.secrets.SecretStore;

/**
 * Fails deployed startup when the central integration-secret store has no key.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@RequiredArgsConstructor
public class SecretStoreStartupValidator implements ApplicationRunner {
    private final SecretStore secretStore;
    private final SecretValueMapper secretValueMapper;
    private final Environment environment;

    @Override
    public void run(ApplicationArguments args) {
        if (secretStore.isAvailable()) {
            validateStoredKeyIds();
            return;
        }
        if (environment.acceptsProfiles(Profiles.of("dev", "test"))) {
            return;
        }
        throw new IllegalStateException("CONNEX_SECRET_STORE_MASTER_KEY must be set outside dev/test");
    }

    private void validateStoredKeyIds() {
        List<String> missing = secretValueMapper.listKeyIds().stream()
                .filter(keyId -> !secretStore.hasKey(keyId))
                .toList();
        if (!missing.isEmpty()) {
            throw new IllegalStateException("Missing CONNEX_SECRET_STORE key ids: " + String.join(", ", missing));
        }
    }
}
