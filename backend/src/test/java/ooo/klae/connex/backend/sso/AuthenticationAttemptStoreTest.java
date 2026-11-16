package ooo.klae.connex.backend.sso;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;

import ooo.klae.connex.backend.util.OneTimeTokenDigest;

/** Exercises shared session persistence through detached per-request session handles. */
class AuthenticationAttemptStoreTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-06T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void eightConcurrentRequestHandlesPersistAtMostFourCompleteAttempts() throws Exception {
        var repository = new OAuthAuthenticationAttemptRepository(CLOCK);
        var seed = new MockHttpServletRequest();
        for (int index = 0; index < 3; index++) {
            save(repository, seed, index);
        }
        ConcurrentMap<String, Object> shared = new ConcurrentHashMap<>();
        Collections.list(seed.getSession().getAttributeNames())
                .forEach(name -> shared.put(name, seed.getSession().getAttribute(name)));
        var barrier = new CyclicBarrier(8);
        try (var executor = Executors.newFixedThreadPool(8)) {
            List<Future<?>> saves = new ArrayList<>();
            for (int index = 3; index < 11; index++) {
                int identity = index;
                var request = new MockHttpServletRequest();
                request.setSession(new SharedSessionHandle(shared, barrier));
                saves.add(executor.submit(() -> save(repository, request, identity)));
            }
            for (Future<?> save : saves) save.get(15, TimeUnit.SECONDS);
        }

        assertEquals(1, shared.size());
        Map<?, ?> persisted = assertInstanceOf(Map.class, shared.values().iterator().next());
        assertEquals(4, persisted.size());
        assertThrows(UnsupportedOperationException.class, persisted::clear);
        var restored = new MockHttpSession();
        shared.forEach(restored::setAttribute);
        int consumed = 0;
        for (int index = 0; index < 11; index++) {
            var callback = new MockHttpServletRequest("GET", "/api/login/oauth2/code/google");
            callback.setSession(restored);
            callback.addParameter("state", "state-" + index);
            var authorization = repository.removeAuthorizationRequest(callback, new MockHttpServletResponse());
            if (authorization == null) {
                assertNull(AuthenticationReturnPathSession.captured(callback));
            } else {
                consumed++;
                assertEquals("state-" + index, authorization.getState());
                assertEquals("/invite?attempt=" + index, AuthenticationReturnPathSession.captured(callback));
            }
        }
        assertEquals(4, consumed);
        Map<?, ?> empty = assertInstanceOf(Map.class, restored.getAttribute(shared.keySet().iterator().next()));
        assertTrue(empty.isEmpty());
    }

    @Test
    void allocationGuardAlsoCoversStartsOutsideCanonicalRoutesBeforeSessionCreation() {
        var request = new MockHttpServletRequest("GET", "/resource-requiring-oauth");
        request.addHeader("Sec-Fetch-Site", "cross-site");
        request.addHeader("Sec-Fetch-Mode", "no-cors");
        request.addHeader("Sec-Fetch-Dest", "image");
        var repository = new OAuthAuthenticationAttemptRepository(CLOCK);
        assertThrows(IllegalStateException.class, () -> save(repository, request, 0));
        assertNull(request.getSession(false));
    }

    @Test
    void previousPerAttemptAttributesAreIgnoredAndInvalidationClearsNewState() {
        var request = new MockHttpServletRequest();
        request.getSession().setAttribute(AuthenticationAttemptStore.class.getName() + ".attempt.oauth2."
                + OneTimeTokenDigest.sha256("legacy-state"), new AuthenticationAttemptStore.Attempt("legacy-state",
                SsoAttemptRepositoryTest.oauth("google", "legacy-state"), "/settings/personal/security", CLOCK.millis() + 600_000));
        var repository = new OAuthAuthenticationAttemptRepository(CLOCK);
        var callback = new MockHttpServletRequest("GET", "/api/login/oauth2/code/google");
        callback.setSession(request.getSession());
        callback.addParameter("state", "legacy-state");
        assertNull(repository.removeAuthorizationRequest(callback, new MockHttpServletResponse()));
        assertNull(AuthenticationReturnPathSession.captured(callback));

        save(repository, request, 0);
        callback.setParameter("state", "state-0");
        assertNotNull(repository.loadAuthorizationRequest(callback));
        request.getSession().invalidate();
        callback.setSession(new MockHttpSession());
        assertNull(repository.removeAuthorizationRequest(callback, new MockHttpServletResponse()));
        assertNull(AuthenticationReturnPathSession.captured(callback));
    }

    private static void save(OAuthAuthenticationAttemptRepository repository, MockHttpServletRequest request, int identity) {
        request.setParameter("redirect", "/invite?attempt=" + identity);
        AuthenticationReturnPathSession.prepareStart(request);
        repository.saveAuthorizationRequest(SsoAttemptRepositoryTest.oauth("google", "state-" + identity),
                request, new MockHttpServletResponse());
    }

    /** Models distinct Spring Session request wrappers persisting deltas to the same backing session. */
    private static final class SharedSessionHandle extends MockHttpSession {
        private final ConcurrentMap<String, Object> shared;
        private final CyclicBarrier barrier;
        private final AtomicBoolean firstRead = new AtomicBoolean(true);

        private SharedSessionHandle(ConcurrentMap<String, Object> shared, CyclicBarrier barrier) {
            super(new org.springframework.mock.web.MockServletContext(), "shared-session");
            this.shared = shared;
            this.barrier = barrier;
        }

        @Override
        public Object getAttribute(String name) {
            Object snapshot = shared.get(name);
            awaitOtherSnapshots();
            return snapshot;
        }

        @Override
        public Enumeration<String> getAttributeNames() {
            var names = List.copyOf(shared.keySet());
            awaitOtherSnapshots();
            return Collections.enumeration(names);
        }

        @Override
        public void setAttribute(String name, Object value) {
            if (value instanceof Map<?, ?> attempts) assertTrue(attempts.size() <= 4);
            shared.put(name, value);
        }

        @Override
        public void removeAttribute(String name) {
            shared.remove(name);
        }

        private void awaitOtherSnapshots() {
            if (!firstRead.compareAndSet(true, false)) return;
            try {
                barrier.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Concurrent request probe interrupted", exception);
            } catch (BrokenBarrierException | TimeoutException exception) {
                throw new IllegalStateException("Concurrent request snapshots did not overlap", exception);
            }
        }
    }
}
