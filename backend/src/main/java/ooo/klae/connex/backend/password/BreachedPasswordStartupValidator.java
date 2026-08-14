package ooo.klae.connex.backend.password;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

/**
 * Fails startup for unsupported or unverifiable breached-password source configuration.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@RequiredArgsConstructor
public class BreachedPasswordStartupValidator implements ApplicationRunner {
    private final BreachedPasswordProperties properties;
    private final OfflineBreachedPasswordLookup offline;

    @Override
    public void run(ApplicationArguments args) {
        if (properties.sourceType() == BreachedPasswordSourceType.OFFLINE) {
            offline.validate();
        }
    }
}
