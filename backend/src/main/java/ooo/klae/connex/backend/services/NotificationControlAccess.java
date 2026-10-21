package ooo.klae.connex.backend.services;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.beans.NotificationMembership;
import ooo.klae.connex.backend.beans.NotificationMembershipAuthority;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.exceptions.ServiceUnavailableException;
import ooo.klae.connex.backend.exceptions.TenantScopeSnapshotException;
import ooo.klae.connex.backend.mappers.NotificationControlMapper;
import ooo.klae.connex.backend.services.RecordVisibilityScopeControlAccess.RecordVisibilityScope;
import ooo.klae.connex.backend.tenant.TenantContext;
import ooo.klae.connex.backend.tenant.TenantWorkScope;
import tools.jackson.databind.ObjectMapper;

/**
 * Prerequisite control contracts for NotificationMapper conversion (#485 L9b). Existing inbox,
 * producer and mutation SQL stays authoritative; these entry points intentionally acquire no
 * production callers until the whole-mapper conversion. Callers supply server-authorized IDs;
 * this component grants no RBAC capability and must not be exposed as an alternate API.
 *
 * <p>Read operations use two batched queries at most, on the caller's single-database connection,
 * without suspension or REQUIRES_NEW. REPEATABLE READ therefore covers control and tenant reads
 * with one view. The entry requires read-only REPEATABLE READ (or the repository's default
 * REPEATABLE READ) and rejects explicit READ COMMITTED: freezing its memberships would lose
 * later activation/departure observations. In a REPEATABLE READ mutation,
 * a plain control read before the first required lock would establish an obsolete read view
 * (#2176). Mutation callers instead retain exact current membership or actor locks through commit,
 * at their existing root-order position. Locking reads use physical control routing on the same
 * transaction connection, never TenantWorkScope.unrouted or a detached transaction.
 *
 * <p>The exact workspace membership fence does not lock other memberships of the recipient.
 * Reconciliation must retain its workspace/organization mutex prefix before membership fences
 * and its first consistent read; it must not hoist audiences into scheduler discovery. Account
 * and workspace roots required by other producers still precede memberships. See LOCKING.md.
 * Public entries validate routing before any transaction creation or connection checkout.
 * Routed mode refuses these contracts until cross-catalog inbox semantics exist (#2200).
 */
@Component
@RequiredArgsConstructor
public class NotificationControlAccess {
    private final NotificationControlMapper controlMapper;
    private final TenantWorkScope tenantWorkScope;
    private final TenantContext tenantContext;
    private final ObjectMapper objectMapper;
    private final PlatformTransactionManager transactionManager;

    /**
     * Resolves once after the caller's early returns and shares the snapshot across a pure read.
     * The dedicated callback never enters a shared mutation Supplier invocation. A new read-only
     * transaction is opened only after routing and arguments have been validated; an existing
     * read transaction is validated before it is joined. The facade must not acquire automatic
     * transaction advice, which would borrow a connection before its typed routing refusal.
     */
    public <T> T withRecipientRead(int recipientId, NotificationRecipientRead<T> read) {
        requireSingleDatabase();
        requirePositive(recipientId);
        Objects.requireNonNull(read, "read");
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            requireReadTransaction();
        }
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.setReadOnly(true);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        return transaction.execute(status -> {
            NotificationRecipientScope scope = tenantWorkScope.unroutedRead(() -> snapshot(recipientId));
            return read.read(scope);
        });
    }

    /**
     * Retains current status and generation for one exact membership on the writable caller
     * transaction. Reserved for conversion; caller root locks must already be in contract order.
     * Absence is returned as null, never replaced by an earlier snapshot or lifecycle-filtered row.
     */
    public NotificationMembershipAuthority lockMembershipForShare(int workspaceId, int recipientId) {
        requireMutation(workspaceId, recipientId);
        return controlMapper.lockMembershipForShare(workspaceId, recipientId);
    }

    /** Retains the exact membership exclusively without expanding to the recipient's other workspaces. */
    public NotificationMembershipAuthority lockMembershipForUpdate(int workspaceId, int recipientId) {
        requireMutation(workspaceId, recipientId);
        return controlMapper.lockMembershipForUpdate(workspaceId, recipientId);
    }

    /**
     * Fences live actor attribution on the writable caller transaction for the conversion's upsert.
     * Null actors need no query; deleted actors return null. Retain this lock until tenant commit,
     * reuse already-held account roots, and preserve each producer's existing actor-lock position
     * and account-before-domain root order rather than adding unrelated broader roots here.
     */
    public Integer lockActorForShare(Integer actorId) {
        requireSingleDatabase();
        requireWritableTransaction();
        if (actorId == null) {
            return null;
        }
        requirePositive(actorId);
        return controlMapper.lockActorForShare(actorId);
    }

    private NotificationRecipientScope snapshot(int recipientId) {
        Map<Integer, NotificationMembership> memberships = new TreeMap<>();
        for (NotificationMembership member : controlMapper.findRecipientMemberships(recipientId)) {
            if (member.recipientId() != recipientId || member.workspaceId() <= 0 || member.orgId() <= 0
                    || member.membershipId() <= 0 || member.status() == null || member.workspaceName() == null
                    || member.workspaceLifecycleState() == null || member.organizationLifecycleState() == null
                    || memberships.putIfAbsent(member.workspaceId(), member) != null) {
                throw invalidSnapshot();
            }
        }
        List<Integer> orgIds = memberships.values().stream().map(NotificationMembership::orgId)
            .distinct().sorted().toList();
        Map<Integer, Map<Integer, Workspace>> workspacesByOrg = new TreeMap<>();
        if (!orgIds.isEmpty()) {
            for (Workspace workspace : controlMapper.findOrganizationWorkspaces(orgIds)) {
                if (workspace.getId() <= 0
                        || !orgIds.contains(workspace.getOrgId())) {
                    throw invalidSnapshot();
                }
                Map<Integer, Workspace> peers = workspacesByOrg.computeIfAbsent(
                    workspace.getOrgId(), ignored -> new TreeMap<>());
                if (peers.putIfAbsent(workspace.getId(), workspace) != null) {
                    throw invalidSnapshot();
                }
            }
        }
        Map<Integer, RecordVisibilityScope> scopesByOrg = new LinkedHashMap<>();
        Map<Integer, RecordVisibilityScope> sources = new LinkedHashMap<>();
        for (NotificationMembership member : memberships.values()) {
            Map<Integer, Workspace> peers = workspacesByOrg.get(member.orgId());
            if (peers == null || !peers.containsKey(member.workspaceId())) {
                throw invalidSnapshot();
            }
            RecordVisibilityScope scope = scopesByOrg.computeIfAbsent(member.orgId(),
                orgId -> new RecordVisibilityScope(orgId, List.copyOf(peers.keySet())));
            sources.put(member.workspaceId(), scope);
        }
        return new NotificationRecipientScope(recipientId, memberships, sources, objectMapper);
    }

    private void requireMutation(int workspaceId, int recipientId) {
        requireSingleDatabase();
        requireWritableTransaction();
        requirePositive(workspaceId);
        requirePositive(recipientId);
    }

    private void requireSingleDatabase() {
        if (tenantWorkScope.isCatalogPerPlacement() || tenantContext.getCatalog() != null) {
            throw new ServiceUnavailableException("Notification catalog routing requires the cross-catalog inbox (#2200)");
        }
    }

    private static void requireWritableTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            throw new IllegalStateException("Notification authority requires a writable transaction");
        }
    }

    private static void requireReadTransaction() {
        if (!TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            throw new IllegalStateException("Notification snapshots require a read-only transaction");
        }
        Integer isolation = TransactionSynchronizationManager.getCurrentTransactionIsolationLevel();
        if (isolation != null && isolation != TransactionDefinition.ISOLATION_REPEATABLE_READ) {
            throw new IllegalStateException("Notification snapshots require REPEATABLE READ");
        }
    }

    private static void requirePositive(int id) {
        if (id <= 0) {
            throw new IllegalArgumentException("Notification authority IDs must be positive");
        }
    }

    private static TenantScopeSnapshotException invalidSnapshot() {
        return new TenantScopeSnapshotException("Notification control snapshot is inconsistent");
    }
}
