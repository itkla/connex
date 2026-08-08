package ooo.klae.connex.backend.services;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.services.TenantExportService.TenantExportDownload;
import ooo.klae.connex.backend.services.TenantLifecycleControlOperations.AcquiredWorkspace;
import ooo.klae.connex.backend.tenant.TenantWorkScope;

/** Issues and redeems user-, session-, organization-, and workspace-bound export grants. */
@Service
@RequiredArgsConstructor
public class TenantExportGrantService {
    /** Lifetime of the browser-bound grant credential. */
    public static final Duration GRANT_LIFETIME = Duration.ofMinutes(2);
    private static final int TOKEN_BYTES = 32;

    private final OrgMemberService orgMemberService;
    private final SessionSecurityService sessionSecurityService;
    private final TenantWorkScope tenantWorkScope;
    private final TenantLifecycleControlOperations controlOperations;
    private final TenantExportService tenantExportService;
    private final Clock clock;
    private final SecureRandom secureRandom = new SecureRandom();

    /** Issues a new grant only after ordinary and recent-authentication authorization. */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public TenantExportGrant issue(
            int orgId,
            int workspaceId,
            int actorId,
            String sessionId) {
        requireSessionId(sessionId);
        return tenantWorkScope.unrouted(
            () -> issueUnrouted(orgId, workspaceId, actorId, sessionId));
    }

    /** Consumes one exact grant and prepares the authorized streaming download. */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public TenantExportDownload redeem(
            int orgId,
            int workspaceId,
            int actorId,
            String sessionId,
            String rawToken) {
        requireSessionId(sessionId);
        return tenantWorkScope.unrouted(
            () -> redeemUnrouted(orgId, workspaceId, actorId, sessionId, rawToken));
    }

    private TenantExportGrant issueUnrouted(
            int orgId,
            int workspaceId,
            int actorId,
            String sessionId) {
        orgMemberService.requireOrgAdmin(orgId, actorId);
        sessionSecurityService.requireRecentAuthentication(actorId);
        Instant now = clock.instant();
        Instant expiresAt = now.plus(GRANT_LIFETIME);
        String rawToken = newToken();
        controlOperations.issueExportGrant(
            orgId,
            workspaceId,
            actorId,
            digest(sessionId),
            digest(rawToken),
            utcDateTime(expiresAt),
            utcDateTime(now));
        return new TenantExportGrant(rawToken, expiresAt);
    }

    private TenantExportDownload redeemUnrouted(
            int orgId,
            int workspaceId,
            int actorId,
            String sessionId,
            String rawToken) {
        orgMemberService.requireOrgAdmin(orgId, actorId);
        if (rawToken == null || rawToken.length() != TOKEN_BYTES * 2) {
            throw new ForbiddenException("Tenant export download grant is invalid or expired");
        }
        try {
            HexFormat.of().parseHex(rawToken);
        } catch (IllegalArgumentException exception) {
            throw new ForbiddenException("Tenant export download grant is invalid or expired");
        }
        AcquiredWorkspace acquired = controlOperations.redeemExportGrant(
            orgId,
            workspaceId,
            actorId,
            digest(sessionId),
            digest(rawToken),
            utcDateTime(clock.instant()));
        return tenantExportService.prepareAcquired(orgId, actorId, acquired);
    }

    private String newToken() {
        byte[] token = new byte[TOKEN_BYTES];
        secureRandom.nextBytes(token);
        return HexFormat.of().formatHex(token);
    }

    private static byte[] digest(String value) {
        try {
            return MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static LocalDateTime utcDateTime(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static void requireSessionId(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new ForbiddenException("Authenticated session is unavailable");
        }
    }

    /** Raw grant material returned only to the path-scoped HttpOnly cookie writer. */
    public record TenantExportGrant(String token, Instant expiresAt) {
    }
}
