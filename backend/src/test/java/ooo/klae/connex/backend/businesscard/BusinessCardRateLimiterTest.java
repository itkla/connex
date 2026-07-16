package ooo.klae.connex.backend.businesscard;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verifyNoInteractions;

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
import ooo.klae.connex.backend.services.AuthService;
import ooo.klae.connex.backend.services.WorkspaceService;

@ExtendWith(MockitoExtension.class)
class BusinessCardRateLimiterTest {
    @Mock private WorkspaceService workspaceService;
    @Mock private AuthService authService;

    private BusinessCardProperties properties;

    @BeforeEach
    void setUp() {
        properties = new BusinessCardProperties();
        properties.setMaxScansPerMinute(2);
        properties.setMaxGlobalScansPerMinute(3);
        properties.setMaxImportsPerMinute(1);
        properties.setRateLimitMaxKeys(3);
        User user = new User();
        user.setId(9);
        org.mockito.Mockito.lenient()
            .when(workspaceService.getCurrentWorkspaceId()).thenReturn(5);
        org.mockito.Mockito.lenient().when(authService.getCurrentUser()).thenReturn(user);
    }

    @Test
    void limitsScansPerAuthenticatedPrincipal() {
        BusinessCardRateLimiter limiter = limiterAt("2026-07-14T12:00:10Z");

        assertDoesNotThrow(limiter::requireScanAllowed);
        assertDoesNotThrow(limiter::requireScanAllowed);
        assertThrows(TooManyRequestsException.class, limiter::requireScanAllowed);
    }

    @Test
    void scanLimitFollowsThePrincipalAcrossWorkspaces() {
        BusinessCardRateLimiter limiter = limiterAt("2026-07-14T12:00:10Z");

        assertDoesNotThrow(limiter::requireScanAllowed);
        assertDoesNotThrow(limiter::requireScanAllowed);
        when(workspaceService.getCurrentWorkspaceId()).thenReturn(6);
        assertThrows(TooManyRequestsException.class, limiter::requireScanAllowed);
    }

    @Test
    void globalScanBudgetPreservesCapacityAcrossPrincipals() {
        BusinessCardRateLimiter limiter = limiterAt("2026-07-14T12:00:10Z");
        User second = new User();
        second.setId(10);

        assertDoesNotThrow(limiter::requireScanAllowed);
        assertDoesNotThrow(limiter::requireScanAllowed);
        when(authService.getCurrentUser()).thenReturn(second);
        assertDoesNotThrow(limiter::requireScanAllowed);
        assertThrows(TooManyRequestsException.class, limiter::requireScanAllowed);
    }

    @Test
    void scanAndImportBudgetsAreIndependent() {
        BusinessCardRateLimiter limiter = limiterAt("2026-07-14T12:00:10Z");

        assertDoesNotThrow(limiter::requireScanAllowed);
        assertDoesNotThrow(limiter::requireImportAllowed);
        assertDoesNotThrow(() -> limiter.requireReservationAllowed(9));
        assertDoesNotThrow(() -> limiter.requireStatusAllowed(9));
        assertThrows(TooManyRequestsException.class, limiter::requireImportAllowed);
        assertThrows(TooManyRequestsException.class,
            () -> limiter.requireReservationAllowed(9));
        assertThrows(TooManyRequestsException.class,
            () -> limiter.requireStatusAllowed(9));
        assertDoesNotThrow(limiter::requireScanAllowed);
    }

    @Test
    void preMultipartAdmissionAndServiceImportBudgetsAreIndependent() {
        BusinessCardRateLimiter limiter = limiterAt("2026-07-14T12:00:10Z");

        assertDoesNotThrow(() -> limiter.requireImportAdmissionAllowed(9));
        assertThrows(TooManyRequestsException.class,
            () -> limiter.requireImportAdmissionAllowed(9));
        assertDoesNotThrow(limiter::requireImportAllowed);
    }

    @Test
    void reservationAndStatusAdmissionUseTheProvidedPrincipalWithoutServices() {
        BusinessCardRateLimiter limiter = limiterAt("2026-07-14T12:00:10Z");

        assertDoesNotThrow(() -> limiter.requireReservationAllowed(9));
        assertDoesNotThrow(() -> limiter.requireStatusAllowed(9));

        verifyNoInteractions(workspaceService, authService);
    }

    @Test
    void newWindowStartsWithFreshCapacity() {
        Clock clock = org.mockito.Mockito.mock(Clock.class);
        when(clock.instant())
                .thenReturn(Instant.parse("2026-07-14T12:00:10Z"))
                .thenReturn(Instant.parse("2026-07-14T12:01:00Z"));
        BusinessCardRateLimiter limiter = new BusinessCardRateLimiter(
                properties, workspaceService, authService, clock);

        assertDoesNotThrow(limiter::requireImportAllowed);
        assertDoesNotThrow(limiter::requireImportAllowed);
    }

    private BusinessCardRateLimiter limiterAt(String instant) {
        return new BusinessCardRateLimiter(
                properties,
                workspaceService,
                authService,
                Clock.fixed(Instant.parse(instant), ZoneOffset.UTC));
    }
}
