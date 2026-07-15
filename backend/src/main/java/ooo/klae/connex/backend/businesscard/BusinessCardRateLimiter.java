package ooo.klae.connex.backend.businesscard;

import java.time.Clock;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.exceptions.TooManyRequestsException;
import ooo.klae.connex.backend.services.AuthService;
import ooo.klae.connex.backend.services.WorkspaceService;

/**
 * Bounded, per-user/workspace fixed-window admission control for card processing.
 */
@Component
@RequiredArgsConstructor
public class BusinessCardRateLimiter {
    private final BusinessCardProperties properties;
    private final WorkspaceService workspaceService;
    private final AuthService authService;
    private final Clock clock;
    private final Map<RateKey, Window> windows = new LinkedHashMap<>(16, 0.75f, true);

    public void requireScanAllowed() {
        requireAllowed(Operation.SCAN, properties.getMaxScansPerMinute());
    }

    public void requireImportAllowed() {
        requireAllowed(Operation.IMPORT, properties.getMaxImportsPerMinute());
    }

    private synchronized void requireAllowed(Operation operation, int limit) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        User user = authService.getCurrentUser();
        if (user == null || user.getId() <= 0 || workspaceId <= 0) {
            throw new IllegalStateException("Business-card rate-limit identity is unavailable");
        }
        long minute = clock.instant().getEpochSecond() / 60;
        RateKey key = new RateKey(workspaceId, user.getId(), operation);
        Window current = windows.get(key);
        if (current != null && current.minute() == minute) {
            if (current.requests() >= limit) {
                throw new TooManyRequestsException(
                        "Business-card request limit reached; retry shortly");
            }
            windows.put(key, new Window(minute, current.requests() + 1));
            return;
        }
        windows.put(key, new Window(minute, 1));
        trim();
    }

    private void trim() {
        int maximumKeys = properties.getRateLimitMaxKeys();
        Iterator<RateKey> keys = windows.keySet().iterator();
        while (windows.size() > maximumKeys && keys.hasNext()) {
            keys.next();
            keys.remove();
        }
    }

    private enum Operation {
        SCAN,
        IMPORT
    }

    private record RateKey(int workspaceId, int userId, Operation operation) {}

    private record Window(long minute, int requests) {}
}
