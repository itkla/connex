package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import ooo.klae.connex.backend.beans.Note;
import ooo.klae.connex.backend.beans.Organization;
import ooo.klae.connex.backend.beans.Task;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.ConflictException;
import ooo.klae.connex.backend.mappers.AuditLogMapper;
import ooo.klae.connex.backend.mappers.OrgMemberMapper;
import ooo.klae.connex.backend.mappers.OrganizationMapper;

/**
 * Deleting an account must not orphan a workspace or organization: because both membership tables
 * are {@code ON DELETE CASCADE}, a self-delete would rip out the owner row and bypass the last-owner
 * guards that live on the member operations. The account path re-applies those guards (#316).
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AccountDeletionGuardTest extends AbstractServiceTest {

    @Autowired private UserService userService;
    @Autowired private WorkspaceService workspaceService;
    @Autowired private OrgMemberService orgMemberService;
    @Autowired private OrgMemberMapper orgMemberMapper;
    @Autowired private AuditLogMapper auditLogMapper;
    @Autowired private OrganizationMapper organizationMapper;
    @Autowired private JdbcTemplate jdbcTemplate;
    private final List<Integer> createdUserIds = new ArrayList<>();
    private final List<Integer> createdWorkspaceIds = new ArrayList<>();
    private final List<Integer> createdOrganizationIds = new ArrayList<>();
    private final List<Integer> createdNoteIds = new ArrayList<>();
    private final List<Integer> createdTaskIds = new ArrayList<>();

    @AfterEach
    void cleanUpCommittedFixtures() {
        createdNoteIds.reversed().forEach(
            id -> jdbcTemplate.update("DELETE FROM note WHERE id = ?", id));
        createdTaskIds.reversed().forEach(
            id -> jdbcTemplate.update("DELETE FROM task WHERE id = ?", id));
        createdWorkspaceIds.reversed().forEach(
            id -> jdbcTemplate.update(
                "DELETE FROM workspace_member WHERE workspace_id = ?", id));
        createdWorkspaceIds.reversed().forEach(
            id -> jdbcTemplate.update("DELETE FROM workspace WHERE id = ?", id));
        createdOrganizationIds.reversed().forEach(
            id -> jdbcTemplate.update("DELETE FROM org_member WHERE org_id = ?", id));
        createdOrganizationIds.reversed().forEach(
            id -> jdbcTemplate.update("DELETE FROM organization WHERE id = ?", id));
        createdUserIds.reversed().forEach(
            id -> jdbcTemplate.update(
                "DELETE FROM workspace_member WHERE user_id = ?", id));
        createdUserIds.reversed().forEach(
            id -> jdbcTemplate.update("DELETE FROM org_member WHERE user_id = ?", id));
        createdUserIds.reversed().forEach(
            id -> jdbcTemplate.update("DELETE FROM app_user WHERE id = ?", id));
    }

    @Override
    protected User newUser() {
        User user = super.newUser();
        createdUserIds.add(user.getId());
        return user;
    }

    private int newOrgOwnedBy(int userId) {
        Organization org = new Organization();
        org.setName("Org " + unique());
        org.setSlug("org-" + unique());
        organizationMapper.insert(org);
        orgMemberMapper.addMember(org.getId(), userId, "owner");
        createdOrganizationIds.add(org.getId());
        return org.getId();
    }

    @Test
    void deletingSoleWorkspaceOwner_isRefused() {
        newSoleOwnedWorkspace();
        assertThrows(BadRequestException.class, () -> userService.delete(currentUser.getId()),
            "the sole owner of a workspace must transfer ownership before deleting their account");
    }

    @Test
    void workspaceGuard_firesOnlyForTheSoleOwner() {
        newSoleOwnedWorkspace();
        assertThrows(BadRequestException.class,
            () -> workspaceService.assertNotSoleOwnerOfAnyWorkspace(currentUser.getId()));
        User plainMember = newUser();
        workspaceService.assertNotSoleOwnerOfAnyWorkspace(plainMember.getId());
    }

    @Test
    void orgGuard_firesForSoleOwner_clearsWhenAnotherOwnerExists() {
        User orgOwner = newUser();
        int orgId = newOrgOwnedBy(orgOwner.getId());

        assertThrows(BadRequestException.class,
            () -> orgMemberService.assertNotSoleOwnerOfAnyOrg(orgOwner.getId()));

        orgMemberMapper.addMember(orgId, newUser().getId(), "owner");
        orgMemberService.assertNotSoleOwnerOfAnyOrg(orgOwner.getId());
    }

    @Test
    void authoredContentRefusesAccountDeletion() {
        User target = newUser();
        Note note = newNote(target, null, null);
        createdNoteIds.add(note.getId());
        authenticateAs(target, workspace.getId());

        assertThrows(ConflictException.class, () -> userService.delete(target.getId()),
            "authored content must be reassigned or removed before the account can go (#440 increment 3)");
    }

    @Test
    void cleanMemberAccount_deletesAndDetachesOrgDataReferences() {
        User target = newUser();
        Task task = newTask(target, null, null);
        createdTaskIds.add(task.getId());
        authenticateAs(target, workspace.getId());

        userService.delete(target.getId());

        assertNull(userMapper.getUserById(target.getId()));
        assertNull(taskMapper.getTaskById(workspace.getId(), task.getId()).getAssignedTo());
        assertTrue(auditLogMapper.findRecent(workspace.getId(), 50, 0).stream()
            .anyMatch(entry -> "user.delete".equals(entry.getAction())),
            "account erasure must be audited; recording after the row delete was silently swallowed");
    }

    private void newSoleOwnedWorkspace() {
        Workspace soleOwned = new Workspace();
        soleOwned.setOrgId(workspaceMapper.getOrgId(workspace.getId()));
        soleOwned.setName("Sole owner " + unique());
        soleOwned.setSlug("sole-owner-" + unique());
        workspaceMapper.insert(soleOwned);
        workspaceMapper.addMember(soleOwned.getId(), currentUser.getId(), "owner");
        createdWorkspaceIds.add(soleOwned.getId());
    }
}
