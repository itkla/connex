package ooo.klae.connex.backend.services;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.beans.OwnershipOffer;
import ooo.klae.connex.backend.dto.OwnershipOfferDto;
import ooo.klae.connex.backend.dto.OwnershipOfferAcceptanceDto;
import ooo.klae.connex.backend.exceptions.ConflictException;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.mappers.OwnershipOfferMapper;
import ooo.klae.connex.backend.mappers.OrgMemberMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;

/** Ordered READ COMMITTED consent transitions, separate from session-bound grant entry points. */
@Component
@RequiredArgsConstructor
public class OwnershipOfferTransaction {
    private final OwnershipOfferMapper mapper;
    private final WorkspaceMapper workspaceMapper;
    private final OrgMemberMapper orgMemberMapper;
    private final WorkspaceService workspaceService;
    private final OrgMemberService orgMemberService;
    private final OwnershipOfferWriter writer;
    private final OwnershipOfferEligibility eligibility;
    private final OwnershipMembershipWriter membershipWriter;
    private final SessionSecurityService sessionSecurityService;
    private final PrivilegedAccountService privilegedAccountService;
    private final AuditService auditService;

    /** Claims consent and promotes only its authenticated offeree, atomically with strict audits. */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public OwnershipOfferAcceptanceDto accept(long id, int actorId, boolean acknowledged) {
        OwnershipOfferWriter.requireReadCommitted();
        sessionSecurityService.requireRecentAuthentication(actorId);
        OwnershipOffer candidate = selfOffer(id, actorId);
        lockScope(candidate, actorId, true);
        writer.requireActorAuthentication(actorId);
        OwnershipOffer locked = selfOfferLocked(id, actorId);
        if ("ACCEPTED".equals(locked.getState())) {
            return acceptance(locked);
        }
        if (!"OPEN".equals(locked.getState()) || mapper.isExpired(id) || !eligibility.isValid(locked)) {
            throw new ConflictException("Ownership offer is no longer available");
        }
        ConfinementBaseline baseline = privilegedAccountService.confinementBaseline(
            () -> locked.getOrgId(), List.of(actorId));
        if (mapper.accept(id, actorId) != 1) {
            throw new ConflictException("Ownership offer is no longer available");
        }
        locked.setState("ACCEPTED");
        membershipWriter.grantAccepted(locked);
        privilegedAccountService.requireConfinementAcknowledged(baseline, acknowledged);
        writer.resolveNoticeLocked(locked);
        writer.audit(locked, "accepted");
        auditService.recordStrictScoped(locked.getWorkspaceId() == null ? "org.member.set" : "workspace.member.role",
            "user", actorId, locked.getWorkspaceId(), locked.getOrgId(), null,
            "Accepted ownership", Map.of("offerId", Long.toString(id)));
        return acceptance(mapper.findById(id));
    }

    /** Rejection grants nothing and remains reachable without recent authentication. */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void decline(long id, int actorId) {
        OwnershipOfferWriter.requireReadCommitted();
        OwnershipOffer candidate = selfOffer(id, actorId);
        lockScope(candidate, actorId, true);
        OwnershipOffer locked = selfOfferLocked(id, actorId);
        if ("DECLINED".equals(locked.getState())) {
            return;
        }
        if (!"OPEN".equals(locked.getState())) {
            throw new ConflictException("Ownership offer is no longer available");
        }
        writer.closeLocked(locked, "DECLINED", actorId, "declined");
    }

    /** Any current raw scope owner may revoke; all three account references are fenced first. */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void revoke(long id, int actorId, String scopeKind, int scopeId) {
        OwnershipOfferWriter.requireReadCommitted();
        sessionSecurityService.requireRecentAuthentication(actorId);
        OwnershipOffer candidate = mapper.findById(id);
        if (candidate == null || !scopeKind.equals(candidate.getScopeKind()) || scopeId != candidate.getScopeId()) {
            throw notFound();
        }
        lockScope(candidate, actorId, true);
        writer.requireActorAuthentication(actorId);
        if (!isScopeOwner(candidate, actorId)) {
            throw notFound();
        }
        OwnershipOffer locked = mapper.lockById(id);
        if (locked == null) {
            throw notFound();
        }
        if ("REVOKED".equals(locked.getState())) {
            return;
        }
        if (!"OPEN".equals(locked.getState())) {
            throw new ConflictException("Ownership offer is no longer available");
        }
        writer.closeLocked(locked, "REVOKED", actorId, "revoked");
    }

    /** Processes one discovered candidate without requiring the offeror's continuing authority. */
    @Transactional(isolation = Isolation.READ_COMMITTED, propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public void reconcile(long id) {
        OwnershipOfferWriter.requireReadCommitted();
        OwnershipOffer candidate = mapper.findById(id);
        if (candidate == null || !"OPEN".equals(candidate.getState())) {
            return;
        }
        lockScope(candidate, null, false);
        OwnershipOffer locked = mapper.lockById(id);
        if (locked == null || !"OPEN".equals(locked.getState())) {
            return;
        }
        if (mapper.isExpired(id)) {
            writer.closeLocked(locked, "EXPIRED", null, "expired");
        } else if (!eligibility.isValid(locked)) {
            writer.closeLocked(locked, "INVALIDATED", null, "eligibility_lost");
        }
    }

    /** Locks ordinary notice mutations in the same prefix as rejection; it grants no privilege. */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void lockNotice(long id, int actorId) {
        OwnershipOfferWriter.requireReadCommitted();
        OwnershipOffer candidate = selfOffer(id, actorId);
        lockScope(candidate, actorId, true);
        selfOfferLocked(id, actorId);
    }

    /** Executes one bulk-inbox notice mutation in its own complete ownership-lock transaction. */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public <T> T mutateNotice(long id, int actorId, Supplier<T> work) {
        OwnershipOfferWriter.requireReadCommitted();
        lockNotice(id, actorId);
        return work.get();
    }

    private void lockScope(OwnershipOffer offer, Integer actorId, boolean reservations) {
        List<Integer> users = new ArrayList<>(List.of(offer.getOfferorUserId(), offer.getOffereeUserId()));
        if (actorId != null) {
            users.add(actorId);
        }
        if (offer.getWorkspaceId() == null) {
            orgMemberService.lockOfferAccounts(users, reservations);
            orgMemberService.lockOwnershipOfferScope(offer.getOrgId(), offer.getOffereeUserId());
        } else {
            workspaceService.lockOfferAccounts(users, reservations);
            workspaceService.lockOwnershipOfferScope(offer.getWorkspaceId(), users, offer.getOffereeUserId());
        }
    }

    /** Scope-list and revoke authority is raw ownership, independent of grant-ceiling overlays. */
    boolean isScopeOwner(OwnershipOffer offer, int actorId) {
        return offer.getWorkspaceId() == null
            ? "owner".equals(orgMemberMapper.getRole(offer.getOrgId(), actorId))
            : "owner".equals(workspaceMapper.getRole(offer.getWorkspaceId(), actorId));
    }

    private OwnershipOffer selfOffer(long id, int actorId) {
        OwnershipOffer offer = mapper.findById(id);
        if (offer == null || offer.getOffereeUserId() != actorId) {
            throw notFound();
        }
        return offer;
    }

    private OwnershipOffer selfOfferLocked(long id, int actorId) {
        OwnershipOffer offer = mapper.lockById(id);
        if (offer == null || offer.getOffereeUserId() != actorId) {
            throw notFound();
        }
        return offer;
    }

    private OwnershipOfferAcceptanceDto acceptance(OwnershipOffer offer) {
        Object member = offer.getWorkspaceId() == null
            ? orgMemberService.currentMember(offer.getOrgId(), offer.getOffereeUserId())
            : workspaceMapper.getMember(offer.getWorkspaceId(), offer.getOffereeUserId());
        return new OwnershipOfferAcceptanceDto(OwnershipOfferDto.from(offer), member,
            privilegedAccountService.standing(offer.getOffereeUserId()),
            privilegedAccountService.enforcedStanding(offer.getOffereeUserId()));
    }

    private static ResourceNotFoundException notFound() {
        return new ResourceNotFoundException("Ownership offer not found");
    }
}
