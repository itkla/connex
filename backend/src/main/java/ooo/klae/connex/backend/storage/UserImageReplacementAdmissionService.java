package ooo.klae.connex.backend.storage;

import java.time.Clock;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.exceptions.ServiceUnavailableException;
import ooo.klae.connex.backend.exceptions.TooManyRequestsException;
import ooo.klae.connex.backend.mappers.UserObjectDeletionQueueMapper;

/**
 * Bounds per-user profile-image replacement traffic and deferred object backlog.
 */
@Service
@RequiredArgsConstructor
public class UserImageReplacementAdmissionService {
    private final UserObjectDeletionQueueMapper deletionQueueMapper;
    private final ObjectStorageProperties properties;
    private final Clock clock;
    private final Map<Integer, Window> windows = new LinkedHashMap<>(16, 0.75f, true);

    public void requireAllowed(int userId) {
        String prefix = "users/" + positive(userId) + "/profile-images/";
        if (deletionQueueMapper.countPendingForPrefix(prefix)
                >= properties.getMaxPendingUserImageDeletions()) {
            throw new ServiceUnavailableException(
                "Profile-image cleanup is pending; retry after storage recovers");
        }
        requireRateAllowed(userId);
    }

    private synchronized void requireRateAllowed(int userId) {
        long hour = clock.instant().getEpochSecond() / 3_600;
        Window current = windows.get(userId);
        if (current != null && current.hour() == hour) {
            if (current.replacements() >= properties.getMaxUserImageReplacementsPerHour()) {
                throw new TooManyRequestsException(
                    "Profile-image replacement limit reached; retry later");
            }
            windows.put(userId, new Window(hour, current.replacements() + 1));
            return;
        }
        windows.put(userId, new Window(hour, 1));
        trim();
    }

    private void trim() {
        Iterator<Integer> keys = windows.keySet().iterator();
        while (windows.size() > properties.getUserImageRateLimitMaxKeys() && keys.hasNext()) {
            keys.next();
            keys.remove();
        }
    }

    private static int positive(int value) {
        if (value <= 0) {
            throw new IllegalArgumentException("Profile-image owner id must be positive");
        }
        return value;
    }

    private record Window(long hour, int replacements) {}
}
