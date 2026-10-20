package ooo.klae.connex.backend.services;

import java.sql.Connection;
import java.util.Map;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.springframework.transaction.support.TransactionSynchronization;
import ooo.klae.connex.backend.tenant.TenantContext;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.beans.OwnershipOffer;
import ooo.klae.connex.backend.dto.OwnershipOfferDto;
import ooo.klae.connex.backend.exceptions.ConflictException;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.exceptions.RecentAuthenticationRequiredException;
import ooo.klae.connex.backend.mappers.OwnershipOfferMapper;
import ooo.klae.connex.backend.mappers.NotificationMapper;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.mappers.WebauthnCredentialMapper;
import ooo.klae.connex.backend.session.StepUpProof;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.mappers.OrganizationMapper;

/** Persists consent only after its ordered authority locks; strict audits share the transaction. */
@Component
@RequiredArgsConstructor
public class OwnershipOfferWriter {
    public static final String NOTICE_TYPE = "ownership.offer";
    private final OwnershipOfferMapper mapper;
    private final WorkspaceMapper workspaceMapper;
    private final OrganizationMapper organizationMapper;
    private final UserMapper userMapper;
    private final WebauthnCredentialMapper credentialMapper;
    private final NotificationMapper notificationMapper;
    private final OwnershipOfferNotificationStateVersionService stateVersionService;
    private final OwnershipOfferEligibility eligibility;
    private final SessionSecurityService sessionSecurityService;
    private final AuditService auditService;
    private final TenantContext tenantContext;

    /** Refuses an enclosing transaction whose isolation cannot support current authority reads. */
    public static void requireReadCommitted() {
        Integer isolation = TransactionSynchronizationManager.getCurrentTransactionIsolationLevel();
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || isolation == null || isolation != Connection.TRANSACTION_READ_COMMITTED) {
            throw new IllegalStateException("Ownership offers require an active READ COMMITTED transaction");
        }
    }

    /** Rechecks recent, sufficient authentication and the authenticated session epoch under roots. */
    public void requireActorAuthentication(int actorId) {
        sessionSecurityService.requireRecentAuthentication(actorId);
        StepUpProof proof = sessionSecurityService.currentStepUpProof(actorId);
        if (proof == null || credentialMapper.findOwnedRowId(proof.credentialRowId(), actorId) == null) {
            throw new RecentAuthenticationRequiredException();
        }
        Integer epoch = sessionSecurityService.currentSessionEpoch();
        if (epoch == null || !epoch.equals(userMapper.currentSessionEpoch(actorId))) {
            throw new RecentAuthenticationRequiredException();
        }
    }

    /** Fences every newly referenced account while the caller retains its initial account lock. */
    public void requireUnreserved(int userId) {
        if (!Boolean.FALSE.equals(userMapper.isAccountDeletionReservedForShare(userId))) {
            throw new ResourceNotFoundException("Ownership offer not found");
        }
    }

    /** Creates or returns the same offeror's open consent without extending its deadline. */
    public OwnershipOfferDto createLocked(int orgId, Integer workspaceId, int offerorId, int offereeId) {
        requireReadCommitted();
        requireActorAuthentication(offerorId);
        if (offerorId == offereeId) {
            throw new ConflictException("Ownership cannot be offered to yourself");
        }
        String kind = workspaceId == null ? "ORGANIZATION" : "WORKSPACE";
        int scopeId = workspaceId == null ? orgId : workspaceId;
        OwnershipOffer existing = mapper.lockOpen(kind, scopeId, offereeId);
        if (existing != null) {
            if (mapper.isExpired(existing.getId())) {
                closeLocked(existing, "EXPIRED", null, "expired");
            } else if (!eligibility.isValid(existing)) {
                closeLocked(existing, "INVALIDATED", null, "eligibility_lost");
            } else if (existing.getOfferorUserId() == offerorId) {
                return OwnershipOfferDto.from(existing);
            } else {
                throw new ConflictException("Another owner has already offered ownership to this account");
            }
        }
        OwnershipOffer offer = new OwnershipOffer();
        offer.setOrgId(orgId);
        offer.setWorkspaceId(workspaceId);
        offer.setScopeKind(kind);
        offer.setOfferorUserId(offerorId);
        offer.setOffereeUserId(offereeId);
        if (workspaceId != null) {
            offer.setMembershipId(workspaceMapper.getMembershipGenerationId(workspaceId, offereeId));
        }
        Integer destination = workspaceId == null ? mapper.findDeliveryWorkspace(orgId, offereeId) : workspaceId;
        offer.setDeliveryWorkspaceId(destination);
        offer.setDeliveryState(destination == null ? "NONE" : "PENDING");
        if (destination != null) {
            offer.setDeliveryMembershipId(workspaceMapper.getMembershipGenerationId(destination, offereeId));
        }
        mapper.insert(offer);
        OwnershipOffer stored = mapper.findById(offer.getId());
        audit(stored, "created");
        return OwnershipOfferDto.from(stored);
    }

    /** Closes consent while holding offer X, then resolves the notice in that same transaction. */
    public void closeLocked(OwnershipOffer offer, String state, Integer actorId, String reason) {
        if (mapper.close(offer.getId(), state, actorId, reason) == 0) {
            return;
        }
        resolveNoticeLocked(offer);
        audit(offer, state.toLowerCase());
    }

    /** Resolves only the exact offer's recipient and dedupe key; no inbox query reads ledger state. */
    public void resolveNoticeLocked(OwnershipOffer offer) {
        if (offer.getDeliveryWorkspaceId() == null || !"DELIVERED".equals(offer.getDeliveryState())) {
            return;
        }
        withNoticeScope(offer, () -> {
            if (notificationMapper.resolveOwnershipOffer(offer.getDeliveryWorkspaceId(),
                    offer.getOffereeUserId(), Long.toString(offer.getId())) > 0) {
                stateVersionService.markChanged(offer.getOffereeUserId());
            }
        });
    }

    /** Switches only identity within an already-routed catalog; it never repins a connection. */
    public void withNoticeScope(OwnershipOffer offer, Runnable work) {
        TenantContext.State previousTenant = tenantContext.snapshot();
        String previousCatalog = tenantContext.getScopeCatalog();
        tenantContext.set(offer.getDeliveryWorkspaceId(), offer.getOrgId(), offer.getOffereeUserId(), "member", previousCatalog);
        try {
            work.run();
        } finally {
            tenantContext.restore(previousTenant);
        }
    }

    /** Invalidates stale offers after a scope mutation, before that mutation takes its audit head. */
    public void invalidateScopeLocked(int orgId, Integer workspaceId) {
        invalidateScopeLocked(orgId, workspaceId, false);
    }

    /** Emits residual invalidation audits while their parent root still exists. */
    public void invalidateScopeBeforeRootDeletionLocked(int orgId, Integer workspaceId) {
        invalidateScopeLocked(orgId, workspaceId, true);
    }

    private void invalidateScopeLocked(int orgId, Integer workspaceId, boolean beforeRootDeletion) {
        requireReadCommitted();
        List<OwnershipOffer> invalid = new ArrayList<>();
        for (OwnershipOffer candidate : discoverLockedScope(orgId, workspaceId)) {
            if (!eligibility.isValid(candidate)) {
                invalidateDeferred(candidate, invalid);
            }
        }
        if (beforeRootDeletion) { emitInvalidationAudits(invalid); }
        else { deferInvalidationAudits(invalid); }
    }

    /** A removal and later rejoin must not restore consent based on the departed tenancy. */
    public void invalidateDepartingMembershipLocked(int orgId, int workspaceId, int userId) {
        requireReadCommitted();
        invalidatePartyLocked(orgId, workspaceId, userId);
        if (!mapper.hasOtherQualifyingTenancy(orgId, userId, workspaceId)) {
            List<OwnershipOffer> invalid = new ArrayList<>();
            for (OwnershipOffer candidate : discoverLockedScope(orgId, null)) {
                if (candidate.getOffereeUserId() == userId) {
                    invalidateDeferred(candidate, invalid);
                }
            }
            deferInvalidationAudits(invalid);
        }
    }

    /** Departure invalidates both party references before cleanup or a new membership generation. */
    public void invalidatePartyLocked(int orgId, Integer workspaceId, int userId) {
        requireReadCommitted();
        List<OwnershipOffer> invalid = new ArrayList<>();
        for (OwnershipOffer candidate : discoverLockedScope(orgId, workspaceId)) {
            if (candidate.getOfferorUserId() == userId || candidate.getOffereeUserId() == userId) {
                invalidateDeferred(candidate, invalid);
            }
        }
        deferInvalidationAudits(invalid);
    }

    private List<OwnershipOffer> discoverLockedScope(int orgId, Integer workspaceId) {
        organizationMapper.lockByIdForShare(orgId);
        return mapper.discoverScope(orgId, workspaceId);
    }

    /** Detachment deletes destination notices under offer X even when organization consent survives. */
    public void detachDestinationLocked(int orgId, int workspaceId, int userId) {
        requireReadCommitted();
        organizationMapper.lockByIdForShare(orgId);
        for (OwnershipOffer candidate : mapper.workspaceReferences(workspaceId)) {
            if (candidate.getOffereeUserId() != userId || !Integer.valueOf(workspaceId).equals(candidate.getDeliveryWorkspaceId())) {
                continue;
            }
            requireReadCommitted();
            OwnershipOffer locked = mapper.lockById(candidate.getId());
            if (locked != null) {
                eraseNoticeLocked(locked);
                mapper.clearDeliveryWorkspace(locked.getId(), workspaceId);
            }
        }
    }

    /** Erases notice history only while the caller retains its exact offer X lock. */
    public void eraseNoticeLocked(OwnershipOffer offer) {
        if (offer.getDeliveryWorkspaceId() != null) {
            withNoticeScope(offer, () -> {
                if (notificationMapper.deleteOwnershipOffer(offer.getDeliveryWorkspaceId(), offer.getOffereeUserId(),
                        Long.toString(offer.getId())) > 0) {
                    stateVersionService.markChanged(offer.getOffereeUserId());
                }
            });
        }
    }

    /** Lifecycle cleanup locks every discovered destination reference before the first offer audit. */
    public void invalidateWorkspaceLifecycleLocked(int orgId, int workspaceId) {
        invalidateWorkspaceLifecycleLocked(orgId, workspaceId, false);
    }

    /** Flushes strict residual audits before terminal root deletion. */
    public void invalidateWorkspaceBeforeRootDeletionLocked(int orgId, int workspaceId) {
        invalidateWorkspaceLifecycleLocked(orgId, workspaceId, true);
    }

    private void invalidateWorkspaceLifecycleLocked(int orgId, int workspaceId, boolean beforeRootDeletion) {
        requireReadCommitted();
        List<OwnershipOffer> locked = new ArrayList<>();
        for (OwnershipOffer candidate : mapper.workspaceReferences(workspaceId)) {
            OwnershipOffer offer = mapper.lockById(candidate.getId());
            if (offer != null) {
                locked.add(offer);
            }
        }
        List<OwnershipOffer> invalidated = new ArrayList<>();
        for (OwnershipOffer offer : locked) {
            if ("OPEN".equals(offer.getState())
                    && (Integer.valueOf(workspaceId).equals(offer.getWorkspaceId()) || !eligibility.isValid(offer))) {
                if (mapper.close(offer.getId(), "INVALIDATED", null, "lifecycle_fence") == 1) {
                    resolveNoticeLocked(offer);
                    invalidated.add(offer);
                }
            }
            if (Integer.valueOf(workspaceId).equals(offer.getDeliveryWorkspaceId())) {
                eraseNoticeLocked(offer);
                mapper.clearDeliveryWorkspace(offer.getId(), workspaceId);
            }
        }
        if (beforeRootDeletion) { emitInvalidationAudits(invalidated); }
        else { deferInvalidationAudits(invalidated); }
    }

    private void invalidateDeferred(OwnershipOffer candidate, List<OwnershipOffer> invalid) {
        requireReadCommitted();
        OwnershipOffer locked = mapper.lockById(candidate.getId());
        if (locked != null && "OPEN".equals(locked.getState())
                && mapper.close(locked.getId(), "INVALIDATED", null, "eligibility_lost") == 1) {
            resolveNoticeLocked(locked);
            invalid.add(locked);
        }
    }

    private void deferInvalidationAudits(List<OwnershipOffer> invalid) {
        if (invalid.isEmpty()) {
            return;
        }
        invalid.sort(Comparator.comparingInt((OwnershipOffer offer) -> offer.getWorkspaceId() == null ? 1 : 0)
            .thenComparingInt(OwnershipOffer::getScopeId).thenComparingLong(OwnershipOffer::getId));
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void beforeCommit(boolean readOnly) {
                for (OwnershipOffer offer : invalid) {
                    audit(offer, "invalidated");
                }
            }
        });
    }

    private void emitInvalidationAudits(List<OwnershipOffer> offers) {
        offers.stream().sorted(Comparator.comparingInt((OwnershipOffer offer) -> offer.getWorkspaceId() == null ? 1 : 0)
            .thenComparingInt(OwnershipOffer::getScopeId).thenComparingLong(OwnershipOffer::getId))
            .forEach(offer -> audit(offer, "invalidated"));
    }

    /** Emits a strictly scoped consent audit after the offer and notice writes. */
    public void audit(OwnershipOffer offer, String transition) {
        auditService.recordStrictScoped("ownership.offer." + transition, "user", offer.getOffereeUserId(),
            offer.getWorkspaceId(), offer.getOrgId(), null, "Ownership offer " + transition,
            Map.of("offerId", Long.toString(offer.getId()), "offerorUserId", offer.getOfferorUserId(),
                "offereeUserId", offer.getOffereeUserId(), "scopeKind", offer.getScopeKind()));
    }
}
