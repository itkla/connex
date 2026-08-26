package ooo.klae.connex.backend.password;

import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

/**
 * Routes every password screen to exactly one configured, non-disableable source.
 */
@Primary
@Component
@RequiredArgsConstructor
public class ConfiguredBreachedPasswordLookup implements BreachedPasswordLookup {
    private final BreachedPasswordProperties properties;
    private final HibpBreachedPasswordLookup remote;
    private final OfflineBreachedPasswordLookup offline;

    @Override
    public boolean isBreached(String sha1Hex) {
        return switch (properties.sourceType()) {
            case REMOTE -> remote.isBreached(sha1Hex);
            case OFFLINE -> offline.isBreached(sha1Hex);
        };
    }
}
