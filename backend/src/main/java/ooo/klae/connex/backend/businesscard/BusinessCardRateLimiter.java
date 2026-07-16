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
 * Bounded global, principal, and workspace fixed-window admission for card processing.
 */
@Component
@RequiredArgsConstructor
public class BusinessCardRateLimiter {
    private final BusinessCardProperties properties;
    private final WorkspaceService workspaceService;
    private final AuthService authService;
    private final Clock clock;
    private final Map<RateKey, Window> windows = new LinkedHashMap<>(16, 0.75f, true);
    private Window globalScanWindow;

    public synchronized void requireScanAllowed() {
        Identity identity = identity();
        long minute = minute();
        Window global = current(globalScanWindow, minute);
        RateKey principalKey = new RateKey(0, identity.userId(), Operation.SCAN);
        Window principal = current(windows.get(principalKey), minute);
        if (global.requests() >= properties.getMaxGlobalScansPerMinute()
                || principal.requests() >= properties.getMaxScansPerMinute()) {
            throw limitReached();
        }
        globalScanWindow = new Window(minute, global.requests() + 1);
        windows.put(principalKey, new Window(minute, principal.requests() + 1));
        trim();
    }

    public void requireImportAllowed() {
        requireAllowed(Operation.IMPORT, properties.getMaxImportsPerMinute());
    }

    public void requireReservationAllowed() {
        requireAllowed(Operation.RESERVATION, properties.getMaxImportsPerMinute());
    }

    private synchronized void requireAllowed(Operation operation, int limit) {
        Identity identity = identity();
        long minute = minute();
        RateKey key = new RateKey(identity.workspaceId(), identity.userId(), operation);
        Window current = current(windows.get(key), minute);
        if (current.requests() >= limit) {
            throw limitReached();
        }
        windows.put(key, new Window(minute, current.requests() + 1));
        trim();
    }

    private Identity identity() {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        User user = authService.getCurrentUser();
        if (user == null || user.getId() <= 0 || workspaceId <= 0) {
            throw new IllegalStateException("Business-card rate-limit identity is unavailable");
        }
        return new Identity(workspaceId, user.getId());
    }

    private long minute() {
        return clock.instant().getEpochSecond() / 60;
    }

    private static Window current(Window candidate, long minute) {
        return candidate != null && candidate.minute() == minute
            ? candidate
            : new Window(minute, 0);
    }

    private static TooManyRequestsException limitReached() {
        return new TooManyRequestsException(
                "Business-card request limit reached; retry shortly");
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
        IMPORT,
        RESERVATION
    }

    private record RateKey(int workspaceId, int userId, Operation operation) {}

    private record Identity(int workspaceId, int userId) {}

    private record Window(long minute, int requests) {}
}
