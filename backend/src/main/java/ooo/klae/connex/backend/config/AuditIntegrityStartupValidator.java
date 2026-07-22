package ooo.klae.connex.backend.config;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

/**
 * Fails deployed startup when audit-log integrity hashing is not configured.
 */
@Component
@RequiredArgsConstructor
public class AuditIntegrityStartupValidator implements ApplicationRunner {

    private final AuditIntegrityProperties properties;
    private final Environment environment;

    @Override
    public void run(ApplicationArguments args) {
        if (properties.hasValidHmacSecret()) {
            return;
        }
        if (environment.acceptsProfiles(Profiles.of("dev", "test"))) {
            return;
        }
        throw new IllegalStateException("CONNEX_AUDIT_INTEGRITY_HMAC_SECRET must be set outside dev/test");
    }
}
