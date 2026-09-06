package ooo.klae.connex.backend.publicapi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.EnumSet;

import org.junit.jupiter.api.Test;

import ooo.klae.connex.backend.tenant.Permission;

class ApiScopeTest {

    @Test
    void everyScopeMapsToGrantableLivePermissions() {
        for (ApiScope scope : ApiScope.values()) {
            assertFalse(scope.permissions().isEmpty());
            assertTrue(scope.permissions().stream().allMatch(Permission::isGrantable));
            assertFalse(scope.permissions().contains(Permission.SSO_MANAGE));
            assertFalse(scope.permissions().contains(Permission.WORKSPACE_DELETE));
        }
    }

    @Test
    void wireCatalogRoundTripsExactly() {
        for (ApiScope scope : ApiScope.values()) {
            assertEquals(scope, ApiScope.fromWire(scope.wireValue()));
        }
    }

    @Test
    void storedHashComparisonUsesLengthSafeConstantTimePrimitive() {
        String hash = "a".repeat(64);
        assertTrue(ApiCredentialService.constantTimeHashEquals(hash, hash));
        assertFalse(ApiCredentialService.constantTimeHashEquals(hash, "b".repeat(64)));
        assertFalse(ApiCredentialService.constantTimeHashEquals(hash, "a".repeat(63)));
        assertFalse(ApiCredentialService.constantTimeHashEquals(null, hash));
    }

    @Test
    void rateLimiterPublishesLimitRemainingResetAndRetryAfter() {
        Clock clock = Clock.fixed(Instant.parse("2026-09-03T00:00:00Z"), ZoneOffset.UTC);
        ApiRateLimiter limiter = new ApiRateLimiter(2, 60, clock);

        ApiRateLimiter.Decision first = limiter.acquire(7);
        ApiRateLimiter.Decision second = limiter.acquire(7);
        ApiRateLimiter.Decision refused = limiter.acquire(7);

        assertTrue(first.allowed());
        assertEquals(1, first.remaining());
        assertTrue(second.allowed());
        assertEquals(0, second.remaining());
        assertFalse(refused.allowed());
        assertEquals(60, refused.retryAfterSeconds());
        assertEquals(Instant.parse("2026-09-03T00:01:00Z").getEpochSecond(), refused.resetAt());
    }

    @Test
    void scopeAuthorizationIsTheIntersectionWithLiveRbac() {
        assertTrue(ApiScope.CRM_READ.isAuthorizedBy(EnumSet.of(Permission.REPORT_READ)));
        assertFalse(ApiScope.CRM_READ.isAuthorizedBy(EnumSet.of(Permission.DEAL_UPDATE)));
        assertTrue(ApiScope.CRM_WRITE.isAuthorizedBy(EnumSet.of(Permission.DEAL_UPDATE)));
    }
}
