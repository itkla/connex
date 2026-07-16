package ooo.klae.connex.backend.mappers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import ooo.klae.connex.backend.storage.ObjectDeletionTask;

class UserObjectDeletionQueueMapperTest extends AbstractMapperTest {
    @Autowired UserObjectDeletionQueueMapper mapper;

    private final List<String> keys = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        for (String key : keys) {
            mapper.deleteByKey(key);
        }
    }

    @Test
    void countsOnlyTheRequestedUsersCanonicalPrefix() {
        int userId = ThreadLocalRandom.current().nextInt(1_000_000, 2_000_000);
        String first = key(userId, "550e8400-e29b-41d4-a716-446655440000.jpg");
        String second = key(userId, "550e8400-e29b-41d4-a716-446655440001.jpg");
        String other = key(userId + 1, "550e8400-e29b-41d4-a716-446655440002.jpg");
        enqueue(first);
        enqueue(second);
        enqueue(other);

        assertEquals(2, mapper.countPendingForPrefix(
            "users/" + userId + "/profile-images/"));
    }

    @Test
    void reschedulesAmbiguousCleanupForAConfirmationPass() {
        int userId = ThreadLocalRandom.current().nextInt(1_000_000, 2_000_000);
        String key = key(userId, "550e8400-e29b-41d4-a716-446655440003.jpg");
        LocalDateTime delayed = LocalDateTime.now().plusMinutes(1);
        LocalDateTime recheck = delayed.plusMinutes(1);
        keys.add(key);

        mapper.enqueue(key, 2, delayed);

        assertTrue(mapper.findDue(delayed.minusSeconds(1), 10).stream()
            .noneMatch(task -> task.objectKey().equals(key)));
        ObjectDeletionTask task = mapper.findDue(delayed.plusSeconds(1), 10).stream()
            .filter(candidate -> candidate.objectKey().equals(key))
            .findFirst()
            .orElseThrow();
        assertEquals(2, task.deletePassesRemaining());
        assertEquals(1, mapper.confirmDeletePass(task.id(), recheck));
        assertTrue(mapper.findDue(delayed.plusSeconds(1), 10).stream()
            .noneMatch(candidate -> candidate.objectKey().equals(key)));
    }

    @Test
    void staleSelectedIdentityCannotLockAReplacementTombstoneForTheSameKey() {
        int userId = ThreadLocalRandom.current().nextInt(1_000_000, 2_000_000);
        String key = key(userId, "550e8400-e29b-41d4-a716-446655440004.jpg");
        LocalDateTime now = LocalDateTime.of(2026, 7, 14, 12, 0);
        keys.add(key);
        mapper.enqueue(key, 1, now);
        ObjectDeletionTask selected = mapper.lockByKey(key);
        assertEquals(1, mapper.deleteByIdentity(selected.id(), key));

        mapper.enqueue(key, 2, now.plusMinutes(1));
        ObjectDeletionTask replacement = mapper.lockByKey(key);

        assertTrue(replacement.id() != selected.id());
        assertNull(mapper.lockDueByIdentity(selected.id(), key, now.plusMinutes(2)));
        assertEquals(replacement.id(), mapper.lockByIdentity(replacement.id(), key).id());
    }

    private void enqueue(String key) {
        keys.add(key);
        mapper.enqueue(key, 1, LocalDateTime.now());
    }

    private static String key(int userId, String token) {
        return "users/" + userId + "/profile-images/" + token;
    }
}
