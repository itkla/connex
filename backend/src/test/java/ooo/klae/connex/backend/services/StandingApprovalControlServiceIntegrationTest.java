package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import ooo.klae.connex.backend.ai.AiStandingApprovalControlOperations;
import ooo.klae.connex.backend.dto.AiStandingApprovalPolicyRequest;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.mappers.AiStandingApprovalPolicyMapper;
import ooo.klae.connex.backend.mappers.OrgMemberMapper;
import ooo.klae.connex.backend.tenant.Permission;

/** Exercises production writers and the assistant's existing locked authority snapshot. */
class StandingApprovalControlServiceIntegrationTest extends AbstractServiceTest {
    @Autowired WorkspaceService workspaces;
    @Autowired RoleService roles;
    @Autowired OrgMemberMapper orgMembers;
    @Autowired AiStandingApprovalPolicyMapper policies;
    @Autowired AiStandingApprovalControlOperations controls;
    @Autowired UserDeletionTransaction deletion;
    @Autowired JdbcTemplate jdbc;

    private WorkspaceService.AuthorityEpochs authority(int userId) {
        return workspaces.lockAndRequirePermissionsSnapshot(workspace.getId(),
                Map.of(userId, Set.of(Permission.AI_USE))).authorityFor(userId);
    }

    @Test
    void roleAssignmentDownUpCannotRestoreTheOriginalBinding() {
        var before = authority(currentUser.getId());
        workspaceMapper.updateMemberRole(workspace.getId(), currentUser.getId(), "member");
        workspaceMapper.updateMemberRole(workspace.getId(), currentUser.getId(), "owner");
        var restored = authority(currentUser.getId());
        assertEquals(before.membershipId(), restored.membershipId());
        assertEquals(before.authorizationEpoch() + 2, restored.authorizationEpoch());
        assertEquals(before.builtInPermissionPolicyVersion(), restored.builtInPermissionPolicyVersion());
        assertNotEquals(before, restored);
    }

    @Test
    void customPermissionDownUpAdvancesHistoryWhileCosmeticRenamePreservesIt() {
        var target = newUser();
        var role = roles.createRole(workspace.getId(), currentUser.getId(), "Standing approval role", List.of("AI_USE"));
        workspaceMapper.setMemberCustomRole(workspace.getId(), target.getId(), role.getId());
        var before = authority(target.getId());
        roles.updateRole(workspace.getId(), currentUser.getId(), role.getId(), "Renamed", List.of("AI_USE"));
        assertEquals(before, authority(target.getId()));
        roles.updateRole(workspace.getId(), currentUser.getId(), role.getId(), "Renamed", List.of());
        assertThrows(ForbiddenException.class, () -> authority(target.getId()));
        roles.updateRole(workspace.getId(), currentUser.getId(), role.getId(), "Renamed", List.of("AI_USE"));
        var restored = authority(target.getId());
        assertEquals(before.authorizationEpoch(), restored.authorizationEpoch());
        assertEquals(before.permissionEpoch() + 2, restored.permissionEpoch());
        assertNotEquals(before, restored);
    }

    @Test
    void departureAndRejoinUseANewMembershipGeneration() {
        var member = newUser();
        var before = authority(member.getId());
        workspaceMapper.removeMember(workspace.getId(), member.getId());
        workspaceMapper.addMember(workspace.getId(), member.getId(), "member");
        var rejoined = authority(member.getId());
        assertNotEquals(before.membershipId(), rejoined.membershipId());
    }

    @Test
    void failedAccountDeletionReleaseCannotReviveOriginalAuthority() {
        var member = newUser();
        var before = authority(member.getId());
        deletion.reserve(member.getId(), "standing-approval-reservation");
        deletion.release(member.getId(), "standing-approval-reservation");
        var restored = authority(member.getId());
        assertEquals(before.membershipId(), restored.membershipId());
        assertEquals(before.authorizationEpoch() + 1, restored.authorizationEpoch());
    }

    @Test
    void organizationPolicyDoesNotRequireCrmVisibilityAndSurvivesMemberDeparture() {
        int orgId = workspaces.getCurrentOrgId();
        orgMembers.addMember(orgId, currentUser.getId(), "admin");
        policies.provision(orgId);
        var narrow = roles.createRole(workspace.getId(), currentUser.getId(), "No CRM access", List.of());
        workspaceMapper.setMemberCustomRole(workspace.getId(), currentUser.getId(), narrow.getId());
        var before = controls.read(workspace.getId(), orgId, currentUser.getId());
        var after = controls.replace(workspace.getId(), orgId, currentUser.getId(),
                new AiStandingApprovalPolicyRequest(false, List.of("remove_tag"), 20, 2, before.stateVersion()));
        assertFalse(after.authoringEnabled());
        assertEquals(Long.parseLong(before.killEpoch()) + 1, Long.parseLong(after.killEpoch()));
        var member = newUser();
        assertThrows(ForbiddenException.class, () -> controls.read(workspace.getId(), orgId, member.getId()));
        workspaceMapper.removeMember(workspace.getId(), member.getId());
        assertNotNull(policies.get(orgId));
    }

    @Test
    void pendingActivationAdvancesEpochAndMissingMetadataRefusesStandingAuthority() {
        var member = newUser();
        jdbc.update("UPDATE workspace_member SET status='pending' WHERE workspace_id=? AND user_id=?",
                workspace.getId(), member.getId());
        long before = jdbc.queryForObject("SELECT authorization_epoch FROM workspace_member WHERE workspace_id=? AND user_id=?",
                Long.class, workspace.getId(), member.getId());
        assertEquals(1, workspaceMapper.activateMember(workspace.getId(), member.getId()));
        assertEquals(before + 1, authority(member.getId()).authorizationEpoch());
        jdbc.update("UPDATE workspace_member SET authorization_epoch=0 WHERE workspace_id=? AND user_id=?",
                workspace.getId(), member.getId());
        assertThrows(ForbiddenException.class, () -> authority(member.getId()));
    }
}
