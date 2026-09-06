package ooo.klae.connex.backend.services;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.exceptions.ConflictException;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.publicapi.ApiCredentialLifecycleService;
import ooo.klae.connex.backend.publicapi.ApiCredentialReferenceRoot;
import ooo.klae.connex.backend.publicapi.PendingCredentialAudit;
import ooo.klae.connex.backend.storage.ManagedObjectService;
import ooo.klae.connex.backend.tenant.TenantContext;

/**
 * Single-catalog account deletion transaction entered after provider catalog fan-out.
 */
@Component
@RequiredArgsConstructor
public class UserDeletionTransaction {
    static final int WORKSPACE_SCOPE_RANK = 0;
    static final int ORGANIZATION_SCOPE_RANK = 1;
    static final int SYSTEM_SCOPE_RANK = 2;

    private static final Set<String> AUDIT_FIELDS =
        Set.of("username", "displayName", "email", "department", "title",
            "employeeId", "phoneNumber", "profilePictureUrl", "timezone", "locale");

    private final UserMapper userMapper;
    private final WorkspaceService workspaceService;
    private final OrgMemberService orgMemberService;
    private final ManagedObjectService managedObjectService;
    private final AuditService auditService;
    private final ApiCredentialLifecycleService apiCredentialLifecycleService;
    private final TenantContext tenantContext;

    /** Guards the account and installs a durable reservation before provider fan-out. */
    @Transactional
    public void reserve(int id, String owner) {
        guard(id, false);
        if (userMapper.reserveAccountDeletion(id, owner) != 1) {
            throw new ConflictException("Account deletion is already in progress");
        }
    }

    /** Extends the owner-bound deletion lease between catalog fan-out phases. */
    @Transactional
    public void renew(int id, String owner) {
        if (userMapper.renewAccountDeletionReservation(id, owner) != 1) {
            throw new ConflictException("Account deletion reservation expired");
        }
    }

    /** Clears a failed account-deletion reservation so the operation can be retried. */
    @Transactional
    public void release(int id, String owner) {
        userMapper.clearAccountDeletionReservation(id, owner);
    }

    /**
     * Locks, revalidates, erases, audits, and deletes the reserved control user atomically. This is
     * the only transaction in the branch that appends into more than one audit-integrity chain, so
     * every append is emitted in one pass ordered by audit scope rather than in deletion order.
     */
    @Transactional
    public void delete(int id, String owner) {
        if (!userMapper.isAccountDeletionReservationOwner(id, owner)) {
            throw new ConflictException("Account deletion was not reserved");
        }
        AccountDeletionGuard guarded = guard(id, true);
        User before = guarded.user();
        List<PendingCredentialAudit> credentialAudits =
            apiCredentialLifecycleService.deleteForAccount(id, guarded.credentialReferenceRoots());
        managedObjectService.deleteUserImageAfterCommit(
            id, before.getProfilePictureUrl());
        Integer accountAuditWorkspaceId = tenantContext.getWorkspaceId();
        Integer accountAuditOrgId = tenantContext.getOrgId();
        emitInAuditScopeOrder(
            credentialAudits,
            auditScopeKey(accountAuditWorkspaceId, accountAuditOrgId),
            () -> auditService.recordScoped(
                "user.delete",
                "user",
                id,
                accountAuditWorkspaceId,
                accountAuditOrgId,
                before.getUsername(),
                "Deleted user " + before.getUsername(),
                auditService.diff(before, null, AUDIT_FIELDS)));
        userMapper.delete(id);
    }

    private void emitInAuditScopeOrder(
            List<PendingCredentialAudit> credentialAudits,
            AuditScopeKey accountScope,
            Runnable accountAudit) {
        List<ScopedAudit> ordered = new ArrayList<>(credentialAudits.size() + 1);
        for (PendingCredentialAudit credentialAudit : credentialAudits) {
            ordered.add(new ScopedAudit(
                auditScopeKey(credentialAudit.workspaceId(), credentialAudit.organizationId()),
                credentialAudit::emit));
        }
        ordered.add(new ScopedAudit(accountScope, accountAudit));
        ordered.sort(Comparator.comparingInt((ScopedAudit audit) -> audit.scope().typeRank())
            .thenComparingInt(audit -> audit.scope().scopeId()));
        for (ScopedAudit audit : ordered) {
            audit.emitter().run();
        }
    }

    static AuditScopeKey auditScopeKey(Integer workspaceId, Integer orgId) {
        if (workspaceId != null) {
            return new AuditScopeKey(WORKSPACE_SCOPE_RANK, workspaceId);
        }
        if (orgId != null) {
            return new AuditScopeKey(ORGANIZATION_SCOPE_RANK, orgId);
        }
        return new AuditScopeKey(SYSTEM_SCOPE_RANK, 0);
    }

    record AuditScopeKey(int typeRank, int scopeId) {
    }

    private record ScopedAudit(AuditScopeKey scope, Runnable emitter) {
    }

    private AccountDeletionGuard guard(int id, boolean includeCredentialReferences) {
        if (userMapper.lockById(id) == null) {
            throw new ResourceNotFoundException("User not found with id: " + id);
        }
        List<Integer> ownedWorkspaceIds = workspaceService.discoverOwnedWorkspaceIds(id);
        List<ApiCredentialReferenceRoot> credentialReferenceRoots = includeCredentialReferences
            ? apiCredentialLifecycleService.discoverAccountReferenceRoots(id)
            : List.of();
        TreeSet<Integer> sharedWorkspaceIds = new TreeSet<>();
        TreeSet<Integer> sharedOrgIds = new TreeSet<>();
        for (ApiCredentialReferenceRoot root : credentialReferenceRoots) {
            sharedWorkspaceIds.add(root.workspaceId());
            sharedOrgIds.add(root.organizationId());
        }
        if (includeCredentialReferences) {
            Integer currentWorkspaceId = tenantContext.getWorkspaceId();
            Integer currentOrgId = tenantContext.getOrgId();
            if (currentWorkspaceId != null) {
                sharedWorkspaceIds.add(currentWorkspaceId);
            }
            if (currentOrgId != null) {
                sharedOrgIds.add(currentOrgId);
            }
        }
        workspaceService.lockAccountWorkspaceRoots(
            ownedWorkspaceIds, new ArrayList<>(sharedWorkspaceIds));
        workspaceService.assertNotSoleOwnerOfWorkspaces(ownedWorkspaceIds);
        orgMemberService.assertNotSoleOwnerOfAnyOrg(id, new ArrayList<>(sharedOrgIds));
        User before = userMapper.getUserById(id);
        if (before == null) {
            throw new ResourceNotFoundException("User not found with id: " + id);
        }
        return new AccountDeletionGuard(before, List.copyOf(credentialReferenceRoots));
    }

    private record AccountDeletionGuard(
            User user, List<ApiCredentialReferenceRoot> credentialReferenceRoots) {
    }
}
