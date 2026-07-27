package ooo.klae.connex.backend.services;

import java.time.Clock;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.exceptions.TooManyRequestsException;

/**
 * Bounded global and authenticated-principal fixed-window admission for duplicate probing.
 *
 * <p>One admission may consume multiple work units, allowing a maximum-size CSV import to be
 * bounded by its candidate values instead of masquerading as one interactive request.
 */
@Component
@RequiredArgsConstructor
public class DuplicatePreflightRateLimiter {

    private final DuplicatePreflightProperties properties;
    private final WorkspaceService workspaceService;
    private final AuthService authService;
    private final Clock clock;
    private final Map<RateKey, Window> windows = new LinkedHashMap<>(16, 0.75f, true);
    private Window globalWindow;

    /**
     * Consumes one preflight work unit for the active workspace principal.
     */
    public synchronized void requireAllowed() {
        requireAllowed(1);
    }

    /**
     * Consumes a positive number of preflight work units for the active workspace principal.
     *
     * @param workUnits normalized lookup work represented by the request
     */
    public synchronized void requireAllowed(int workUnits) {
        if (workUnits < 1) {
            throw new IllegalArgumentException("Duplicate-preflight work units must be positive");
        }
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        User user = authService.getCurrentUser();
        if (workspaceId <= 0 || user == null || user.getId() <= 0) {
            throw new IllegalStateException("Duplicate-preflight principal is unavailable");
        }
        long minute = clock.instant().getEpochSecond() / 60;
        Window global = current(globalWindow, minute);
        RateKey key = new RateKey(user.getId());
        Window principal = current(windows.get(key), minute);
        if (workUnits > properties.getMaxGlobalRequestsPerMinute() - global.requests()
                || workUnits > properties.getMaxRequestsPerMinute() - principal.requests()) {
            throw new TooManyRequestsException(
                "Duplicate-preflight request limit reached; retry shortly");
        }
        globalWindow = new Window(minute, global.requests() + workUnits);
        windows.put(key, new Window(minute, principal.requests() + workUnits));
        trim();
    }

    private static Window current(Window candidate, long minute) {
        return candidate != null && candidate.minute() == minute
            ? candidate
            : new Window(minute, 0);
    }

    private void trim() {
        Iterator<RateKey> keys = windows.keySet().iterator();
        while (windows.size() > properties.getMaxRateLimitKeys() && keys.hasNext()) {
            keys.next();
            keys.remove();
        }
    }

    private record RateKey(int userId) {
    }

    private record Window(long minute, int requests) {
    }
}
