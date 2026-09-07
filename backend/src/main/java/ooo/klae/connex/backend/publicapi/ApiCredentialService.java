package ooo.klae.connex.backend.publicapi;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.exceptions.ServiceUnavailableException;
import ooo.klae.connex.backend.mappers.ApiCredentialMapper;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.services.AuditService;
import ooo.klae.connex.backend.services.SessionSecurityService;
import ooo.klae.connex.backend.services.WorkspaceService;
import ooo.klae.connex.backend.services.WorkspaceService.LockedMemberAuthorization;
import ooo.klae.connex.backend.services.WorkspaceService.SnapshotMemberAuthorization;
import ooo.klae.connex.backend.tenant.Permission;
import ooo.klae.connex.backend.tenant.RequirePermission;

/** Issues, resolves, lists, and revokes hash-only personal API credentials. */
@Service
public class ApiCredentialService {
    private static final String TOKEN_PREFIX = "cnx_pat_";
    private static final int TOKEN_BYTES = 32;
    private static final int MAX_CREDENTIAL_PAGE_SIZE = 100;
    private static final Pattern TOKEN_PATTERN = Pattern.compile("cnx_pat_[A-Za-z0-9_-]{43}");

    private final ApiCredentialMapper apiCredentialMapper;
    private final UserMapper userMapper;
    private final WorkspaceService workspaceService;
    private final AuditService auditService;
    private final SessionSecurityService sessionSecurityService;
    private final boolean enabled;
    private final int maxActiveCredentialsPerMembership;
    private final SecureRandom secureRandom = new SecureRandom();

    /** Creates the service with an instance-level fail-closed availability switch. */
    public ApiCredentialService(
            ApiCredentialMapper apiCredentialMapper,
            UserMapper userMapper,
            WorkspaceService workspaceService,
            AuditService auditService,
            SessionSecurityService sessionSecurityService,
            @Value("${connex.public-api.enabled:false}") boolean enabled,
            @Value("${connex.public-api.max-active-credentials-per-membership:20}")
            int maxActiveCredentialsPerMembership) {
        if (maxActiveCredentialsPerMembership < 1) {
            throw new IllegalArgumentException(
                "Public API active credential limit must be positive");
        }
        this.apiCredentialMapper = apiCredentialMapper;
        this.userMapper = userMapper;
        this.workspaceService = workspaceService;
        this.auditService = auditService;
        this.sessionSecurityService = sessionSecurityService;
        this.enabled = enabled;
        this.maxActiveCredentialsPerMembership = maxActiveCredentialsPerMembership;
    }

    /** Returns whether this deployment has explicitly enabled the public API plane. */
    public boolean isEnabled() {
        return enabled;
    }

    /** Resolves only the catalog-routing keys after the caller has passed pre-auth throttling. */
    public Optional<RoutingBinding> resolveRoutingBinding(String rawToken) {
        if (!enabled || rawToken == null || !TOKEN_PATTERN.matcher(rawToken).matches()) {
            return Optional.empty();
        }
        String presentedHash = sha256Hex(rawToken);
        ApiCredential candidate = apiCredentialMapper.findRoutingByTokenHash(presentedHash);
        if (candidate == null) {
            return Optional.empty();
        }
        boolean hashMatches = constantTimeHashEquals(candidate.getTokenHash(), presentedHash);
        boolean active = candidate.getRevokedAt() == null
            && candidate.getExpiresAt() != null
            && candidate.getExpiresAt().isAfter(LocalDateTime.now(ZoneOffset.UTC));
        if (!hashMatches || !active) {
            return Optional.empty();
        }
        return Optional.of(new RoutingBinding(
            candidate.getId(),
            candidate.getWorkspaceId(),
            candidate.getOrganizationId(),
            candidate.getCreatedById(),
            presentedHash));
    }

    /**
     * Authenticates one candidate from consistent non-locking reads in the caller's read-only
     * {@code REPEATABLE_READ} transaction.
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Optional<AuthenticatedCredential> authenticate(RoutingBinding routing) {
        if (!enabled || routing == null || routing.presentedHash() == null) {
            return Optional.empty();
        }
        User creator = userMapper.getUserById(routing.userId());
        if (creator == null) {
            return Optional.empty();
        }
        SnapshotMemberAuthorization authorization = workspaceService.snapshotMemberAuthorization(
            routing.workspaceId(), routing.userId());
        if (authorization == null) {
            return Optional.empty();
        }
        ApiCredential credential = apiCredentialMapper.findByIdWithScopes(
            routing.workspaceId(), routing.credentialId());
        if (credential == null
                || !constantTimeHashEquals(
                    credential.getTokenHash(), routing.presentedHash())
                || credential.getWorkspaceId() != routing.workspaceId()
                || credential.getOrganizationId() != routing.organizationId()
                || credential.getCreatedById() != routing.userId()
                || credential.getMembershipId() != authorization.membershipId()
                || credential.getOrganizationId() != authorization.organizationId()
                || credential.getRevokedAt() != null
                || credential.getExpiresAt() == null
                || !credential.getExpiresAt().isAfter(LocalDateTime.now(ZoneOffset.UTC))) {
            return Optional.empty();
        }
        Set<ApiScope> credentialScopes = parseScopes(credential.getScopes());
        EnumSet<ApiScope> authorizedScopes = EnumSet.noneOf(ApiScope.class);
        for (ApiScope scope : credentialScopes) {
            if (scope.isAuthorizedBy(authorization.permissions())) {
                authorizedScopes.add(scope);
            }
        }
        ApiCredentialPrincipal details = new ApiCredentialPrincipal(
            credential.getId(),
            creator.getId(),
            credential.getWorkspaceId(),
            credential.getOrganizationId(),
            credential.getName(),
            credentialScopes,
            authorizedScopes,
            credential.getExpiresAt());
        return Optional.of(new AuthenticatedCredential(creator, details));
    }

    /** Records successful use after the authorization snapshot transaction has closed. */
    @Transactional
    public boolean recordSuccessfulUse(long credentialId, String presentedHash) {
        return apiCredentialMapper.updateLastUsed(credentialId, presentedHash) == 1;
    }

    /** Lists secret-free credential metadata for the active workspace. */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    @RequirePermission(Permission.API_CREDENTIAL_MANAGE)
    public List<CredentialView> list(int page, int size) {
        requireEnabled();
        if (page < 1 || size < 1 || size > MAX_CREDENTIAL_PAGE_SIZE) {
            throw new BadRequestException("Credential page or size is outside the allowed range");
        }
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        List<ApiCredential> credentials = apiCredentialMapper.listByWorkspace(
            workspaceId, size, (long) (page - 1) * size);
        if (credentials.isEmpty()) {
            return List.of();
        }
        Map<Long, List<String>> scopesByCredential = new LinkedHashMap<>();
        for (ApiCredentialScope scope : apiCredentialMapper.findScopesByCredentialIds(
                workspaceId,
                credentials.stream().map(ApiCredential::getId).toList())) {
            scopesByCredential.computeIfAbsent(scope.getCredentialId(), ignored -> new ArrayList<>())
                .add(scope.getScope());
        }
        for (ApiCredential credential : credentials) {
            credential.setScopes(List.copyOf(
                scopesByCredential.getOrDefault(credential.getId(), List.of())));
        }
        return credentials.stream()
            .map(ApiCredentialService::toView)
            .toList();
    }

    /** Issues a workspace-bound credential and returns its plaintext exactly once. */
    @Transactional
    @RequirePermission(Permission.API_CREDENTIAL_MANAGE)
    public IssuedCredential issue(String requestedName, Set<ApiScope> requestedScopes, LocalDateTime expiresAt) {
        requireEnabled();
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        int actorId = workspaceService.getCurrentUserId();
        sessionSecurityService.requireRecentAuthentication(actorId);
        LockedMemberAuthorization authorization = requireLockedManagement(workspaceId, actorId);
        String name = normalizeName(requestedName);
        Set<ApiScope> scopes = normalizeScopes(requestedScopes);
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        if (expiresAt == null || !expiresAt.isAfter(now)) {
            throw new BadRequestException("Credential expiry must be in the future");
        }
        for (ApiScope scope : scopes) {
            if (!scope.isAuthorizedBy(authorization.permissions())) {
                throw new ForbiddenException("Credential scope exceeds the creator's current permissions");
            }
        }
        apiCredentialMapper.deleteInactiveByMembership(
            workspaceId, actorId, authorization.membershipId());
        if (apiCredentialMapper.countActiveByMembership(
                workspaceId, actorId, authorization.membershipId())
                >= maxActiveCredentialsPerMembership) {
            throw new BadRequestException(
                "Active API credential limit reached for this workspace membership");
        }

        String rawToken = generateToken();
        ApiCredential credential = new ApiCredential();
        credential.setWorkspaceId(workspaceId);
        credential.setOrganizationId(authorization.organizationId());
        credential.setCreatedById(actorId);
        credential.setMembershipId(authorization.membershipId());
        credential.setName(name);
        credential.setTokenHash(sha256Hex(rawToken));
        credential.setTokenLast4(rawToken.substring(rawToken.length() - 4));
        credential.setExpiresAt(expiresAt);
        apiCredentialMapper.insert(credential);
        apiCredentialMapper.insertScopes(
            credential.getId(),
            scopes.stream().sorted().map(ApiScope::wireValue).toList());
        ApiCredential persistedCredential = apiCredentialMapper.findByTokenHash(credential.getTokenHash());
        if (persistedCredential == null) {
            throw new IllegalStateException("Issued API credential metadata is unavailable");
        }
        auditService.recordStrictScoped(
            "api_credential.issue",
            "api_credential",
            null,
            persistedCredential.getWorkspaceId(),
            persistedCredential.getOrganizationId(),
            credentialAuditLabel(persistedCredential),
            "Issued an API credential",
            Map.of("credentialId", persistedCredential.getId(), "last4", persistedCredential.getTokenLast4()));
        return new IssuedCredential(rawToken, toView(persistedCredential));
    }

    /** Revokes a credential in the active workspace without deleting its audit metadata. */
    @Transactional
    @RequirePermission(Permission.API_CREDENTIAL_MANAGE)
    public void revoke(long credentialId) {
        requireEnabled();
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        int actorId = workspaceService.getCurrentUserId();
        requireLockedManagement(workspaceId, actorId);
        ApiCredential credential = apiCredentialMapper.findByIdForUpdate(workspaceId, credentialId);
        if (credential == null) {
            throw new ResourceNotFoundException("API credential not found");
        }
        if (credential.getRevokedAt() == null) {
            apiCredentialMapper.revoke(workspaceId, credentialId, actorId);
            auditService.recordStrictScoped(
                "api_credential.revoke",
                "api_credential",
                null,
                credential.getWorkspaceId(),
                credential.getOrganizationId(),
                credentialAuditLabel(credential),
                "Revoked an API credential",
                Map.of("credentialId", credentialId, "last4", credential.getTokenLast4()));
        }
    }

    /** Returns metadata and currently effective scopes for the authenticating credential. */
    public CredentialIdentity currentCredential() {
        ApiCredentialPrincipal credential = currentDetails();
        return new CredentialIdentity(
            credential.credentialId(),
            credential.name(),
            credential.workspaceId(),
            credential.organizationId(),
            sortedScopes(credential.authorizedScopes()),
            credential.expiresAt());
    }

    static boolean constantTimeHashEquals(String expected, String presented) {
        if (expected == null || presented == null) {
            return false;
        }
        return MessageDigest.isEqual(
            expected.getBytes(StandardCharsets.US_ASCII),
            presented.getBytes(StandardCharsets.US_ASCII));
    }

    private LockedMemberAuthorization requireLockedManagement(int workspaceId, int actorId) {
        LockedMemberAuthorization authorization = workspaceService.lockedMemberAuthorization(
            workspaceId, actorId);
        if (authorization == null
                || !authorization.permissions().contains(Permission.API_CREDENTIAL_MANAGE)) {
            throw new ForbiddenException("Requires API_CREDENTIAL_MANAGE permission in this workspace");
        }
        return authorization;
    }

    private void requireEnabled() {
        if (!enabled) {
            throw new ServiceUnavailableException("Public API is disabled on this deployment");
        }
    }

    private ApiCredentialPrincipal currentDetails() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null
                || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof User user)
                || !(authentication.getDetails() instanceof ApiCredentialPrincipal credential)
                || credential.userId() != user.getId()) {
            throw new ForbiddenException("Public API credential authentication is required");
        }
        return credential;
    }

    private String generateToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        secureRandom.nextBytes(bytes);
        return TOKEN_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String sha256Hex(String value) {
        try {
            return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static String normalizeName(String value) {
        if (value == null || value.isBlank()) {
            throw new BadRequestException("Credential name is required");
        }
        String normalized = value.trim();
        if (normalized.length() > 128) {
            throw new BadRequestException("Credential name must be at most 128 characters");
        }
        if (TOKEN_PATTERN.matcher(normalized).find()) {
            throw new BadRequestException("Credential name must not contain a personal access token");
        }
        return normalized;
    }

    private static String credentialAuditLabel(ApiCredential credential) {
        return "Credential " + credential.getId() + " (last4 " + credential.getTokenLast4() + ")";
    }

    private static Set<ApiScope> normalizeScopes(Set<ApiScope> requestedScopes) {
        if (requestedScopes == null || requestedScopes.isEmpty()) {
            throw new BadRequestException("At least one credential scope is required");
        }
        return EnumSet.copyOf(requestedScopes);
    }

    private static Set<ApiScope> parseScopes(List<String> storedScopes) {
        if (storedScopes == null || storedScopes.isEmpty()) {
            throw new IllegalStateException("API credential has no scopes");
        }
        EnumSet<ApiScope> parsed = EnumSet.noneOf(ApiScope.class);
        for (String storedScope : storedScopes) {
            parsed.add(ApiScope.fromWire(storedScope));
        }
        return parsed;
    }

    private static CredentialView toView(ApiCredential credential) {
        return new CredentialView(
            credential.getId(),
            credential.getName(),
            credential.getWorkspaceId(),
            credential.getOrganizationId(),
            credential.getCreatedById(),
            credential.getTokenLast4(),
            credential.getScopes().stream().map(ApiScope::fromWire).sorted().toList(),
            credential.getExpiresAt(),
            credential.getLastUsedAt(),
            credential.getRevokedAt(),
            credential.getCreatedAt());
    }

    private static List<ApiScope> sortedScopes(Set<ApiScope> scopes) {
        List<ApiScope> sorted = new ArrayList<>(scopes);
        sorted.sort(Comparator.naturalOrder());
        return List.copyOf(sorted);
    }

    /** Authenticated creator principal plus secret-free credential details. */
    public record AuthenticatedCredential(User user, ApiCredentialPrincipal credential) {
    }

    /** Hash-bound control-plane values needed to select the tenant catalog before authorization. */
    public record RoutingBinding(
            long credentialId,
            int workspaceId,
            int organizationId,
            int userId,
            String presentedHash) {
    }

    /** One-time plaintext reveal paired with its persistent metadata. */
    public record IssuedCredential(String token, CredentialView credential) {
    }

    /** Secret-free management representation of a personal API credential. */
    public record CredentialView(
            long id,
            String name,
            int workspaceId,
            int organizationId,
            int createdById,
            String last4,
            List<ApiScope> scopes,
            LocalDateTime expiresAt,
            LocalDateTime lastUsedAt,
            LocalDateTime revokedAt,
            LocalDateTime createdAt) {
    }

    /** Public identity of the credential authenticating the current request. */
    public record CredentialIdentity(
            long credentialId,
            String name,
            int workspaceId,
            int organizationId,
            List<ApiScope> scopes,
            LocalDateTime expiresAt) {
    }

}
