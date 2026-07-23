package ooo.klae.connex.backend.mappers;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.dto.UserDisplayNameDto;

class UserMapperTest extends AbstractMapperTest {
    @Autowired private JdbcTemplate jdbcTemplate;

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
    void getActiveWorkspaceMemberDisplayNamesByIdsExcludesPendingAndNonmembers() {
        User active = newUser();
        User pending = newUnassignedUser();
        User nonmember = newUnassignedUser();
        workspaceMapper.addPendingMember(workspace.getId(), pending.getId(), "member");

        List<UserDisplayNameDto> labels = userMapper
            .getActiveWorkspaceMemberDisplayNamesByIds(
                workspace.getId(), List.of(active.getId(), pending.getId(), nonmember.getId()));

        assertEquals(
            List.of(new UserDisplayNameDto(active.getId(), active.getDisplayName())), labels);
    }

    /**
     * Updates a user and checks if the new values are persisted.
     */
    @Test
    void update_persistsNewValues() {
        User user = newUser();
        user.setDisplayName("Mr. New Name");
        user.setEmail("renamed_" + unique() + "@example.com");

        userMapper.update(user);

        User found = userMapper.getUserById(user.getId());
        assertEquals("Mr. New Name", found.getDisplayName());
        assertEquals(user.getEmail(), found.getEmail());
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
