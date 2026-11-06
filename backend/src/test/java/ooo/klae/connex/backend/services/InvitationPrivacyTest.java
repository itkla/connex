package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.dto.InviteDto;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import tools.jackson.databind.json.JsonMapper;

/** Exercises the invitation disclosure boundary with real persistence and authorization. */
@Transactional(isolation = Isolation.READ_COMMITTED)
class InvitationPrivacyTest extends AbstractServiceTest {
    @Autowired private InviteService inviteService;
    @Autowired private WorkspaceService workspaceService;
    @Autowired private RoleService roleService;

    @BeforeEach
    void isolateCommittedInvitationFixtures() {
        tenantContext.clear();
        var isolated = workspaceService.createWorkspace("Invitation privacy fixtures", currentUser.getId());
        authenticateAs(currentUser, isolated.getId());
        approveForStepUp(currentUser);
    }

    @Test
    void accountCreationAndRepeatedPendingInvitesDoNotChangeTheDisclosure() {
        var target = workspaceService.createWorkspace("Private invitation", currentUser.getId());
        assertNotEquals(workspace.getOrgId(), target.getOrgId());
        String email = "invite-privacy-" + unique() + "@example.com";
        var absent = afterFixtureCommit(() -> inviteService.createInvite(target.getId(), currentUser, email, "member"));
        User recipient = newUser();
        userMapper.updateEmail(recipient.getId(), email);
        recipient.setEmail(email);
        var registered = afterFixtureCommit(() -> inviteService.createInvite(target.getId(), currentUser, email, "member"));
        var repeated = afterFixtureCommit(() -> inviteService.addExistingMember(target.getId(), currentUser.getId(), email, "member"));

        JsonMapper mapper = JsonMapper.builder().build();
        for (var result : List.of(absent, registered, repeated)) {
            var json = mapper.readTree(mapper.writeValueAsString(result));
            assertEquals(Set.of("invite", "emailDelivery"), Set.copyOf(json.propertyNames()));
            assertEquals(absent.getEmailDelivery().name(), json.path("emailDelivery").asString());
            assertEquals(Set.of("id", "email", "role", "status", "token", "invitedByLabel", "expiresAt", "createdAt"),
                Set.copyOf(json.path("invite").propertyNames()));
            assertEquals(email, json.path("invite").path("email").asString());
            assertEquals("pending", json.path("invite").path("status").asString());
            assertFalse(json.toString().contains(recipient.getUsername()));
            assertFalse(json.toString().contains(recipient.getDisplayName()));
            assertFalse(json.toString().contains("username"));
            assertFalse(json.toString().contains("profilePictureUrl"));
            assertTrue(result.getInvite().getId().matches("[a-f0-9]{64}"));
            assertNotNull(result.getInvite().getExpiresAt());
        }
        assertEquals(1, inviteService.listInvites(target.getId(), currentUser.getId()).size());
        assertFalse(workspaceMapper.isMemberIncludingPending(target.getId(), recipient.getId()));
        assertEquals(List.of(currentUser.getId()), workspaceService.getMembersWithRoles(
            target.getId(), currentUser.getId()).stream().map(member -> member.getId()).toList());

        String exchanged = inviteService.exchangeToken(repeated.getInvite().getToken(), "privacy-exchange-owner");
        assertTrue(inviteService.previewInviteByHash(exchanged).isValid());
        assertFalse(workspaceMapper.isMemberIncludingPending(target.getId(), recipient.getId()));
        assertEquals(1, workspaceService.getMembersWithRoles(target.getId(), currentUser.getId()).size());
        InviteDto awaitingAcceptance = inviteService.listInvites(target.getId(), currentUser.getId()).getFirst();
        assertEquals(email, awaitingAcceptance.getEmail());
        assertEquals("pending", awaitingAcceptance.getStatus());
        assertNull(awaitingAcceptance.getToken());
        inviteService.acceptInviteByHash(exchanged, recipient, () -> { });

        var accepted = workspaceService.getMembersWithRoles(target.getId(), currentUser.getId()).stream()
            .filter(member -> member.getId() == recipient.getId()).findFirst().orElseThrow();
        assertEquals(recipient.getUsername(), accepted.getUsername());
        assertEquals(recipient.getDisplayName(), accepted.getDisplayName());
        assertEquals(email, accepted.getEmail());
        assertTrue(inviteService.listInvites(target.getId(), currentUser.getId()).isEmpty());
    }

    @Test
    void historicalPendingProfilesStayOutOfRosterSearchAndRoleResponses() {
        var target = workspaceService.createWorkspace("Pending privacy", currentUser.getId());
        User recipient = newUser();
        workspaceService.addPendingMember(target.getId(), currentUser, recipient, "member");

        assertEquals(1, workspaceService.getMembersWithRoles(target.getId(), currentUser.getId()).size());
        assertEquals(List.of(currentUser.getId()), workspaceMapper.findLayoutMemberships(
            target.getOrgId(), currentUser.getId(), List.of(target.getId()), 100).stream()
            .map(member -> member.getUserId()).toList());
        assertTrue(userMapper.findMatchingWorkspaceMemberIds(target.getId(), recipient.getUsername()).isEmpty());
        assertTrue(workspaceMapper.getActiveMemberIdsIn(target.getId(), List.of(recipient.getId())).isEmpty());
        assertThrows(ResourceNotFoundException.class, () -> workspaceService.changeMemberRole(
            target.getId(), currentUser.getId(), recipient.getId(), "member"));
        var custom = roleService.createRole(target.getId(), currentUser.getId(), "Privacy role", List.of());
        assertThrows(ResourceNotFoundException.class, () -> workspaceService.assignCustomRole(
            target.getId(), currentUser.getId(), recipient.getId(), custom.getId()));

        InviteDto pending = inviteService.listInvites(target.getId(), currentUser.getId()).getFirst();
        assertEquals(recipient.getEmail(), pending.getEmail());
        assertEquals("pending", pending.getStatus());
        assertTrue(pending.getId().matches("[a-f0-9]{64}"));
        assertNull(pending.getExpiresAt());
        assertNull(pending.getToken());
        assertNull(pending.getInvitedByLabel());
        workspaceService.approveMembership(target.getId(), recipient.getId());
        assertEquals(2, workspaceService.getMembersWithRoles(target.getId(), currentUser.getId()).size());
    }

    @Test
    void historicalInvitationRevocationIsWorkspaceBoundAndCannotRemoveAcceptedMembership() {
        var first = workspaceService.createWorkspace("First privacy", currentUser.getId());
        authenticateAs(currentUser, first.getId());
        approveForStepUp(currentUser);
        var second = workspaceService.createWorkspace("Second privacy", currentUser.getId());
        assertEquals(first.getOrgId(), second.getOrgId());
        User recipient = newUser();
        workspaceService.addPendingMember(first.getId(), currentUser, recipient, "member");
        String reference = inviteService.listInvites(first.getId(), currentUser.getId()).getFirst().getId();

        assertThrows(ResourceNotFoundException.class,
            () -> inviteService.revokeInvite(second.getId(), reference, currentUser.getId()));
        assertNotNull(workspaceMapper.getMember(first.getId(), recipient.getId()));
        workspaceService.approveMembership(first.getId(), recipient.getId());
        assertThrows(ResourceNotFoundException.class,
            () -> inviteService.revokeInvite(first.getId(), reference, currentUser.getId()));
        assertTrue(workspaceMapper.isMember(first.getId(), recipient.getId()));
    }

    @Test
    void historicalRevocationCannotAddressAReplacementGeneration() {
        var target = workspaceService.createWorkspace("Replaced privacy", currentUser.getId());
        User recipient = newUser();
        workspaceService.addPendingMember(target.getId(), currentUser, recipient, "member");
        String oldReference = inviteService.listInvites(target.getId(), currentUser.getId()).getFirst().getId();
        inviteService.revokeInvite(target.getId(), oldReference, currentUser.getId());
        assertNull(workspaceMapper.getMember(target.getId(), recipient.getId()));
        workspaceService.addPendingMember(target.getId(), currentUser, recipient, "member");

        assertThrows(ResourceNotFoundException.class,
            () -> inviteService.revokeInvite(target.getId(), oldReference, currentUser.getId()));
        assertNotNull(workspaceMapper.getMember(target.getId(), recipient.getId()));
        String replacement = inviteService.listInvites(target.getId(), currentUser.getId()).getFirst().getId();
        inviteService.revokeInvite(target.getId(), replacement, currentUser.getId());
        assertNull(workspaceMapper.getMember(target.getId(), recipient.getId()));
    }

    @Test
    void resendingHistoricalInvitationSupersedesTheOldGrantAndRevokesCompletely() {
        var target = workspaceService.createWorkspace("Superseded privacy", currentUser.getId());
        User recipient = newUser();
        workspaceService.addPendingMember(target.getId(), currentUser, recipient, "member");

        var replacement = afterFixtureCommit(() -> inviteService.createInvite(target.getId(), currentUser, recipient.getEmail(), "member"));

        assertNull(workspaceMapper.getMember(target.getId(), recipient.getId()));
        assertEquals(1, inviteService.listInvites(target.getId(), currentUser.getId()).size());
        inviteService.revokeInvite(target.getId(), replacement.getInvite().getId(), currentUser.getId());
        assertTrue(inviteService.listInvites(target.getId(), currentUser.getId()).isEmpty());
        assertThrows(ResourceNotFoundException.class,
            () -> workspaceService.approveMembership(target.getId(), recipient.getId()));
    }

    @Test
    void historicalOwnerInvitationRetainsOwnerOnlyRevocation() {
        var target = workspaceService.createWorkspace("Owner invitation privacy", currentUser.getId());
        User recipient = newUser();
        workspaceMapper.addPendingMember(target.getId(), recipient.getId(), "member");
        workspaceMapper.updateMemberRole(target.getId(), recipient.getId(), "owner");
        User admin = newUser();
        workspaceMapper.addMember(target.getId(), admin.getId(), "admin");
        authenticateAs(admin, target.getId());
        approveForStepUp(admin);
        String reference = inviteService.listInvites(target.getId(), admin.getId()).getFirst().getId();

        ForbiddenException refusal = assertThrows(ForbiddenException.class,
            () -> inviteService.revokeInvite(target.getId(), reference, admin.getId()));
        assertEquals("Requires OWNER role in this workspace", refusal.getMessage());
        assertEquals("pending", workspaceMapper.getMember(target.getId(), recipient.getId()).getStatus());
    }

    @Test
    void unrelatedTenantCannotCreateListOrRevokeInvitationsOrReadRoster() {
        var target = workspaceService.createWorkspace("Tenant privacy", currentUser.getId());
        User outsider = newUser();
        var invitation = afterFixtureCommit(() -> inviteService.createInvite(target.getId(), currentUser, outsider.getEmail(), "member"));

        assertThrows(ForbiddenException.class,
            () -> afterFixtureCommit(() -> inviteService.createInvite(target.getId(), outsider, currentUser.getEmail(), "member")));
        assertThrows(ForbiddenException.class,
            () -> inviteService.listInvites(target.getId(), outsider.getId()));
        assertThrows(ForbiddenException.class,
            () -> inviteService.revokeInvite(target.getId(), invitation.getInvite().getId(), outsider.getId()));
        assertThrows(ForbiddenException.class,
            () -> workspaceService.getMembersWithRoles(target.getId(), outsider.getId()));
    }
}
