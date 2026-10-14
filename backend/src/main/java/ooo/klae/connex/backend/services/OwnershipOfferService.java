package ooo.klae.connex.backend.services;

import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.sql.SQLException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ooo.klae.connex.backend.dto.MemberDto;
import ooo.klae.connex.backend.dto.OrgMemberDto;
import ooo.klae.connex.backend.dto.MemberRoleChange;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.beans.OwnershipOffer;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.dto.OwnershipOfferDto;
import ooo.klae.connex.backend.dto.OwnershipOfferAcceptanceDto;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.mappers.OwnershipOfferMapper;
import ooo.klae.connex.backend.mappers.OrgMemberMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.mappers.OrganizationMapper;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.mappers.TenantLifecycleControlMapper;
import ooo.klae.connex.backend.tenant.TenantContext;
import ooo.klae.connex.backend.tenant.TenantWorkScope;

/** Authoritative self-offer cards and route-before-transaction ownership operations. */
@Service
@RequiredArgsConstructor
public class OwnershipOfferService {
    private final OwnershipOfferMapper mapper;
    private final OrgMemberMapper orgMemberMapper;
    private final WorkspaceMapper workspaceMapper;
    private final OrganizationMapper organizationMapper;
    private final UserMapper userMapper;
    private final OwnershipOfferTransaction transaction;
    private final OwnershipOfferEligibility eligibility;
    private final TenantWorkScope tenantWorkScope;
    private final TenantContext tenantContext;
    private final TenantLifecycleControlMapper lifecycleMapper;
    private final TenantLifecycleAccess lifecycleAccess;
    private final PrivilegedAccountService privilegedAccountService;
    private final AuditService auditService;
    private final WorkspaceService workspaceService;
    private final OrgMemberService orgMemberService;
    private static final Logger log = LoggerFactory.getLogger(OwnershipOfferService.class);

    /** Lists the authenticated offeree's unexpired, currently eligible control-plane cards. */
    public List<OwnershipOfferDto> listForSelf(int userId) {
        return tenantWorkScope.unroutedRead(() -> mapper.listForOfferee(userId).stream()
            .filter(eligibility::isValid).map(OwnershipOfferDto::from)
            .map(displayLabelsFor(userId, false)).toList());
    }

    /** Lists pending scope offers for a current raw scope owner, with inaccessible scopes hidden. */
    public List<OwnershipOfferDto> listForScope(String kind, int scopeId, int actorId) {
        return tenantWorkScope.unroutedRead(() -> {
            boolean owner = "WORKSPACE".equals(kind)
                ? "owner".equals(workspaceMapper.getRole(scopeId, actorId))
                : "owner".equals(orgMemberMapper.getRole(scopeId, actorId));
            if (!owner) {
                throw notFound();
            }
            return mapper.listForScope(kind, scopeId).stream().filter(eligibility::isValid)
                .map(OwnershipOfferDto::from).map(displayLabelsFor(actorId, true)).toList();
        });
    }

    /** Accepts consent, then refreshes standing after its promotion transaction commits. */
    public OwnershipOfferAcceptanceDto accept(long id, int userId, boolean acknowledged) {
        requireCompatibleEnclosingTransaction();
        OwnershipOffer offer = find(id);
        if (offer.getOffereeUserId() != userId) {
            throw notFound();
        }
        try {
            OwnershipOfferAcceptanceDto result = routed(offer, userId,
                () -> transaction.accept(id, userId, acknowledged));
            return tenantWorkScope.unroutedRead(() -> new OwnershipOfferAcceptanceDto(
                displayLabelsFor(userId, false).apply(result.offer()), result.membership(), privilegedAccountService.standing(userId),
                privilegedAccountService.enforcedStanding(userId)));
        } catch (RuntimeException failure) {
            refusalAfterRollback(offer, "accept", failure);
            throw failure;
        }
    }

    /** Declines one's own consent without granting privilege or requiring step-up. */
    public void decline(long id, int userId) {
        requireCompatibleEnclosingTransaction();
        OwnershipOffer offer = find(id);
        if (offer.getOffereeUserId() != userId) {
            throw notFound();
        }
        try {
            routed(offer, userId, () -> { transaction.decline(id, userId); return true; });
        } catch (RuntimeException failure) {
            refusalAfterRollback(offer, "decline", failure);
            throw failure;
        }
    }

    /** Revokes an exact path-scoped offer as a current scope owner. */
    public void revoke(String kind, int scopeId, long id, int actorId) {
        requireCompatibleEnclosingTransaction();
        OwnershipOffer offer = find(id);
        if (!Objects.equals(kind, offer.getScopeKind()) || scopeId != offer.getScopeId()) {
            throw notFound();
        }
        if (!tenantWorkScope.unroutedRead(() -> transaction.isScopeOwner(offer, actorId))) {
            throw notFound();
        }
        try {
            routed(offer, actorId, () -> { transaction.revoke(id, actorId, kind, scopeId); return true; });
        } catch (RuntimeException failure) {
            refusalAfterRollback(offer, "revoke", failure);
            throw failure;
        }
    }

    /** Runs a bounded sweep with one complete transaction per discovered offer. */
    public void expire() {
        for (OwnershipOffer offer : tenantWorkScope.unroutedRead(() -> mapper.expiredCandidates(100))) {
            try {
                routed(offer, offer.getOffereeUserId(), () -> { transaction.reconcile(offer.getId()); return true; });
            } catch (RuntimeException failure) {
                log.warn("Ownership expiry failed offerId={} exceptionClass={}", offer.getId(), failure.getClass().getSimpleName());
            }
        }
    }

    /** Closes fenced-organization candidates individually after the parent fence commits. */
    public void invalidateFencedOrganization(int orgId) {
        for (OwnershipOffer offer : tenantWorkScope.unroutedRead(() -> mapper.fencedOrganizationOffers(orgId))) {
            try {
                routed(offer, offer.getOffereeUserId(), () -> { transaction.reconcile(offer.getId()); return true; });
            } catch (RuntimeException failure) {
                log.warn("Ownership lifecycle invalidation failed offerId={} exceptionClass={}", offer.getId(), failure.getClass().getSimpleName());
            }
        }
    }

    /** Resolves the delivery catalog before opening any transaction and restores request identity. */
    public <T> T routed(OwnershipOffer offer, int actorId, Supplier<T> work) {
        Integer destination = offer.getDeliveryWorkspaceId();
        if (destination == null) {
            return tenantWorkScope.unrouted(() -> retryFresh(work));
        }
        Integer activeOrg = tenantWorkScope.unroutedRead(() -> workspaceMapper.getOrgId(destination));
        if (activeOrg == null) {
            var root = tenantWorkScope.unroutedRead(() -> lifecycleMapper.findWorkspaceOrCleanupInOrg(offer.getOrgId(), destination));
            if (root == null) { return tenantWorkScope.unrouted(() -> retryFresh(work)); }
            var route = lifecycleAccess.capture(root, offer.getOrgId());
            return tenantWorkScope.withCatalog(route.catalog(), () -> withIdentity(destination, route.orgId(), actorId, route.catalog(), work));
        }
        return tenantWorkScope.withWorkspacePlacement(destination, (orgId, catalog) ->
            withIdentity(destination, orgId, actorId, catalog, work));
    }

    private <T> T withIdentity(int destination, int orgId, int actorId, String catalog, Supplier<T> work) {
        boolean resolved = tenantContext.isResolved();
        Integer oldWorkspace = tenantContext.getWorkspaceId();
        Integer oldOrg = tenantContext.getOrgId();
        Integer oldUser = tenantContext.getUserId();
        String oldRole = tenantContext.getRole();
        String oldCatalog = tenantContext.getScopeCatalog();
        tenantContext.set(destination, orgId, actorId, "member", catalog);
        try {
            return retryFresh(work);
        } finally {
            if (resolved) {
                tenantContext.set(oldWorkspace, oldOrg, oldUser, oldRole, oldCatalog);
            } else {
                tenantContext.clear();
            }
        }
    }

    /** Pins the path workspace before its existing grant transaction; preliminary membership hides foreign scopes. */
    public MemberRoleChange<MemberDto> changeWorkspaceRole(int workspaceId, int actorId, int targetId,
            String role, Integer roleId, boolean acknowledged) {
        requireCompatibleEnclosingTransaction();
        if (workspaceMapper.getRole(workspaceId, actorId) == null) {
            throw new ForbiddenException("Requires member management permission in this workspace");
        }
        OwnershipOffer scope = new OwnershipOffer();
        scope.setOrgId(workspaceMapper.getOrgId(workspaceId));
        scope.setWorkspaceId(workspaceId);
        scope.setDeliveryWorkspaceId(workspaceId);
        scope.setDeliveryState("DELIVERED");
        scope.setOffereeUserId(targetId);
        try {
            MemberRoleChange<MemberDto> result = routed(scope, actorId, () -> roleId == null
                ? workspaceService.changeMemberRole(workspaceId, actorId, targetId, role, acknowledged)
                : MemberRoleChange.direct(workspaceService.assignCustomRole(workspaceId, actorId, targetId, roleId, acknowledged)));
            return withCreationLabels(result, actorId);
        } catch (RuntimeException failure) {
            if ("owner".equalsIgnoreCase(role)) {
                refusalAfterRollback(scope, "create", failure);
            }
            throw failure;
        }
    }

    /** Pins organization membership edits before their transaction, retaining eligibility secrecy. */
    public MemberRoleChange<OrgMemberDto> changeOrganizationRole(int orgId, int actorId, Integer targetId,
            String email, String role, boolean acknowledged) {
        requireCompatibleEnclosingTransaction();
        orgMemberService.requireOrgOwner(orgId, actorId);
        OwnershipOffer scope = organizationRoute(orgId);
        scope.setOffereeUserId(targetId == null ? actorId : targetId);
        try {
            MemberRoleChange<OrgMemberDto> result = routed(scope, actorId, () -> targetId == null
                ? orgMemberService.setMemberByEmail(orgId, actorId, email, role, acknowledged)
                : orgMemberService.setMember(orgId, actorId, targetId, role, acknowledged));
            return withCreationLabels(result, actorId);
        } catch (RuntimeException failure) {
            if ("owner".equalsIgnoreCase(role)) {
                refusalAfterRollback(scope, "create", failure);
            }
            throw failure;
        }
    }

    /** Routes organization removal before its existing owner and last-owner checks. */
    public void removeOrganizationMember(int orgId, int actorId, int targetId) {
        orgMemberService.requireOrgOwner(orgId, actorId);
        routed(organizationRoute(orgId), actorId, () -> { orgMemberService.removeMember(orgId, actorId, targetId); return true; });
    }

    /** Routes self-only organization departure without introducing a step-up requirement. */
    public void leaveOrganization(int orgId, int actorId) {
        if (orgMemberMapper.getRole(orgId, actorId) == null) {
            throw new ResourceNotFoundException("You are not a member of this organization");
        }
        routed(organizationRoute(orgId), actorId, () -> { orgMemberService.leave(orgId, actorId); return true; });
    }

    /** Delivery always routes the destination before its own transaction, including a pending notice. */
    public <T> T routedDelivery(OwnershipOffer offer, int actorId, Supplier<T> work) {
        OwnershipOffer route = new OwnershipOffer();
        route.setOrgId(offer.getOrgId());
        route.setDeliveryState("DELIVERED");
        route.setDeliveryWorkspaceId(offer.getDeliveryWorkspaceId());
        return routed(route, actorId, work);
    }

    private OwnershipOffer organizationRoute(int orgId) {
        OwnershipOffer scope = new OwnershipOffer();
        scope.setOrgId(orgId);
        scope.setDeliveryWorkspaceId(workspaceMapper.findByOrgId(orgId).stream().map(workspace -> workspace.getId()).findFirst().orElse(null));
        scope.setDeliveryState("DELIVERED");
        return scope;
    }

    private OwnershipOffer find(long id) {
        OwnershipOffer offer = tenantWorkScope.unroutedRead(() -> mapper.findById(id));
        if (offer == null) {
            throw notFound();
        }
        return offer;
    }

    private <T> MemberRoleChange<T> withCreationLabels(MemberRoleChange<T> result, int actorId) {
        return result.offer() == null ? result : tenantWorkScope.unroutedRead(() ->
            new MemberRoleChange<>(result.currentMember(), displayLabelsFor(actorId, true).apply(result.offer())));
    }

    /** Uses non-locking control reads and response-local caches after transition authorization. */
    private UnaryOperator<OwnershipOfferDto> displayLabelsFor(int actorId, boolean scopeOwner) {
        Map<String, String> scopeNames = new HashMap<>();
        Map<Integer, User> parties = new HashMap<>();
        return offer -> {
            String scopeName = scopeNames.computeIfAbsent(offer.scopeKind() + ":" + offer.workspaceId() + ":" + offer.orgId(), key -> {
                if ("WORKSPACE".equals(offer.scopeKind())) {
                    var workspace = workspaceMapper.getActiveById(offer.workspaceId());
                    return workspace == null ? null : workspace.getName();
                }
                var organization = organizationMapper.getById(offer.orgId());
                return organization == null ? null : organization.getName();
            });
            User offeror = parties.computeIfAbsent(offer.offerorUserId(), userMapper::getUserById);
            User offeree = scopeOwner || actorId == offer.offereeUserId()
                ? parties.computeIfAbsent(offer.offereeUserId(), userMapper::getUserById) : null;
            return offer.withDisplayLabels(scopeName, offeror == null ? null : offeror.getDisplayName(),
                offeree == null ? null : offeree.getDisplayName(), offeree == null ? null : offeree.getEmail());
        };
    }

    private void refusalAfterRollback(OwnershipOffer offer, String action, RuntimeException failure) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            auditService.deferStrictFailureIndependentScoped("ownership.offer.refused", "user", offer.getOffereeUserId(),
                offer.getWorkspaceId(), offer.getOrgId(), null, "Ownership offer " + action + " refused",
                failure.getClass().getSimpleName());
            return;
        }
        auditService.recordStrictFailureIndependentScoped("ownership.offer.refused", "user", offer.getOffereeUserId(),
            offer.getWorkspaceId(), offer.getOrgId(), null, "Ownership offer " + action + " refused",
            failure.getClass().getSimpleName());
    }

    private static void requireCompatibleEnclosingTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            OwnershipOfferWriter.requireReadCommitted();
        }
    }

    private static <T> T retryFresh(Supplier<T> work) {
        boolean enclosing = TransactionSynchronizationManager.isActualTransactionActive();
        for (int attempt = 1; ; attempt++) {
            try {
                return work.get();
            } catch (RuntimeException failure) {
                boolean deadlock = false;
                for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                    if (cause instanceof SQLException sql && sql.getErrorCode() == 1213) { deadlock = true; }
                }
                if (enclosing || !deadlock || attempt == 3) { throw failure; }
            }
        }
    }

    private static ResourceNotFoundException notFound() {
        return new ResourceNotFoundException("Ownership offer not found");
    }
}
