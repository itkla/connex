package ooo.klae.connex.backend.publicapi;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.mappers.ApiCredentialMapper;
import ooo.klae.connex.backend.mappers.OrganizationMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.services.AuditService;

/** Deletes membership-bound credentials inside offboarding and account-erasure transactions. */
@Service
@RequiredArgsConstructor
public class ApiCredentialLifecycleService {
    private final ApiCredentialMapper apiCredentialMapper;
    private final WorkspaceMapper workspaceMapper;
    private final OrganizationMapper organizationMapper;
    private final AuditService auditService;

    /**
     * Deletes and audits every credential created by one membership root, including while the
     * workspace is tearing down; returns only when a root is already gone. The workspace root and
     * its organization root are taken {@code FOR SHARE} unconditionally, before any credential row,
     * because taking them only when credentials exist would place them after the child rows the
     * trailing audit's foreign-key parents re-request.
     */
    public void deleteForMembership(int workspaceId, int userId) {
        Integer organizationId = workspaceMapper.lockWorkspaceOrgIdForShare(workspaceId);
        if (organizationId == null) {
            return;
        }
        if (organizationMapper.lockByIdForShare(organizationId) == null) {
            return;
        }
        List<ApiCredential> credentials =
            apiCredentialMapper.listByMembershipForUpdate(workspaceId, userId);
        if (credentials.isEmpty()) {
            return;
        }
        if (apiCredentialMapper.deleteByMembership(workspaceId, userId) != credentials.size()) {
            throw new IllegalStateException("API credential membership cleanup did not converge");
        }
        for (ApiCredential credential : credentials) {
            auditDeletion("api_credential.membership_removed", credential);
        }
    }

    /** Discovers, without locks, every workspace and organization root referenced by an account. */
    public List<ApiCredentialReferenceRoot> discoverAccountReferenceRoots(int userId) {
        return apiCredentialMapper.listAccountReferenceRoots(userId).stream()
            .distinct()
            .sorted(Comparator.comparingInt(ApiCredentialReferenceRoot::workspaceId)
                .thenComparingInt(ApiCredentialReferenceRoot::organizationId))
            .toList();
    }

    /**
     * Deletes every credential that references an account being erased, including while its
     * workspace is tearing down, and skips only exact credential rows that already vanished. The
     * retained evidence is returned unemitted so the erasure can append it in one canonical
     * audit-scope order; dropping the returned audits would destroy credential rows without
     * evidence, so every caller must emit all of them inside the same transaction.
     *
     * <p>No residual reference count follows the deletions. What makes the plan complete is the
     * durable deletion reservation: it commits in its own transaction, its {@code app_user} update
     * waits behind the {@code FOR SHARE} that every in-flight issuance and revocation holds on that
     * row, and {@code WorkspaceService.lockedMemberAuthorization} then refuses a
     * reservation-flagged account. No {@code created_by_id} or {@code revoked_by_id} reference can
     * therefore be born after the reservation, and every earlier one is already inside this
     * transaction's read view. A row that vanished was deleted by another transaction and needs no
     * action here, while a consistent-read count would still see it and abort a compliance-critical
     * erasure.
     *
     * @param userId account being erased
     * @param lockedReferenceRoots tenant roots the caller has already locked
     * @return one unemitted audit per deleted credential, in {@code (workspace_id, id)} order
     */
    public List<PendingCredentialAudit> deleteForAccount(
            int userId, Collection<ApiCredentialReferenceRoot> lockedReferenceRoots) {
        Set<ApiCredentialReferenceRoot> plannedRoots = new HashSet<>(lockedReferenceRoots);
        List<ApiCredential> credentials = apiCredentialMapper.listByAccountReference(userId).stream()
            .sorted(Comparator.comparingInt(ApiCredential::getWorkspaceId)
                .thenComparingLong(ApiCredential::getId))
            .toList();
        List<PendingCredentialAudit> pendingAudits = new ArrayList<>();
        for (int first = 0; first < credentials.size();) {
            int workspaceId = credentials.get(first).getWorkspaceId();
            int end = first + 1;
            while (end < credentials.size()
                    && credentials.get(end).getWorkspaceId() == workspaceId) {
                end++;
            }
            List<ApiCredential> lockedCredentials = new ArrayList<>(end - first);
            for (int index = first; index < end; index++) {
                ApiCredential candidate = credentials.get(index);
                if (!plannedRoots.contains(new ApiCredentialReferenceRoot(
                        candidate.getWorkspaceId(), candidate.getOrganizationId()))) {
                    throw new IllegalStateException(
                        "API credential account cleanup crossed an unlocked tenant root");
                }
                ApiCredential locked = apiCredentialMapper.findByIdForUpdate(
                    workspaceId, candidate.getId());
                if (locked == null) {
                    continue;
                }
                if (locked.getWorkspaceId() != workspaceId
                        || locked.getOrganizationId() != candidate.getOrganizationId()
                        || (locked.getCreatedById() != userId
                            && !Objects.equals(locked.getRevokedById(), userId))) {
                    throw new IllegalStateException("API credential account cleanup plan changed");
                }
                lockedCredentials.add(locked);
            }
            for (ApiCredential locked : lockedCredentials) {
                if (apiCredentialMapper.deleteById(workspaceId, locked.getId()) != 1) {
                    throw new IllegalStateException(
                        "API credential account cleanup did not converge");
                }
                pendingAudits.add(new RetainedCredentialAudit(this, locked));
            }
            first = end;
        }
        return List.copyOf(pendingAudits);
    }

    private void auditDeletion(String action, ApiCredential credential) {
        auditService.recordStrictScoped(
            action,
            "api_credential",
            null,
            credential.getWorkspaceId(),
            credential.getOrganizationId(),
            "Credential " + credential.getId() + " (last4 " + credential.getTokenLast4() + ")",
            "Deleted an API credential during identity lifecycle cleanup",
            Map.of("credentialId", credential.getId(), "last4", credential.getTokenLast4()));
    }

    private record RetainedCredentialAudit(
            ApiCredentialLifecycleService lifecycleService, ApiCredential credential)
            implements PendingCredentialAudit {

        @Override
        public int workspaceId() {
            return credential.getWorkspaceId();
        }

        @Override
        public int organizationId() {
            return credential.getOrganizationId();
        }

        @Override
        public void emit() {
            lifecycleService.auditDeletion("api_credential.account_erased", credential);
        }
    }
}
