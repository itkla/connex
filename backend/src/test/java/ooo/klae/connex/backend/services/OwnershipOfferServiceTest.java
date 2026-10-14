package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.reset;

import java.util.List;
import java.util.ArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import ooo.klae.connex.backend.beans.Organization;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.beans.WorkspaceRole;
import ooo.klae.connex.backend.config.PrivilegedMfaProperties;
import ooo.klae.connex.backend.dto.MemberRoleChange;
import ooo.klae.connex.backend.dto.MemberDto;
import ooo.klae.connex.backend.dto.OwnershipOfferDto;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.ConflictException;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.exceptions.PrivilegedMfaConfinementAcknowledgementRequiredException;
import ooo.klae.connex.backend.exceptions.RecentAuthenticationRequiredException;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.mappers.OrgMemberMapper;
import ooo.klae.connex.backend.mappers.OrganizationMapper;
import ooo.klae.connex.backend.mappers.OwnershipOfferMapper;
import ooo.klae.connex.backend.mappers.RoleMapper;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.support.OwnershipOfferTestFixtures;
import ooo.klae.connex.backend.tenant.Permission;

/** Consent, live authority, generation, MFA and atomic audit behavior against real MySQL. */
@Transactional(isolation = Isolation.READ_COMMITTED)
class OwnershipOfferServiceTest extends AbstractServiceTest {
    @Autowired private WorkspaceService workspaces;
    @Autowired private OrgMemberService organizations;
    @Autowired private OwnershipOfferService offers;
    @Autowired private OwnershipOfferMapper offerMapper;
    @Autowired private OrgMemberMapper orgMembers;
    @Autowired private OrganizationMapper organizationMapper;
    @Autowired private RoleMapper roles;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PrivilegedMfaProperties mfa;
    @Autowired private SystemActor system;
    @MockitoSpyBean private AuditService audit;
    @MockitoSpyBean private UserMapper reservationUsers;
    private int ownerCredential;
    private final List<Integer> committedUsers = new ArrayList<>();

    @Override protected User newUser() {
        User user = super.newUser();
        if (!TransactionSynchronizationManager.isActualTransactionActive()) { committedUsers.add(user.getId()); }
        return user;
    }

    @BeforeEach void enrolledOwner() {
        mfa.setAttestationEnforced("false");
        ownerCredential = OwnershipOfferTestFixtures.stepUpCurrent(jdbc, currentUser);
    }

    @AfterEach void resetPolicyAndAudit() {
        mfa.setAttestationEnforced("false");
        reset(audit, reservationUsers);
        for (int userId : committedUsers) {
            jdbc.update("DELETE FROM app_user WHERE id = ?", userId);
        }
        committedUsers.clear();
    }

    private MemberRoleChange<MemberDto> offer(User target) {
        return workspaces.changeMemberRole(workspace.getId(), currentUser.getId(), target.getId(), "owner");
    }

    private long id(OwnershipOfferDto offer) { return Long.parseLong(offer.id()); }

    private void asOfferee(User target) {
        authenticateAs(target, workspace.getId());
        OwnershipOfferTestFixtures.stepUpCurrent(jdbc, target);
    }

    @Test void workspacePromotionRetainsRoleUntilAcceptanceAndCreatesNoCoverage() {
        User target = newUser();
        var offered = offer(target);
        assertEquals("member", offered.currentMember().getBuiltInRole());
        assertEquals("member", workspaceMapper.getRole(workspace.getId(), target.getId()));
        assertEquals(1, offers.listForSelf(target.getId()).size());
        asOfferee(target);
        var accepted = offers.accept(id(offered.offer()), target.getId(), false);
        assertEquals("ACCEPTED", accepted.offer().state());
        assertEquals("owner", workspaceMapper.getRole(workspace.getId(), target.getId()));
        assertTrue(offers.listForSelf(target.getId()).isEmpty());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM privileged_credential_attestation p JOIN webauthn_credential c ON c.id = p.credential_row_id JOIN webauthn_user_entity u ON u.id = c.user_entity_user_id WHERE u.user_id = ?", Integer.class, target.getId()));
    }

    @Test void offeredPromotionPreservesOverlayAndAcceptanceClearsIt() {
        User target = newUser();
        WorkspaceRole overlay = new WorkspaceRole();
        overlay.setWorkspaceId(workspace.getId()); overlay.setName("Read only " + unique());
        roles.insertRole(overlay);
        roles.insertPermissions(workspace.getId(), overlay.getId(), List.of("PERSON_CREATE"));
        workspaceMapper.setMemberCustomRole(workspace.getId(), target.getId(), overlay.getId());
        var offered = offer(target);
        assertEquals(overlay.getId(), offered.currentMember().getRoleId());
        asOfferee(target);
        offers.accept(id(offered.offer()), target.getId(), false);
        assertNull(workspaceMapper.getMember(workspace.getId(), target.getId()).getRoleId());
    }

    @Test void sameOfferorRetryReturnsSameIdAndDeadlineAndAnotherOwnerConflicts() {
        User target = newUser();
        var first = offer(target);
        var retry = offer(target);
        assertEquals(first.offer(), retry.offer());
        User other = newUser();
        workspaceMapper.updateMemberRole(workspace.getId(), other.getId(), "owner");
        authenticateAs(other, workspace.getId()); OwnershipOfferTestFixtures.stepUpCurrent(jdbc, other);
        assertThrows(ConflictException.class, () -> workspaces.changeMemberRole(workspace.getId(), other.getId(), target.getId(), "owner"));
        assertEquals("member", workspaceMapper.getRole(workspace.getId(), target.getId()));
    }

    @Test void acceptedRetryNeverRegrantsAfterDemotion() {
        User target = newUser(); var offered = offer(target); asOfferee(target);
        offers.accept(id(offered.offer()), target.getId(), false);
        authenticateAs(currentUser, workspace.getId()); OwnershipOfferTestFixtures.stepUpCurrent(jdbc, currentUser);
        workspaces.changeMemberRole(workspace.getId(), currentUser.getId(), target.getId(), "member");
        asOfferee(target);
        assertEquals("ACCEPTED", offers.accept(id(offered.offer()), target.getId(), false).offer().state());
        assertEquals("member", workspaceMapper.getRole(workspace.getId(), target.getId()));
    }

    @Test void declineIsSelfOnlyNeedsNoStepUpAndIsIdempotent() {
        User target = newUser(); var offered = offer(target);
        assertThrows(ResourceNotFoundException.class, () -> offers.decline(id(offered.offer()), currentUser.getId()));
        authenticateAs(target, workspace.getId());
        offers.decline(id(offered.offer()), target.getId()); offers.decline(id(offered.offer()), target.getId());
        assertEquals("DECLINED", offerMapper.findById(id(offered.offer())).getState());
        assertEquals("member", workspaceMapper.getRole(workspace.getId(), target.getId()));
        OwnershipOfferTestFixtures.stepUpCurrent(jdbc, target);
        assertThrows(ConflictException.class, () -> offers.accept(id(offered.offer()), target.getId(), false));
    }

    @Test void anotherCurrentOwnerCanRevokeAndRetriesDoNotChangeAttribution() {
        User target = newUser(); User third = newUser(); var offered = offer(target);
        workspaceMapper.updateMemberRole(workspace.getId(), third.getId(), "owner");
        authenticateAs(third, workspace.getId()); OwnershipOfferTestFixtures.stepUpCurrent(jdbc, third);
        offers.revoke("WORKSPACE", workspace.getId(), id(offered.offer()), third.getId());
        offers.revoke("WORKSPACE", workspace.getId(), id(offered.offer()), third.getId());
        assertEquals(third.getId(), offerMapper.findById(id(offered.offer())).getClosedByUserId());
        assertThrows(ResourceNotFoundException.class, () -> offers.revoke("ORGANIZATION", workspaceMapper.getOrgId(workspace.getId()), id(offered.offer()), third.getId()));
    }

    @Test void pendingTargetsAndEveryInternalOwnerJoinAreRefused() {
        User target = newUser();
        jdbc.update("UPDATE workspace_member SET status = 'pending' WHERE workspace_id = ? AND user_id = ?", workspace.getId(), target.getId());
        assertThrows(BadRequestException.class, () -> offer(target));
        assertThrows(BadRequestException.class, () -> workspaces.addPendingMember(workspace.getId(), currentUser, target, "owner"));
        assertThrows(BadRequestException.class, () -> workspaces.ensureActiveMember(workspace.getId(), target.getId(), "owner"));
        workspaceMapper.updateMemberRole(workspace.getId(), target.getId(), "owner");
        assertThrows(BadRequestException.class, () -> offer(target));
        assertThrows(BadRequestException.class, () -> workspaces.approveMembership(workspace.getId(), target.getId()));
        assertEquals("pending", workspaceMapper.getMember(workspace.getId(), target.getId()).getStatus());
    }

    @Test void adminChangesAndExistingOwnerRepairRemainDirect() {
        User target = newUser();
        var admin = workspaces.changeMemberRole(workspace.getId(), currentUser.getId(), target.getId(), "admin");
        assertNull(admin.offer()); assertEquals("admin", admin.currentMember().getBuiltInRole());
        var repaired = workspaces.changeMemberRole(workspace.getId(), currentUser.getId(), currentUser.getId(), "owner");
        assertNull(repaired.offer()); assertEquals("owner", repaired.currentMember().getBuiltInRole());
        assertTrue(offers.listForSelf(target.getId()).isEmpty());
    }

    @Test void aFullCustomAdministratorStillCannotOfferRawOwnership() {
        User target = newUser();
        WorkspaceRole delegate = new WorkspaceRole(); delegate.setWorkspaceId(workspace.getId()); delegate.setName("Delegate " + unique());
        roles.insertRole(delegate);
        roles.insertPermissions(workspace.getId(), delegate.getId(), Permission.grantableSet().stream().map(Enum::name).toList());
        workspaceMapper.updateMemberRole(workspace.getId(), currentUser.getId(), "member");
        workspaceMapper.setMemberCustomRole(workspace.getId(), currentUser.getId(), delegate.getId());
        assertThrows(ForbiddenException.class, () -> offer(target));
        assertTrue(offers.listForSelf(target.getId()).isEmpty());
    }

    @Test void aNarrowOwnerOverlayCannotOfferOwnership() {
        User target = newUser();
        WorkspaceRole overlay = new WorkspaceRole(); overlay.setWorkspaceId(workspace.getId()); overlay.setName("Narrow " + unique());
        roles.insertRole(overlay); roles.insertPermissions(workspace.getId(), overlay.getId(), List.of("MEMBER_MANAGE"));
        workspaceMapper.setMemberCustomRole(workspace.getId(), currentUser.getId(), overlay.getId());
        assertThrows(ForbiddenException.class, () -> offer(target));
    }

    @Test void systemAutomationCannotOfferOwnershipInEitherScope() {
        User target = newUser(); int actorId = system.user().getId();
        assertThrows(ForbiddenException.class, () -> workspaces.changeMemberRole(workspace.getId(), actorId, target.getId(), "owner"));
        assertThrows(ForbiddenException.class, () -> organizations.setMember(workspace.getOrgId(), actorId, target.getId(), "owner"));
        assertTrue(offerMapper.listForOfferee(target.getId()).isEmpty());
    }

    @Test void offerorDemotionInvalidatesConsentAndRestorationCannotReviveIt() {
        User target = newUser(); User coOwner = newUser(); var offered = offer(target);
        workspaceMapper.updateMemberRole(workspace.getId(), coOwner.getId(), "owner");
        authenticateAs(coOwner, workspace.getId()); OwnershipOfferTestFixtures.stepUpCurrent(jdbc, coOwner);
        workspaces.changeMemberRole(workspace.getId(), coOwner.getId(), currentUser.getId(), "member");
        assertEquals("INVALIDATED", offerMapper.findById(id(offered.offer())).getState());
        workspaceMapper.updateMemberRole(workspace.getId(), currentUser.getId(), "owner");
        asOfferee(target);
        assertThrows(ConflictException.class, () -> offers.accept(id(offered.offer()), target.getId(), false));
    }

    @Test void departureAndRejoinCannotRestoreWorkspaceConsent() {
        User target = newUser(); var offered = offer(target);
        workspaces.removeMember(workspace.getId(), currentUser.getId(), target.getId());
        workspaceMapper.addMember(workspace.getId(), target.getId(), "member");
        assertEquals("INVALIDATED", offerMapper.findById(id(offered.offer())).getState());
        asOfferee(target);
        assertThrows(ConflictException.class, () -> offers.accept(id(offered.offer()), target.getId(), false));
    }

    @Test void aChangedGenerationRefusesEvenAStoredOpenOffer() {
        User target = newUser(); var offered = offer(target);
        workspaceMapper.removeMember(workspace.getId(), target.getId());
        workspaceMapper.addMember(workspace.getId(), target.getId(), "member");
        asOfferee(target);
        assertTrue(offers.listForSelf(target.getId()).isEmpty());
        assertThrows(ConflictException.class, () -> offers.accept(id(offered.offer()), target.getId(), false));
    }

    @Test void acceptanceRechecksBothDeletionReservations() {
        User target = newUser(); var offered = offer(target);
        jdbc.update("UPDATE app_user SET account_deletion_reserved_at = UTC_TIMESTAMP(6), account_deletion_reservation_owner = 'ownership-test-reservation', account_deletion_reservation_until = DATE_ADD(UTC_TIMESTAMP(6), INTERVAL 1 HOUR) WHERE id = ?", currentUser.getId());
        asOfferee(target);
        assertThrows(ResourceNotFoundException.class, () -> offers.accept(id(offered.offer()), target.getId(), false));
        assertEquals("OPEN", offerMapper.findById(id(offered.offer())).getState());
        assertEquals("member", workspaceMapper.getRole(workspace.getId(), target.getId()));
    }

    @Test @Transactional(propagation = Propagation.NOT_SUPPORTED) void createAndThirdPartyRevokeFenceEveryNewReference() {
        User target = newUser();
        jdbc.update("UPDATE app_user SET account_deletion_reserved_at = UTC_TIMESTAMP(6), account_deletion_reservation_owner = 'ownership-test-reservation', account_deletion_reservation_until = DATE_ADD(UTC_TIMESTAMP(6), INTERVAL 1 HOUR) WHERE id = ?", target.getId());
        assertThrows(ResourceNotFoundException.class, () -> offer(target));
        jdbc.update("UPDATE app_user SET account_deletion_reserved_at = NULL, account_deletion_reservation_owner = NULL, account_deletion_reservation_until = NULL WHERE id = ?", target.getId());
        var offered = offer(target); User third = newUser(); workspaceMapper.updateMemberRole(workspace.getId(), third.getId(), "owner");
        authenticateAs(third, workspace.getId()); OwnershipOfferTestFixtures.stepUpCurrent(jdbc, third);
        jdbc.update("UPDATE app_user SET account_deletion_reserved_at = UTC_TIMESTAMP(6), account_deletion_reservation_owner = 'ownership-test-reservation', account_deletion_reservation_until = DATE_ADD(UTC_TIMESTAMP(6), INTERVAL 1 HOUR) WHERE id = ?", third.getId());
        assertThrows(ResourceNotFoundException.class, () -> offers.revoke("WORKSPACE", workspace.getId(), id(offered.offer()), third.getId()));
        assertEquals("OPEN", offerMapper.findById(id(offered.offer())).getState());
    }

    @ParameterizedTest
    @ValueSource(strings = {"WORKSPACE:create:offeror", "WORKSPACE:create:offeree", "ORGANIZATION:create:offeror", "ORGANIZATION:create:offeree",
        "WORKSPACE:revoke:offeror", "WORKSPACE:revoke:offeree", "WORKSPACE:revoke:actor", "ORGANIZATION:revoke:offeror", "ORGANIZATION:revoke:offeree", "ORGANIZATION:revoke:actor"})
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void everyCreateAndRevokeReferenceIsFencedSeparately(String scenario) {
        String[] parts = scenario.split(":"); boolean organization = parts[0].equals("ORGANIZATION"); boolean create = parts[1].equals("create");
        int orgId = workspaceMapper.getOrgId(workspace.getId()); User target = newUser(); User actor = currentUser;
        if (organization) { orgMembers.addMember(orgId, currentUser.getId(), "owner"); }
        long offerId = 0;
        if (!create) {
            offerId = organization ? id(organizations.setMember(orgId, currentUser.getId(), target.getId(), "owner").offer()) : id(offer(target).offer());
            actor = newUser(); workspaceMapper.updateMemberRole(workspace.getId(), actor.getId(), "owner");
            if (organization) { orgMembers.addMember(orgId, actor.getId(), "owner"); }
            authenticateAs(actor, workspace.getId()); OwnershipOfferTestFixtures.stepUpCurrent(jdbc, actor);
        }
        int reserved = parts[2].equals("offeror") ? currentUser.getId() : parts[2].equals("offeree") ? target.getId() : actor.getId();
        jdbc.update("UPDATE app_user SET account_deletion_reserved_at = UTC_TIMESTAMP(6), account_deletion_reservation_owner = 'ownership-reference-test', account_deletion_reservation_until = DATE_ADD(UTC_TIMESTAMP(6), INTERVAL 1 HOUR) WHERE id = ?", reserved);
        if (create) {
            if (!organization && parts[2].equals("offeror")) {
                doReturn(false).when(reservationUsers).isAccountDeletionReserved(currentUser.getId());
            }
            assertThrows(ResourceNotFoundException.class, () -> { if (organization) { organizations.setMember(orgId, currentUser.getId(), target.getId(), "owner"); } else { offer(target); } });
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM ownership_offer WHERE offeree_user_id = ?", Integer.class, target.getId()));
        } else {
            long selected = offerId; int actorId = actor.getId();
            assertThrows(ResourceNotFoundException.class, () -> offers.revoke(parts[0], organization ? orgId : workspace.getId(), selected, actorId));
            assertEquals("OPEN", offerMapper.findById(offerId).getState());
        }
    }

    @Test void acceptanceRequiresAnOwnedFreshSignerAndCurrentEpoch() {
        User target = newUser(); var offered = offer(target); authenticateAs(target, workspace.getId());
        assertThrows(RecentAuthenticationRequiredException.class, () -> offers.accept(id(offered.offer()), target.getId(), false));
        asOfferee(target);
        jdbc.update("UPDATE app_user SET session_epoch = session_epoch + 1 WHERE id = ?", target.getId());
        assertThrows(RecentAuthenticationRequiredException.class, () -> offers.accept(id(offered.offer()), target.getId(), false));
        assertEquals("OPEN", offerMapper.findById(id(offered.offer())).getState());
    }

    @Test @Transactional(propagation = Propagation.NOT_SUPPORTED) void expirationRefusesAcceptanceImmediatelyAndAReplacementDoesNotReuseConsent() {
        User target = newUser(); var offered = offer(target);
        jdbc.update("UPDATE ownership_offer SET created_at = DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 8 DAY), expires_at = DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 1 DAY) WHERE id = ?", id(offered.offer()));
        assertTrue(offers.listForSelf(target.getId()).isEmpty()); asOfferee(target);
        assertThrows(ConflictException.class, () -> offers.accept(id(offered.offer()), target.getId(), false));
        authenticateAs(currentUser, workspace.getId()); OwnershipOfferTestFixtures.stepUpCurrent(jdbc, currentUser);
        var replacement = offer(target);
        assertFalse(offered.offer().id().equals(replacement.offer().id()));
        assertEquals("EXPIRED", offerMapper.findById(id(offered.offer())).getState());
    }

    @Test @Transactional(propagation = Propagation.NOT_SUPPORTED) void confinementAcknowledgementBelongsToTheOffereeAndRollsBackTheClaim() {
        jdbc.update("INSERT INTO privileged_credential_attestation (credential_row_id, org_id, source) VALUES (?, ?, 'FOUNDER')", ownerCredential, workspaceMapper.getOrgId(workspace.getId()));
        mfa.setAttestationEnforced("true");
        User target = newUser(); var offered = offer(target);
        assertEquals("member", workspaceMapper.getRole(workspace.getId(), target.getId()));
        asOfferee(target);
        assertThrows(PrivilegedMfaConfinementAcknowledgementRequiredException.class,
            () -> offers.accept(id(offered.offer()), target.getId(), false));
        assertEquals("OPEN", offerMapper.findById(id(offered.offer())).getState());
        assertEquals("member", workspaceMapper.getRole(workspace.getId(), target.getId()));
        var accepted = offers.accept(id(offered.offer()), target.getId(), true);
        assertEquals(PrivilegedMfaStanding.ATTESTATION_REQUIRED, accepted.standing());
        assertEquals(PrivilegedMfaStanding.ATTESTATION_REQUIRED, accepted.enforcedStanding());
    }

    @Test @Transactional(propagation = Propagation.NOT_SUPPORTED) void strictAcceptanceAuditFailureRollsBackRoleAndConsentTogether() {
        User target = newUser(); var offered = offer(target); asOfferee(target);
        doThrow(new IllegalStateException("audit unavailable")).when(audit).recordStrictScoped(
            eq("ownership.offer.accepted"), any(), anyInt(), any(), anyInt(), any(), any(), any());
        assertThrows(IllegalStateException.class, () -> offers.accept(id(offered.offer()), target.getId(), false));
        assertEquals("OPEN", offerMapper.findById(id(offered.offer())).getState());
        assertEquals("member", workspaceMapper.getRole(workspace.getId(), target.getId()));
    }

    @Test void absentOrganizationMembershipIsOfferedWithoutInterimAdminAndAcceptClearsFounder() {
        int orgId = workspaceMapper.getOrgId(workspace.getId());
        orgMembers.addMember(orgId, currentUser.getId(), "owner"); User target = newUser();
        var offered = organizations.setMember(orgId, currentUser.getId(), target.getId(), "owner");
        assertNotNull(offered.offer()); assertNull(offered.currentMember()); assertNull(orgMembers.getRole(orgId, target.getId()));
        asOfferee(target); offers.accept(id(offered.offer()), target.getId(), false);
        assertEquals("owner", orgMembers.getRole(orgId, target.getId()));
        assertFalse(jdbc.queryForObject("SELECT founder FROM org_member WHERE org_id = ? AND user_id = ?", Boolean.class, orgId, target.getId()));
    }

    @Test void foreignSigningCredentialIsRejectedEvenWithAFreshStampAndEnrollment() {
        User target = newUser(); var offered = offer(target); asOfferee(target);
        var request = ((ServletRequestAttributes) RequestContextHolder.currentRequestAttributes()).getRequest();
        OwnershipOfferTestFixtures.stepUp(jdbc, target, request.getSession(), ownerCredential);
        assertThrows(RecentAuthenticationRequiredException.class, () -> offers.accept(id(offered.offer()), target.getId(), false));
        assertEquals("OPEN", offerMapper.findById(id(offered.offer())).getState());
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void acceptanceRechecksAuthorityEvenWhenAnOpenRowBypassesInvalidationHooks(boolean organization) {
        User target = newUser(); int orgId = workspaceMapper.getOrgId(workspace.getId());
        if (organization) { orgMembers.addMember(orgId, currentUser.getId(), "owner"); }
        OwnershipOfferDto offered = organization
            ? organizations.setMember(orgId, currentUser.getId(), target.getId(), "owner").offer() : offer(target).offer();
        if (organization) { orgMembers.updateRole(orgId, currentUser.getId(), "admin"); }
        else { workspaceMapper.updateMemberRole(workspace.getId(), currentUser.getId(), "member"); }
        asOfferee(target);
        assertEquals("OPEN", offerMapper.findById(id(offered)).getState());
        assertThrows(ConflictException.class, () -> offers.accept(id(offered), target.getId(), false));
        assertEquals("member", workspaceMapper.getRole(workspace.getId(), target.getId()));
    }

    @Test void acceptanceRechecksAChangedGrantCeilingWhileConsentIsStillOpen() {
        User target = newUser(); var offered = offer(target);
        WorkspaceRole overlay = new WorkspaceRole(); overlay.setWorkspaceId(workspace.getId()); overlay.setName("Narrow acceptance " + unique());
        roles.insertRole(overlay); roles.insertPermissions(workspace.getId(), overlay.getId(), List.of("MEMBER_MANAGE"));
        workspaceMapper.setMemberCustomRole(workspace.getId(), currentUser.getId(), overlay.getId());
        asOfferee(target);
        assertThrows(ConflictException.class, () -> offers.accept(id(offered.offer()), target.getId(), false));
        assertEquals("OPEN", offerMapper.findById(id(offered.offer())).getState());
        assertEquals("member", workspaceMapper.getRole(workspace.getId(), target.getId()));
    }

    @Test @Transactional(propagation = Propagation.NOT_SUPPORTED) void strictCreateAuditFailureLeavesNoConsentOrPromotion() {
        User target = newUser();
        doThrow(new IllegalStateException("audit unavailable")).when(audit).recordStrictScoped(
            eq("ownership.offer.created"), any(), anyInt(), any(), anyInt(), any(), any(), any());
        assertThrows(IllegalStateException.class, () -> offer(target));
        assertTrue(offerMapper.listForOfferee(target.getId()).isEmpty());
        assertEquals("member", workspaceMapper.getRole(workspace.getId(), target.getId()));
    }

    @Test void aFoundingHelperCannotBeReusedForAnExistingScope() {
        assertThrows(ConflictException.class, () -> organizations.addFoundingOwner(workspaceMapper.getOrgId(workspace.getId()), currentUser.getId()));
    }
}
