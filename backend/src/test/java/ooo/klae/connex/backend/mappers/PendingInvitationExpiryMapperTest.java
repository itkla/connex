package ooo.klae.connex.backend.mappers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.Workspace;

/** Exercises legacy invitation deadlines at the workspace-scoped persistence boundary. */
class PendingInvitationExpiryMapperTest extends AbstractMapperTest {
    @Autowired private JdbcTemplate jdbc;
    @Autowired private InviteMapper inviteMapper;

    @Test
    void newPendingGrantsHaveFourteenDayDeadlines() {
        String beforeCreation = jdbc.queryForObject("SELECT UTC_TIMESTAMP()", String.class);
        assertNotNull(beforeCreation);
        User recipient = pendingRecipient();
        Boolean withinWindow = jdbc.queryForObject("""
            SELECT expires_at >= CAST(? AS DATETIME) + INTERVAL 14 DAY
                AND expires_at <= UTC_TIMESTAMP() + INTERVAL 14 DAY
            FROM workspace_member WHERE workspace_id = ? AND user_id = ?
            """, Boolean.class, beforeCreation, workspace.getId(), recipient.getId());

        assertEquals(Boolean.TRUE, withinWindow);
        assertTrue(workspaceMapper.getPendingMemberships(recipient.getId()).stream()
            .anyMatch(item -> item.getId() == workspace.getId()));
        assertEquals(1, workspaceMapper.activateMember(workspace.getId(), recipient.getId()));
        assertEquals(0, workspaceMapper.activateMember(workspace.getId(), recipient.getId()));
    }

    @Test
    void expiredPendingGrantCannotBeListedToRecipientOrActivated() {
        User recipient = pendingRecipient();
        jdbc.update("""
            UPDATE workspace_member SET expires_at = UTC_TIMESTAMP() - INTERVAL 1 SECOND
            WHERE workspace_id = ? AND user_id = ?
            """, workspace.getId(), recipient.getId());

        assertTrue(workspaceMapper.getPendingMemberships(recipient.getId()).isEmpty());
        assertEquals(0, workspaceMapper.activateMember(workspace.getId(), recipient.getId()));
        assertFalse(workspaceMapper.isMember(workspace.getId(), recipient.getId()));
        var invitation = inviteMapper.findPendingByWorkspace(workspace.getId()).stream()
            .filter(item -> item.getEmail().equals(recipient.getEmail())).findFirst().orElseThrow();
        assertNotNull(invitation.getExpiresAt());
        assertNull(invitation.getToken());
        assertNull(invitation.getInvitedByLabel());
        assertEquals(invitation.getId(), workspaceMapper.findPendingInvitationReferenceByEmail(
            workspace.getId(), recipient.getEmail()));
        assertEquals(recipient.getId(), workspaceMapper.findPendingInvitationUserId(
            workspace.getId(), invitation.getId()));
    }

    @Test
    void missingDeadlineUsesFixedCutoverUntilBackfilled() {
        User recipient = pendingRecipient();
        jdbc.update("UPDATE workspace_member SET expires_at = NULL WHERE workspace_id = ? AND user_id = ?",
            workspace.getId(), recipient.getId());

        String cutoff = jdbc.queryForObject("""
            SELECT CAST(installed_on AT TIME ZONE '+00:00' AS DATETIME) + INTERVAL 30 DAY
            FROM flyway_schema_history WHERE version = '247' AND success = 1
            """, String.class);
        assertNotNull(cutoff);
        assertEquals(cutoff, inviteMapper.findPendingByWorkspace(workspace.getId()).stream()
            .filter(item -> item.getEmail().equals(recipient.getEmail())).findFirst().orElseThrow().getExpiresAt());
        assertEquals(java.util.List.of(workspace.getId()), workspaceMapper.getPendingMemberships(recipient.getId())
            .stream().map(item -> item.getId()).toList());
        assertEquals(1, workspaceMapper.activateMember(workspace.getId(), recipient.getId()));
    }

    @Test
    void unfinishedBackfillStillRejectsLegacyGrantAfterCutoverDeadline() {
        User recipient = pendingRecipient();
        jdbc.update("UPDATE workspace_member SET expires_at = NULL WHERE workspace_id = ? AND user_id = ?",
            workspace.getId(), recipient.getId());
        jdbc.update("UPDATE flyway_schema_history SET installed_on = CURRENT_TIMESTAMP() - INTERVAL 31 DAY WHERE version = '247'");

        assertTrue(workspaceMapper.getPendingMemberships(recipient.getId()).isEmpty());
        assertEquals(0, workspaceMapper.activateMember(workspace.getId(), recipient.getId()));
        assertNotNull(inviteMapper.findPendingByWorkspace(workspace.getId()).stream()
            .filter(item -> item.getEmail().equals(recipient.getEmail())).findFirst().orElseThrow().getExpiresAt());
    }

    @Test
    void missingSuccessfulCutoverHistoryFailsClosed() {
        User recipient = pendingRecipient();
        jdbc.update("UPDATE workspace_member SET expires_at = NULL WHERE workspace_id = ? AND user_id = ?",
            workspace.getId(), recipient.getId());
        jdbc.update("UPDATE flyway_schema_history SET success = 0 WHERE version = '247'");

        assertTrue(workspaceMapper.getPendingMemberships(recipient.getId()).isEmpty());
        assertEquals(0, workspaceMapper.activateMember(workspace.getId(), recipient.getId()));
    }

    @Test
    void expiryAndManagementQueriesStayBoundToTheirWorkspaceAndRecipient() {
        User recipient = pendingRecipient();
        User stranger = newUser();
        Workspace other = new Workspace();
        other.setName("Other invitation workspace");
        other.setSlug("invitation-expiry-" + unique());
        workspaceMapper.insert(other);
        workspaceMapper.addPendingMember(other.getId(), recipient.getId(), "member");
        jdbc.update("""
            UPDATE workspace_member SET expires_at = UTC_TIMESTAMP() - INTERVAL 1 SECOND
            WHERE workspace_id = ? AND user_id = ?
            """, workspace.getId(), recipient.getId());
        var invitation = inviteMapper.findPendingByWorkspace(workspace.getId()).stream()
            .filter(item -> item.getEmail().equals(recipient.getEmail())).findFirst().orElseThrow();

        assertNull(workspaceMapper.findPendingInvitationUserId(other.getId(), invitation.getId()));
        assertEquals(0, inviteMapper.markRevokedByReference(invitation.getId(), other.getId()));
        assertTrue(inviteMapper.findPendingByWorkspace(other.getId()).stream()
            .noneMatch(item -> item.getId().equals(invitation.getId())));
        assertTrue(workspaceMapper.getPendingMemberships(stranger.getId()).isEmpty());
        assertEquals(java.util.List.of(other.getId()), workspaceMapper.getPendingMemberships(recipient.getId())
            .stream().map(item -> item.getId()).toList());
        assertEquals(0, workspaceMapper.activateMember(workspace.getId(), recipient.getId()));
        assertEquals(1, workspaceMapper.activateMember(other.getId(), recipient.getId()));
    }

    private User pendingRecipient() {
        User recipient = newUser();
        workspaceMapper.removeMember(workspace.getId(), recipient.getId());
        workspaceMapper.addPendingMember(workspace.getId(), recipient.getId(), "member");
        return recipient;
    }
}
