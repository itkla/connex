package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.exceptions.TooManyRequestsException;

@ExtendWith(MockitoExtension.class)
class DuplicatePreflightRateLimiterTest {

    @Mock private WorkspaceService workspaceService;
    @Mock private AuthService authService;

    private DuplicatePreflightProperties properties;
    private User user;

    @BeforeEach
    void setUp() {
        properties = new DuplicatePreflightProperties();
        properties.setMaxRequestsPerMinute(2);
        properties.setMaxGlobalRequestsPerMinute(3);
        properties.setMaxRateLimitKeys(2);
        user = new User();
        user.setId(9);
        org.mockito.Mockito.lenient()
            .when(workspaceService.getCurrentWorkspaceId()).thenReturn(5);
        org.mockito.Mockito.lenient().when(authService.getCurrentUser()).thenReturn(user);
    }

    @Test
    void followsThePrincipalAcrossWorkspaces() {
        DuplicatePreflightRateLimiter limiter = limiterAt("2026-07-26T12:00:10Z");

        assertDoesNotThrow(() -> limiter.requireAllowed());
        assertDoesNotThrow(() -> limiter.requireAllowed());
        when(workspaceService.getCurrentWorkspaceId()).thenReturn(6);
        assertThrows(TooManyRequestsException.class, () -> limiter.requireAllowed());
    }

    @Test
    void preservesAGlobalBudgetAcrossPrincipals() {
        DuplicatePreflightRateLimiter limiter = limiterAt("2026-07-26T12:00:10Z");
        User second = new User();
        second.setId(10);

        assertDoesNotThrow(() -> limiter.requireAllowed());
        assertDoesNotThrow(() -> limiter.requireAllowed());
        when(authService.getCurrentUser()).thenReturn(second);
        assertDoesNotThrow(() -> limiter.requireAllowed());
        assertThrows(TooManyRequestsException.class, () -> limiter.requireAllowed());
    }

    @Test
    void weightedAdmissionsCannotHideBulkLookupWork() {
        DuplicatePreflightRateLimiter limiter = limiterAt("2026-07-26T12:00:10Z");

        assertDoesNotThrow(() -> limiter.requireAllowed(2));
        assertThrows(TooManyRequestsException.class, () -> limiter.requireAllowed(1));
        assertThrows(IllegalArgumentException.class, () -> limiter.requireAllowed(0));
    }

    @Test
    void resetsAtTheNextMinute() {
        Clock clock = org.mockito.Mockito.mock(Clock.class);
        when(clock.instant())
            .thenReturn(Instant.parse("2026-07-26T12:00:10Z"))
            .thenReturn(Instant.parse("2026-07-26T12:00:20Z"))
            .thenReturn(Instant.parse("2026-07-26T12:01:00Z"));
        DuplicatePreflightRateLimiter limiter = new DuplicatePreflightRateLimiter(
            properties, workspaceService, authService, clock);

        assertDoesNotThrow(() -> limiter.requireAllowed());
        assertDoesNotThrow(() -> limiter.requireAllowed());
        assertDoesNotThrow(() -> limiter.requireAllowed());
    }

    @Test
    void rejectsAnUnavailablePrincipal() {
        user.setId(0);

        assertThrows(
            IllegalStateException.class,
            () -> limiterAt("2026-07-26T12:00:10Z").requireAllowed());
    }

    private DuplicatePreflightRateLimiter limiterAt(String instant) {
        return new DuplicatePreflightRateLimiter(
            properties,
            workspaceService,
            authService,
            Clock.fixed(Instant.parse(instant), ZoneOffset.UTC));
    }
}
