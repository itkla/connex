package ooo.klae.connex.backend.services;

import java.util.Collection;
import java.util.List;
import java.util.Set;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.WorkspaceMember;
import ooo.klae.connex.backend.config.PrivilegedMfaProperties;
import ooo.klae.connex.backend.config.PrivilegedMfaRequestPolicy;
import ooo.klae.connex.backend.exceptions.PrivilegedMfaAttestationRequiredException;
import ooo.klae.connex.backend.exceptions.PrivilegedMfaEnrollmentRequiredException;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.tenant.Permission;
import ooo.klae.connex.backend.util.ClientIpResolver;

/** Reasserts confinement from the request actor's retained workspace authorization. */
@Service
@RequiredArgsConstructor
@Slf4j
public class PrivilegedMfaLockedAuthorization {
    private static final Set<Permission> PRIVILEGED_PERMISSIONS = Set.of(
        Permission.API_CREDENTIAL_MANAGE, Permission.AUDIT_READ, Permission.CAMPAIGN_MANAGE,
        Permission.CAMPAIGN_SEND, Permission.CONSENT_MANAGE, Permission.CUSTOM_FIELD_MANAGE,
        Permission.DOCUMENT_MANAGE, Permission.MEMBER_MANAGE, Permission.PIPELINE_MANAGE,
        Permission.PRODUCT_MANAGE, Permission.ROLE_MANAGE, Permission.RULE_MANAGE,
        Permission.SEQUENCE_MANAGE, Permission.SHARE_MANAGE, Permission.TAG_MANAGE,
        Permission.TEAM_MANAGE, Permission.WORKSPACE_SETTINGS);

    private final PrivilegedMfaProperties properties;
    private final UserMapper userMapper;
    private final AutomationScope automationScope;
    private final AuditService auditService;
    private final DenialAuditRateLimiter denialAuditRateLimiter;
    private final ClientIpResolver clientIpResolver;

    /**
     * Identifies only the authenticated HTTP actor among the accounts being authorized. Targets,
     * background work and automation never become confinement subjects. This decision performs no
     * database access; credential reads wait until retained authorization proves local privilege.
     *
     * @param authorizedUserIds accounts whose roots the caller already holds
     * @return the actor id, or null when this dispatch is outside confinement
     */
    public Integer requestActor(Collection<Integer> authorizedUserIds) {
        if (!properties.isEnforced() || automationScope.isActive() || automationScope.origin() != null
                || !(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs)) {
            return null;
        }
        PrivilegedMfaRequestPolicy.Decision decision = PrivilegedMfaRequestPolicy.decision(attrs.getRequest());
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (decision == null || decision.exempt() || authentication == null
                || !authentication.isAuthenticated() || !(authentication.getPrincipal() instanceof User user)
                || user.getId() != decision.userId() || !authorizedUserIds.contains(user.getId())
                || "__connex_system__".equals(user.getUsername())) {
            return null;
        }
        return user.getId();
    }

    /**
     * Refuses only the authenticated actor privileged in this workspace or its organization.
     * Built-in owner/admin authority needs no role lookup; a custom overlay uses only its retained
     * permission rows, with the closed list mirrored from UserMapper's privilegedOrgBranches.
     *
     * <p>The distinct, cache-flushed standing statement uses current shared reads on the same
     * connection after account, workspace, membership and role authority, matching registration and
     * deletion's role-before-credential order. No organization lock is added: retaining that parent
     * before an aggregate can cycle against a queued organization writer and the aggregate holder's
     * trailing audit. A consistent credential read is unsafe even under the
     * account root: its old REPEATABLE READ snapshot can predate an ordinary member deleting its last
     * passkey and then becoming privileged. NOWAIT avoids waiting on an assertion's credential update
     * while an account writer queues ahead of its trailing audit; the caller translates contention
     * through the existing workspace retry contract. This neither opens a consistent-read snapshot
     * ahead of a caller's aggregate mutex nor borrows another pooled connection. The check
     * covers this workspace's organization, leaving account-wide remote promotions to the filters.
     *
     * <p>When workspace authority is ordinary, a separate cache-flushed, nonlocking read checks
     * owner/admin membership in this workspace's active organization. It adds no lock edge and uses
     * this transaction's isolation: under REPEATABLE READ a grant committed before its consistent
     * snapshot is visible, but one committed after that snapshot and before this check is not.
     *
     * @param actorId authenticated actor selected for confinement, or null
     * @param workspaceId workspace whose authorization is retained
     * @param membership exact locked membership
     * @param permissions effective permissions from the retained role rows
     */
    public void requireAllowed(Integer actorId, int workspaceId, WorkspaceMember membership,
            Set<Permission> permissions) {
        if (actorId == null || membership == null || membership.getUserId() != actorId
                || membership.getWorkspaceId() != workspaceId || !"active".equals(membership.getStatus())) {
            return;
        }
        WorkspaceService.Role role = membership.getRoleId() == null
            ? WorkspaceService.Role.of(membership.getRole()) : null;
        boolean privileged = membership.getRoleId() == null
            ? role == WorkspaceService.Role.OWNER || role == WorkspaceService.Role.ADMIN
            : permissions.stream().anyMatch(PRIVILEGED_PERMISSIONS::contains);
        if (!privileged && !userMapper.hasWorkspaceOrganizationPrivilege(actorId, workspaceId)) {
            return;
        }
        boolean attestationEnforced = properties.isAttestationEnforced();
        List<Boolean> passkeys = userMapper.lockedWorkspaceMfaPasskeySufficiency(actorId, workspaceId,
            attestationEnforced);
        if (passkeys.isEmpty()) {
            recordRefusal("enrollment_required");
            throw new PrivilegedMfaEnrollmentRequiredException();
        }
        if (attestationEnforced && passkeys.stream().noneMatch(Boolean.TRUE::equals)) {
            recordRefusal("attestation_required");
            throw new PrivilegedMfaAttestationRequiredException();
        }
    }

    private void recordRefusal(String reason) {
        try {
            ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.currentRequestAttributes();
            User user = (User) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
            String clientIp = clientIpResolver.resolveForDenialAudit(attrs.getRequest());
            String action = "auth.mfa.policy.denied";
            denialAuditRateLimiter.acquire(user.getId(), action, reason, clientIp, attrs.getRequest()).ifPresent(admission -> {
                PrivilegedMfaRequestPolicy.Decision decision = PrivilegedMfaRequestPolicy.decision(attrs.getRequest());
                String prefix = decision.publicApi() ? "Public API privileged" : "Privileged";
                String summary = prefix + ("enrollment_required".equals(reason)
                    ? " account confined pending MFA enrollment" : " account confined pending passkey approval");
                auditService.deferStrictFailureIndependentScoped(action, "user", user.getId(), null, null,
                    user.getDisplayName(), summary, reason, attrs.getRequest(), clientIp,
                    () -> denialAuditRateLimiter.release(admission));
            });
        } catch (RuntimeException failure) {
            log.error("Failed to defer privileged MFA refusal reason={}", reason, failure);
        }
    }
}
