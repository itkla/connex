package ooo.klae.connex.backend.config;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.dto.SecretStoreDiagnosticsDto;
import ooo.klae.connex.backend.secrets.SecretStore;
import ooo.klae.connex.backend.secrets.SecretStoreLifecycleService;

/**
 * Fails deployed startup when the central integration-secret store is unavailable
 * and reports unusable stored-row key material for operator recovery.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@RequiredArgsConstructor
public class SecretStoreStartupValidator implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(SecretStoreStartupValidator.class);

    private final SecretStore secretStore;
    private final SecretStoreLifecycleService lifecycleService;
    private final Environment environment;

    @Override
    public void run(ApplicationArguments args) {
        if (secretStore.isAvailable()) {
            validateStoredKeys();
            return;
        }
        if (environment.acceptsProfiles(Profiles.of("dev", "test"))) {
            return;
        }
        throw new IllegalStateException("CONNEX_SECRET_STORE_MASTER_KEY must be set outside dev/test");
    }

    private void validateStoredKeys() {
        SecretStoreDiagnosticsDto diagnostics = lifecycleService.diagnostics();
        if (lifecycleService.hasBlockingFailures(diagnostics)) {
            log.error("Secret store key diagnostics found unavailable stored rows: missing="
                    + diagnostics.getMissingKeySecrets()
                    + " disabled=" + diagnostics.getDisabledKeySecrets()
                    + " mismatched=" + diagnostics.getMismatchedSecrets()
                    + " unsupported=" + diagnostics.getUnsupportedAlgorithmSecrets());
        }
        if (diagnostics.getStaleSecrets() > 0) {
            log.warn("Secret store has {} stale rows not yet rewrapped to active key {}",
                    diagnostics.getStaleSecrets(), diagnostics.getActiveKeyId());
        }
    }
}
