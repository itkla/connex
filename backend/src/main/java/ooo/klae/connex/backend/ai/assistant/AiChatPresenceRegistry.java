package ooo.klae.connex.backend.ai.assistant;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.exceptions.TooManyRequestsException;

/** Bounded single-node registry for ephemeral assistant-session presence and typing state. */
@Component
@RequiredArgsConstructor
public class AiChatPresenceRegistry {
    private static final Duration PRESENCE_TTL = Duration.ofSeconds(45);
    private static final Duration TYPING_TTL = Duration.ofSeconds(6);
    private static final int MAX_SESSIONS = 10_000;
    private static final int MAX_PARTICIPANTS_PER_SESSION = 100;

    private final Clock clock;
    private final Map<SessionKey, LinkedHashMap<Integer, PresenceState>> sessions =
            new LinkedHashMap<>(16, 0.75f, true);

    /** Records one authorized heartbeat and returns the current live session snapshot. */
    public synchronized Snapshot touch(
            int workspaceId, int sessionId, int userId, boolean typing) {
        Instant now = clock.instant();
        purgeExpired(now);
        SessionKey key = new SessionKey(workspaceId, sessionId);
        LinkedHashMap<Integer, PresenceState> participants = sessions.get(key);
        if (participants == null) {
            if (sessions.size() >= MAX_SESSIONS) {
                throw new TooManyRequestsException("Assistant presence capacity is exhausted");
            }
            participants = new LinkedHashMap<>();
            sessions.put(key, participants);
        }
        if (!participants.containsKey(userId)
                && participants.size() >= MAX_PARTICIPANTS_PER_SESSION) {
            throw new TooManyRequestsException("Assistant session presence capacity is exhausted");
        }
        participants.put(userId, new PresenceState(
                now.plus(PRESENCE_TTL), typing ? now.plus(TYPING_TTL) : Instant.EPOCH));
        return snapshot(participants, now);
    }

    /** Returns the current live snapshot after expiring stale heartbeats. */
    public synchronized Snapshot snapshot(int workspaceId, int sessionId) {
        Instant now = clock.instant();
        purgeExpired(now);
        LinkedHashMap<Integer, PresenceState> participants =
                sessions.get(new SessionKey(workspaceId, sessionId));
        return participants == null ? Snapshot.empty() : snapshot(participants, now);
    }

    /** Removes one participant's ephemeral state immediately. */
    public synchronized void remove(int workspaceId, int sessionId, int userId) {
        SessionKey key = new SessionKey(workspaceId, sessionId);
        LinkedHashMap<Integer, PresenceState> participants = sessions.get(key);
        if (participants == null) {
            return;
        }
        participants.remove(userId);
        if (participants.isEmpty()) {
            sessions.remove(key);
        }
    }

    /** Clears all ephemeral state for a session that is no longer shared. */
    public synchronized void clear(int workspaceId, int sessionId) {
        sessions.remove(new SessionKey(workspaceId, sessionId));
    }

    private void purgeExpired(Instant now) {
        sessions.entrySet().removeIf(entry -> {
            entry.getValue().entrySet().removeIf(
                    participant -> !participant.getValue().presentUntil().isAfter(now));
            return entry.getValue().isEmpty();
        });
    }

    private static Snapshot snapshot(
            LinkedHashMap<Integer, PresenceState> participants, Instant now) {
        Set<Integer> present = new LinkedHashSet<>();
        Set<Integer> typing = new LinkedHashSet<>();
        participants.forEach((userId, state) -> {
            if (state.presentUntil().isAfter(now)) {
                present.add(userId);
            }
            if (state.typingUntil().isAfter(now)) {
                typing.add(userId);
            }
        });
        return new Snapshot(Set.copyOf(present), Set.copyOf(typing));
    }

    /** Immutable live user-id snapshot from the bounded registry. */
    public record Snapshot(Set<Integer> presentUserIds, Set<Integer> typingUserIds) {
        public Snapshot {
            presentUserIds = Set.copyOf(presentUserIds);
            typingUserIds = Set.copyOf(typingUserIds);
        }

        private static Snapshot empty() {
            return new Snapshot(Set.of(), Set.of());
        }
    }

    private record SessionKey(int workspaceId, int sessionId) {
    }

    private record PresenceState(Instant presentUntil, Instant typingUntil) {
    }
}
