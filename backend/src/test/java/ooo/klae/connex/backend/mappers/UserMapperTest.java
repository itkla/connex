package ooo.klae.connex.backend.mappers;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import ooo.klae.connex.backend.beans.UnenrolledPrivilegedAccount;
import ooo.klae.connex.backend.beans.UnenrolledPrivilegedAccountCounts;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.beans.WorkspaceRole;
import ooo.klae.connex.backend.dto.UserReferenceDto;

class UserMapperTest extends AbstractMapperTest {
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private RoleMapper roleMapper;
    @Autowired private OrgMemberMapper orgMemberMapper;
    @Autowired private SqlSessionTemplate sqlSession;

    /**
     * Inserts a new user and checks if the generated ID is not zero.
     */
    @Test
    void insert_assignsGeneratedId() {
        User user = newUser();
        assertNotEquals(0, user.getId());
    }

    /**
     * Gets a user by ID and checks if the returned user is not null.
     */
    @Test
    void getUserById_returnsInsertedRow() {
        User user = newUser();

        User found = userMapper.getUserById(user.getId());

        assertNotNull(found);
        assertEquals(user.getUsername(), found.getUsername());
        assertEquals(user.getDisplayName(), found.getDisplayName());
        assertEquals(user.getEmail(), found.getEmail());
        assertEquals(user.getPasswordHash(), found.getPassword());
        assertEquals("UTC", found.getTimezone());
        assertEquals("en", found.getLocale());
    }

    /**
     * Gets a user by username and checks if the returned user is not null.
     */
    @Test
    void getUserByUsername_returnsRow() {
        User user = newUser();

        User found = userMapper.getUserByUsername(user.getUsername());

        assertNotNull(found);
        assertEquals(user.getId(), found.getId());
    }

    /**
     * Gets a user by ID and checks if the returned user is null when the ID is negative.
     */
    @Test
    void getUserById_returnsNullWhenMissing() {
        assertNull(userMapper.getUserById(-1));
    }

    /**
     * Gets all users and checks if the returned list includes the inserted user.
     */
    @Test
    void getAllUsers_includesInsertedRow() {
        User user = newUser();

        List<User> all = userMapper.getAllUsers();

        assertTrue(all.stream().anyMatch(x -> x.getId() == user.getId()));
    }

    @Test
    void deletionReservationForShareReadsCurrentState() {
        User user = newUser();

        assertFalse(userMapper.isAccountDeletionReservedForShare(user.getId()));
        assertEquals(1, userMapper.reserveAccountDeletion(user.getId(), "mapper-lock-proof"));
        assertTrue(userMapper.isAccountDeletionReservedForShare(user.getId()));
    }

    @Test
    void getActiveWorkspaceMemberReferencesByIdsReturnsOnlyActiveMembersOfRequestedWorkspace() {
        User active = newUser();
        String profilePictureUrl = "/api/users/" + active.getId() + "/profile-picture";
        userMapper.updateProfilePictureUrlIfCurrent(active.getId(), null, profilePictureUrl);
        User pending = newUnassignedUser();
        User inactive = newUser();
        User foreign = newUnassignedUser();
        workspaceMapper.addPendingMember(workspace.getId(), pending.getId(), "member");
        jdbcTemplate.update(
            "UPDATE workspace_member SET status = 'inactive' WHERE workspace_id = ? AND user_id = ?",
            workspace.getId(), inactive.getId());
        Workspace other = new Workspace();
        other.setName("WS " + unique());
        other.setSlug("ws_" + unique());
        workspaceMapper.insert(other);
        workspaceMapper.addMember(other.getId(), foreign.getId(), "member");

        List<UserReferenceDto> references = userMapper
            .getActiveWorkspaceMemberReferencesByIds(
                workspace.getId(),
                List.of(
                    active.getId(),
                    pending.getId(),
                    inactive.getId(),
                    foreign.getId(),
                    Integer.MAX_VALUE));

        assertEquals(
            List.of(new UserReferenceDto(active.getId(), active.getDisplayName(), profilePictureUrl)),
            references);
    }

    /**
     * Persists editable profile fields without replacing the stored email or password.
     */
    @Test
    void update_persistsProfileWithoutChangingEmail() {
        User user = newUser();
        String originalEmail = user.getEmail();
        String originalPasswordHash = user.getPasswordHash();
        user.setUsername("renamed_" + unique());
        user.setDisplayName("Mr. New Name");
        user.setTimezone("Asia/Tokyo");
        user.setEmail("renamed_" + unique() + "@example.com");
        user.setPasswordHash("replacement_" + unique());

        userMapper.update(user);

        User found = userMapper.getUserById(user.getId());
        assertNotNull(found);
        assertEquals(user.getUsername(), found.getUsername());
        assertEquals("Mr. New Name", found.getDisplayName());
        assertEquals("Asia/Tokyo", found.getTimezone());
        assertEquals(originalEmail, found.getEmail());
        assertEquals(originalPasswordHash, found.getPasswordHash());
    }

    @Test
    void genericUpdateCannotReplaceManagedImageAndCasRejectsStaleReplacement() {
        User user = newUser();
        String first = "/api/users/" + user.getId()
            + "/profile-picture/550e8400-e29b-41d4-a716-446655440000.png";
        String second = "/api/users/" + user.getId()
            + "/profile-picture/550e8400-e29b-41d4-a716-446655440001.png";
        assertEquals(1, userMapper.updateProfilePictureUrlIfCurrent(user.getId(), null, first));

        user.setProfilePictureUrl("https://attacker.example/image.png");
        userMapper.update(user);

        assertEquals(first, userMapper.getUserById(user.getId()).getProfilePictureUrl());
        assertEquals(0, userMapper.updateProfilePictureUrlIfCurrent(user.getId(), null, second));
        assertEquals(1, userMapper.updateProfilePictureUrlIfCurrent(user.getId(), first, second));
    }

    /**
     * Deletes a user and checks if the user is removed.
     */
    @Test
    void delete_removesRow() {
        User user = newUser();

        userMapper.delete(user.getId());

        assertNull(userMapper.getUserById(user.getId()));
    }

    @Test
    void updateTimezone_persistsValue() {
        User user = newUser();

        userMapper.updateTimezone(user.getId(), "Asia/Tokyo");

        assertEquals("Asia/Tokyo", userMapper.getUserById(user.getId()).getTimezone());
    }

    @Test
    void updateLocale_persistsValue() {
        User user = newUser();

        userMapper.updateLocale(user.getId(), "ja");

        assertEquals("ja", userMapper.getUserById(user.getId()).getLocale());
    }

    @Test
    void localeConstraintRejectsValuesOutsideExactAllowlist() {
        User user = newUser();

        for (String locale : List.of("fr", "EN", "en-US", "en ", "ja ", "../../ja")) {
            assertThrows(DataAccessException.class,
                    () -> jdbcTemplate.update("UPDATE app_user SET locale = ? WHERE id = ?", locale, user.getId()));
        }
        assertThrows(DataAccessException.class,
                () -> jdbcTemplate.update("UPDATE app_user SET locale = NULL WHERE id = ?", user.getId()));
        assertEquals("en", userMapper.getUserById(user.getId()).getLocale());
    }

    /**
     * Search only returns members of the searching workspace, never a user who
     * belongs solely to another workspace.
     */
    @Test
    void search_isScopedToWorkspaceMembers() {
        User mine = newUser(); // member of the default workspace

        Workspace other = new Workspace();
        other.setName("WS " + unique());
        other.setSlug("ws_" + unique());
        workspaceMapper.insert(other);

        String s = unique();
        User foreign = new User();
        foreign.setUsername("zz_" + s);
        foreign.setDisplayName("Zz " + s);
        foreign.setEmail(s + "@foreign.example.com");
        foreign.setPasswordHash("hash_" + s);
        foreign.setTimezone("UTC");
        userMapper.insert(foreign);
        workspaceMapper.addMember(other.getId(), foreign.getId(), "member");

        assertTrue(userMapper.search(workspace.getId(), "%" + foreign.getUsername() + "%")
                .stream().noneMatch(u -> u.getId() == foreign.getId()));
        assertTrue(userMapper.search(workspace.getId(), "%" + mine.getUsername() + "%")
                .stream().anyMatch(u -> u.getId() == mine.getId()));
    }

    @Test
    void findMatchingWorkspaceMemberIdsInRestrictsMatchesToRequestedMembers() {
        String marker = "bounded_" + unique();
        User first = newUser();
        User second = newUser();
        User third = newUser();
        first.setDisplayName(marker + "_1");
        second.setDisplayName(marker + "_2");
        third.setDisplayName(marker + "_3");
        userMapper.update(first);
        userMapper.update(second);
        userMapper.update(third);

        List<Integer> matching = userMapper.findMatchingWorkspaceMemberIdsIn(
            workspace.getId(), "%" + marker + "%", List.of(first.getId(), third.getId()));

        assertEquals(List.of(first.getId(), third.getId()), matching);
    }

    /**
     * countUsers excludes the reserved {@code __connex_system__} actor, so a fresh instance (only the
     * seeded system actor) reads zero real users and the bootstrap runner can fire (#81 Phase 2).
     */
    @Test
    void countUsers_excludesSystemActor() {
        newUser();

        long nonSystem = userMapper.getAllUsers().stream()
            .filter(u -> !"__connex_system__".equals(u.getUsername())).count();
        assertEquals(nonSystem, userMapper.countUsers(),
            "countUsers must equal the number of non-system accounts");

        assertNotNull(userMapper.getUserByUsername("__connex_system__"));
        assertTrue(userMapper.getAllUsers().size() > userMapper.countUsers(),
            "the seeded system actor is present but excluded from the count");
    }

    @Test
    void privilegedAccountQueryTracksCurrentRoleAcrossControlPlaneMemberships() {
        User user = newUser();
        assertFalse(userMapper.isPrivilegedAccount(user.getId()));

        workspaceMapper.updateMemberRole(workspace.getId(), user.getId(), "admin");
        assertTrue(userMapper.isPrivilegedAccount(user.getId()));

        workspaceMapper.updateMemberRole(workspace.getId(), user.getId(), "member");
        assertFalse(userMapper.isPrivilegedAccount(user.getId()));

        WorkspaceRole role = new WorkspaceRole();
        role.setWorkspaceId(workspace.getId());
        role.setName("security-admin-" + unique());
        roleMapper.insertRole(role);
        workspaceMapper.setMemberCustomRole(workspace.getId(), user.getId(), role.getId());
        roleMapper.insertPermissions(workspace.getId(), role.getId(), List.of("MEMBER_MANAGE"));
        assertTrue(userMapper.isPrivilegedAccount(user.getId()));

        roleMapper.clearPermissions(workspace.getId(), role.getId());
        roleMapper.insertPermissions(workspace.getId(), role.getId(), List.of("REPORT_READ"));
        assertFalse(userMapper.isPrivilegedAccount(user.getId()));

        Integer orgId = jdbcTemplate.queryForObject(
                "SELECT org_id FROM workspace WHERE id = ?", Integer.class, workspace.getId());
        orgMemberMapper.addMember(orgId, user.getId(), "admin");
        assertTrue(userMapper.isPrivilegedAccount(user.getId()));
    }

    @Test
    void sequenceManageButNotSequenceViewClassifiesCustomRoleAsPrivileged() {
        User user = newUser();
        WorkspaceRole role = new WorkspaceRole();
        role.setWorkspaceId(workspace.getId());
        role.setName("sequence-manager-" + unique());
        roleMapper.insertRole(role);
        workspaceMapper.setMemberCustomRole(workspace.getId(), user.getId(), role.getId());

        roleMapper.insertPermissions(workspace.getId(), role.getId(), List.of("SEQUENCE_VIEW"));
        assertFalse(userMapper.isPrivilegedAccount(user.getId()));

        roleMapper.clearPermissions(workspace.getId(), role.getId());
        roleMapper.insertPermissions(workspace.getId(), role.getId(), List.of("SEQUENCE_MANAGE"));
        assertTrue(userMapper.isPrivilegedAccount(user.getId()));
    }

    @Test
    void customRoleHoldingOnlyTeamManageIsPrivileged() {
        User user = newUser();
        WorkspaceRole role = new WorkspaceRole();
        role.setWorkspaceId(workspace.getId());
        role.setName("team-admin-" + unique());
        roleMapper.insertRole(role);
        workspaceMapper.setMemberCustomRole(workspace.getId(), user.getId(), role.getId());
        roleMapper.insertPermissions(workspace.getId(), role.getId(), List.of("TEAM_MANAGE"));
        assertTrue(userMapper.isPrivilegedAccount(user.getId()));
    }

    @Test
    void customRoleHoldingOnlyApiCredentialManageIsPrivileged() {
        User user = newUser();
        WorkspaceRole role = new WorkspaceRole();
        role.setWorkspaceId(workspace.getId());
        role.setName("api-credential-manager-" + unique());
        roleMapper.insertRole(role);
        workspaceMapper.setMemberCustomRole(workspace.getId(), user.getId(), role.getId());
        roleMapper.insertPermissions(
            workspace.getId(), role.getId(), List.of("API_CREDENTIAL_MANAGE"));

        assertTrue(userMapper.isPrivilegedAccount(user.getId()));
    }

    @Test
    void inventoryListsAnOrganizationMemberWithoutAPasskey() {
        Workspace fresh = freshWorkspace();
        User user = inventoryUser("hash_" + unique(), unique() + "@inventory.example.com");
        orgMemberMapper.addMember(fresh.getOrgId(), user.getId(), "admin");

        assertTrue(userMapper.isPrivilegedAccount(user.getId()));
        assertEquals(new UnenrolledPrivilegedAccount(user.getId(), true, true),
                unenrolledEntry(user.getId()));
    }

    @Test
    void inventoryOmitsAWorkspaceAdministratorWhoHoldsAPasskey() {
        Workspace fresh = freshWorkspace();
        User user = inventoryUser("hash_" + unique(), unique() + "@inventory.example.com");
        workspaceMapper.addMember(fresh.getId(), user.getId(), "admin");
        assertNotNull(unenrolledEntry(user.getId()));

        enrollPasskey(user);

        assertTrue(userMapper.isPrivilegedAccount(user.getId()));
        assertNull(unenrolledEntry(user.getId()));
    }

    @Test
    void inventoryListsACustomRoleHolderWithAdministrativePermissions() {
        Workspace fresh = freshWorkspace();
        User user = inventoryUser("hash_" + unique(), unique() + "@inventory.example.com");
        workspaceMapper.addMember(fresh.getId(), user.getId(), "member");
        WorkspaceRole role = new WorkspaceRole();
        role.setWorkspaceId(fresh.getId());
        role.setName("inventory-member-manager-" + unique());
        roleMapper.insertRole(role);
        workspaceMapper.setMemberCustomRole(fresh.getId(), user.getId(), role.getId());
        roleMapper.insertPermissions(fresh.getId(), role.getId(), List.of("REPORT_READ"));
        assertNull(unenrolledEntry(user.getId()));

        roleMapper.insertPermissions(fresh.getId(), role.getId(), List.of("MEMBER_MANAGE"));

        assertTrue(userMapper.isPrivilegedAccount(user.getId()));
        assertNotNull(unenrolledEntry(user.getId()));
    }

    @Test
    void inventoryOmitsAPlainMember() {
        Workspace fresh = freshWorkspace();
        User user = inventoryUser("hash_" + unique(), unique() + "@inventory.example.com");
        workspaceMapper.addMember(fresh.getId(), user.getId(), "member");

        assertFalse(userMapper.isPrivilegedAccount(user.getId()));
        assertNull(unenrolledEntry(user.getId()));
    }

    @Test
    void inventoryOmitsPrivilegeHeldOnlyInTenantsBeingTornDown() {
        Workspace orgTornDown = freshWorkspace();
        User orgAdmin = inventoryUser("hash_" + unique(), unique() + "@inventory.example.com");
        orgMemberMapper.addMember(orgTornDown.getOrgId(), orgAdmin.getId(), "owner");
        Workspace workspaceTornDown = freshWorkspace();
        User workspaceAdmin = inventoryUser("hash_" + unique(), unique() + "@inventory.example.com");
        workspaceMapper.addMember(workspaceTornDown.getId(), workspaceAdmin.getId(), "owner");
        assertNotNull(unenrolledEntry(orgAdmin.getId()));
        assertNotNull(unenrolledEntry(workspaceAdmin.getId()));

        jdbcTemplate.update("UPDATE organization SET lifecycle_state = 'tearing_down' WHERE id = ?",
                orgTornDown.getOrgId());
        jdbcTemplate.update("UPDATE workspace SET lifecycle_state = 'tearing_down' WHERE id = ?",
                workspaceTornDown.getId());
        sqlSession.clearCache();

        assertFalse(userMapper.isPrivilegedAccount(orgAdmin.getId()));
        assertFalse(userMapper.isPrivilegedAccount(workspaceAdmin.getId()));
        assertNull(unenrolledEntry(orgAdmin.getId()));
        assertNull(unenrolledEntry(workspaceAdmin.getId()));
    }

    @Test
    void inventoryExcludesTheSystemActor() {
        User system = userMapper.getUserByUsername("__connex_system__");
        assertNotNull(system);
        Workspace fresh = freshWorkspace();
        orgMemberMapper.addMember(fresh.getOrgId(), system.getId(), "admin");

        assertTrue(userMapper.isPrivilegedAccount(system.getId()));
        assertNull(unenrolledEntry(system.getId()));
    }

    @Test
    void inventoryFlagsSelfServicePrerequisitesAndCountsTheWholePopulation() {
        UnenrolledPrivilegedAccountCounts before = userMapper.countUnenrolledPrivilegedAccounts();
        assertNotNull(before);
        Workspace fresh = freshWorkspace();
        User passwordAndEmail = inventoryUser("hash_" + unique(), unique() + "@inventory.example.com");
        User passwordOnly = inventoryUser("hash_" + unique(), "");
        User passwordless = inventoryUser(null, unique() + "@inventory.example.com");
        for (User user : List.of(passwordAndEmail, passwordOnly, passwordless)) {
            orgMemberMapper.addMember(fresh.getOrgId(), user.getId(), "admin");
        }

        assertEquals(new UnenrolledPrivilegedAccount(passwordAndEmail.getId(), true, true),
                unenrolledEntry(passwordAndEmail.getId()));
        assertEquals(new UnenrolledPrivilegedAccount(passwordOnly.getId(), true, false),
                unenrolledEntry(passwordOnly.getId()));
        assertEquals(new UnenrolledPrivilegedAccount(passwordless.getId(), false, true),
                unenrolledEntry(passwordless.getId()));
        UnenrolledPrivilegedAccountCounts after = userMapper.countUnenrolledPrivilegedAccounts();
        assertNotNull(after);
        assertEquals(before.total() + 3, after.total());
        assertEquals(before.passwordBacked() + 2, after.passwordBacked());
        assertEquals(before.passwordBackedWithoutEmail() + 1, after.passwordBackedWithoutEmail());
    }

    @Test
    void inventoryListIsCappedInAscendingIdOrder() {
        Workspace fresh = freshWorkspace();
        for (int i = 0; i < 3; i++) {
            User user = inventoryUser("hash_" + unique(), unique() + "@inventory.example.com");
            orgMemberMapper.addMember(fresh.getOrgId(), user.getId(), "admin");
        }

        List<UnenrolledPrivilegedAccount> capped = userMapper.listUnenrolledPrivilegedAccounts(2);

        assertEquals(2, capped.size());
        assertTrue(capped.get(0).id() < capped.get(1).id());
    }

    private UnenrolledPrivilegedAccount unenrolledEntry(int userId) {
        return userMapper.listUnenrolledPrivilegedAccounts(Integer.MAX_VALUE).stream()
                .filter(account -> account.id() == userId)
                .findFirst()
                .orElse(null);
    }

    private Workspace freshWorkspace() {
        String suffix = unique();
        jdbcTemplate.update("INSERT INTO organization (name, slug) VALUES (?, ?)",
                "Inventory " + suffix, "inventory-" + suffix);
        Integer orgId = jdbcTemplate.queryForObject(
                "SELECT id FROM organization WHERE slug = ?", Integer.class, "inventory-" + suffix);
        assertNotNull(orgId);
        Workspace fresh = new Workspace();
        fresh.setOrgId(orgId);
        fresh.setName("Inventory " + suffix);
        fresh.setSlug("inventory-" + suffix);
        workspaceMapper.insert(fresh);
        return fresh;
    }

    private User inventoryUser(String passwordHash, String email) {
        String suffix = unique();
        User user = new User();
        user.setUsername("inventory_" + suffix);
        user.setDisplayName("Inventory " + suffix);
        user.setEmail(email);
        user.setPasswordHash(passwordHash);
        user.setTimezone("UTC");
        userMapper.insert(user);
        return user;
    }

    private void enrollPasskey(User user) {
        String handle = "inventory-handle-" + unique();
        jdbcTemplate.update(
                "INSERT INTO webauthn_user_entity (id, user_id, name, display_name) VALUES (?, ?, ?, ?)",
                handle, user.getId(), user.getUsername(), user.getDisplayName());
        jdbcTemplate.update(
                "INSERT INTO webauthn_credential (credential_id, user_entity_user_id, public_key) VALUES (?, ?, ?)",
                ("inventory-credential-" + unique()).getBytes(StandardCharsets.UTF_8),
                handle, new byte[] {9, 9, 9});
        sqlSession.clearCache();
    }

    private User newUnassignedUser() {
        String suffix = unique();
        User user = new User();
        user.setUsername("unassigned_" + suffix);
        user.setDisplayName("Unassigned " + suffix);
        user.setEmail(suffix + "@unassigned.example.com");
        user.setPasswordHash("hash_" + suffix);
        user.setTimezone("UTC");
        userMapper.insert(user);
        return user;
    }
}
