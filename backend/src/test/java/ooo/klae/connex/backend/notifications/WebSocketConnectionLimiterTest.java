package ooo.klae.connex.backend.notifications;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.security.Principal;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.WebSocketSession;

/**
 * Verifies the per-principal concurrent-connection cap: admits up to the limit,
 * rejects beyond it, frees a slot on close, scopes the count per principal, and
 * never caps an unauthenticated (principal-less) session.
 */
class WebSocketConnectionLimiterTest {

    private WebSocketConnectionLimiter limiter;

    @BeforeEach
    void setUp() {
        limiter = new WebSocketConnectionLimiter(2);
    }

    private WebSocketSession sessionFor(String principalName) {
        WebSocketSession session = mock(WebSocketSession.class);
        Principal principal = mock(Principal.class);
        lenient().when(principal.getName()).thenReturn(principalName);
        lenient().when(session.getPrincipal()).thenReturn(principal);
        return session;
    }

    @Test
    void admitsUpToLimitThenRejects() {
        assertTrue(limiter.tryRegister(sessionFor("alice")));
        assertTrue(limiter.tryRegister(sessionFor("alice")));
        assertFalse(limiter.tryRegister(sessionFor("alice")));
    }

    @Test
    void removeFreesSlot() {
        WebSocketSession first = sessionFor("alice");
        limiter.tryRegister(first);
        limiter.tryRegister(sessionFor("alice"));
        assertFalse(limiter.tryRegister(sessionFor("alice")));

        limiter.remove(first);

        assertTrue(limiter.tryRegister(sessionFor("alice")));
    }

    @Test
    void limitIsPerPrincipal() {
        limiter.tryRegister(sessionFor("alice"));
        limiter.tryRegister(sessionFor("alice"));

        assertTrue(limiter.tryRegister(sessionFor("bob")));
    }

    @Test
    void nullPrincipalIsNeverCapped() {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getPrincipal()).thenReturn(null);

        assertTrue(limiter.tryRegister(session));
        assertTrue(limiter.tryRegister(session));
        assertTrue(limiter.tryRegister(session));
    }

    @Test
    void rejectsNonPositiveLimit() {
        assertThrows(IllegalArgumentException.class, () -> new WebSocketConnectionLimiter(0));
        assertThrows(IllegalArgumentException.class, () -> new WebSocketConnectionLimiter(-1));
    }

    @Test
    void removeOfUnregisteredSessionIsNoop() {
        limiter.remove(sessionFor("alice"));

        assertTrue(limiter.tryRegister(sessionFor("alice")));
        assertTrue(limiter.tryRegister(sessionFor("alice")));
        assertFalse(limiter.tryRegister(sessionFor("alice")));
    }
}
