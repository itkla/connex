package ooo.klae.connex.backend.services;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;

import tools.jackson.databind.ObjectMapper;

import ooo.klae.connex.backend.beans.Activity;
import ooo.klae.connex.backend.beans.AuditLog;
import ooo.klae.connex.backend.beans.Note;
import ooo.klae.connex.backend.beans.Task;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.mappers.AuditLogMapper;

class UserServiceTest extends AbstractServiceTest {

    @Autowired UserService userService;
    @Autowired AuditLogMapper auditLogMapper;
    @Autowired AuthenticationManager authenticationManager;
    @Autowired ObjectMapper objectMapper;
    @Autowired PasswordEncoder passwordEncoder;

    @Test
    void getActivitiesByUserId_returnsOnlyActivitiesCreatedByUser() {
        User user1 = newUser();
        User user2 = newUser();
        Activity a1 = newActivity(user1, null, null);
        Activity a2 = newActivity(user2, null, null);

        List<Activity> activities = userService.getActivitiesByUserId(user1.getId());

        assertTrue(activities.stream().anyMatch(x -> x.getId() == a1.getId()));
        assertTrue(activities.stream().noneMatch(x -> x.getId() == a2.getId()));
    }

    @Test
    void getActivitiesByUserId_throwsWhenUserMissing() {
        assertThrows(ResourceNotFoundException.class, () -> userService.getActivitiesByUserId(-1));
    }

    @Test
    void getTasksByUserId_returnsOnlyTasksAssignedToUser() {
        User user1 = newUser();
        User user2 = newUser();
        Task t1 = newTask(user1, null, null);
        Task t2 = newTask(user2, null, null);

        List<Task> tasks = userService.getTasksByUserId(user1.getId());

        assertTrue(tasks.stream().anyMatch(x -> x.getId() == t1.getId()));
        assertTrue(tasks.stream().noneMatch(x -> x.getId() == t2.getId()));
    }

    @Test
    void getTasksByUserId_throwsWhenUserMissing() {
        assertThrows(ResourceNotFoundException.class, () -> userService.getTasksByUserId(-1));
    }

    @Test
    void getNotesByUserId_returnsOnlyNotesAuthoredByUser() {
        User user1 = newUser();
        User user2 = newUser();
        Note n1 = newNote(user1, null, null);
        Note n2 = newNote(user2, null, null);

        List<Note> notes = userService.getNotesByUserId(user1.getId());

        assertTrue(notes.stream().anyMatch(x -> x.getId() == n1.getId()));
        assertTrue(notes.stream().noneMatch(x -> x.getId() == n2.getId()));
    }

    @Test
    void getNotesByUserId_throwsWhenUserMissing() {
        assertThrows(ResourceNotFoundException.class, () -> userService.getNotesByUserId(-1));
    }

    @Test
    void update_byNonAdminMember_onAnotherUsersAccount_isForbidden() {
        User attacker = newUser();
        User victim = newUser();
        authenticateAs(attacker);

        assertThrows(ForbiddenException.class,
            () -> userService.update(victim.getId(), profileUpdate(victim, "Hacked Name")));
        assertEquals(victim.getDisplayName(), userMapper.getUserById(victim.getId()).getDisplayName());
    }

    @Test
    void delete_byNonAdminMember_onAnotherUsersAccount_isForbidden() {
        User attacker = newUser();
        User victim = newUser();
        authenticateAs(attacker);

        assertThrows(ForbiddenException.class, () -> userService.delete(victim.getId()));
        assertTrue(userMapper.getUserById(victim.getId()) != null);
    }

    @Test
    void updateProfilePicture_byNonAdminMember_onAnotherUsersAccount_isForbidden() {
        User attacker = newUser();
        User victim = newUser();
        authenticateAs(attacker);

        assertThrows(ForbiddenException.class,
            () -> userService.updateProfilePictureUrl(victim.getId(), "/profile-pictures/x.png"));
    }

    @Test
    void update_onOwnAccount_isAllowed() {
        User member = newUser();
        authenticateAs(member);

        assertDoesNotThrow(() -> userService.update(member.getId(), profileUpdate(member, "My New Name")));
        assertEquals("My New Name", userMapper.getUserById(member.getId()).getDisplayName());
    }

    @Test
    void genericProfileUpdateCannotChangeLocale() {
        User member = newUser();
        authenticateAs(member);
        User update = profileUpdate(member, member.getDisplayName());
        update.setLocale("ja");

        User updated = userService.update(member.getId(), update);

        assertEquals("en", updated.getLocale());
        assertEquals("en", userMapper.getUserById(member.getId()).getLocale());
    }

    @Test
    void update_byWorkspaceAdmin_onAnotherUsersAccount_isForbidden() {
        User member = newUser();

        assertThrows(ForbiddenException.class,
            () -> userService.update(member.getId(), profileUpdate(member, "Renamed By Admin")));
        assertEquals(member.getDisplayName(), userMapper.getUserById(member.getId()).getDisplayName());
    }

    @Test
    void updateLocale_onOwnAccountPersistsSupportedValue() throws Exception {
        User member = newUser();
        authenticateAs(member);

        User updated = userService.updateLocale(member.getId(), "ja");

        assertEquals("ja", updated.getLocale());
        assertEquals("ja", userMapper.getUserById(member.getId()).getLocale());
        AuditLog audit = auditLogMapper.findRecent(workspace.getId(), 50, 0).stream()
                .filter(entry -> "user.updateLocale".equals(entry.getAction()))
                .filter(entry -> Integer.valueOf(member.getId()).equals(entry.getEntityId()))
                .findFirst()
                .orElseThrow();
        assertEquals(member.getId(), audit.getActorId());
        var changes = objectMapper.readTree(audit.getChanges());
        assertEquals("en", changes.path("locale").path("old").asText());
        assertEquals("ja", changes.path("locale").path("new").asText());
    }

    @Test
    void updateLocale_rejectsUnsupportedValueWithoutChangingAccount() {
        User member = newUser();
        authenticateAs(member);
        long before = localeAuditCount(member.getId());

        assertThrows(BadRequestException.class, () -> userService.updateLocale(member.getId(), "fr"));
        assertEquals("en", userMapper.getUserById(member.getId()).getLocale());
        assertEquals(before, localeAuditCount(member.getId()));
    }

    @Test
    void updateLocale_onAnotherAccountIsForbidden() {
        User attacker = newUser();
        User victim = newUser();
        authenticateAs(attacker);

        assertThrows(ForbiddenException.class, () -> userService.updateLocale(victim.getId(), "ja"));
        assertEquals("en", userMapper.getUserById(victim.getId()).getLocale());
    }

    @Test
    void loadUserByUsername_resolvesByUsername() {
        User user = newUser();
        UserDetails loaded = userService.loadUserByUsername(user.getUsername());
        assertEquals(user.getId(), ((User) loaded).getId());
    }

    @Test
    void loadUserByUsername_resolvesByEmail() {
        User user = newUser();
        UserDetails loaded = userService.loadUserByUsername(user.getEmail());
        assertEquals(user.getId(), ((User) loaded).getId());
    }

    @Test
    void loadUserByUsername_throwsForUnknownIdentifier() {
        assertThrows(UsernameNotFoundException.class,
            () -> userService.loadUserByUsername("no_such_" + unique()));
        assertThrows(UsernameNotFoundException.class,
            () -> userService.loadUserByUsername(unique() + "@nowhere.example.com"));
    }

    @Test
    void loadUserByUsername_keepsUsernameAndEmailNamespacesDisjoint() {
        String token = "collider_" + unique();
        User byUsername = insertRawUser(token, unique() + "@example.com");
        User byEmail = insertRawUser("user_" + unique(), token + "@example.com");

        assertEquals(byUsername.getId(), ((User) userService.loadUserByUsername(token)).getId());
        assertEquals(byEmail.getId(),
            ((User) userService.loadUserByUsername(token + "@example.com")).getId());
    }

    @Test
    void authenticationManager_authenticatesByEitherUsernameOrEmail() {
        String raw = "Sup3rSecret!";
        User user = insertRawUser("user_" + unique(), unique() + "@example.com");
        userMapper.updatePasswordHash(user.getId(), passwordEncoder.encode(raw));

        Authentication byUsername = authenticationManager.authenticate(
            new UsernamePasswordAuthenticationToken(user.getUsername(), raw));
        Authentication byEmail = authenticationManager.authenticate(
            new UsernamePasswordAuthenticationToken(user.getEmail(), raw));

        assertEquals(user.getId(), ((User) byUsername.getPrincipal()).getId());
        assertEquals(user.getId(), ((User) byEmail.getPrincipal()).getId());
    }

    private User insertRawUser(String username, String email) {
        User user = new User();
        user.setUsername(username);
        user.setDisplayName("Raw " + username);
        user.setEmail(email);
        user.setPasswordHash("hash");
        user.setTimezone("UTC");
        userMapper.insert(user);
        return user;
    }

    private long localeAuditCount(int userId) {
        return auditLogMapper.findRecent(workspace.getId(), 100, 0).stream()
                .filter(entry -> "user.updateLocale".equals(entry.getAction()))
                .filter(entry -> Integer.valueOf(userId).equals(entry.getEntityId()))
                .count();
    }

    private void authenticateAs(User user) {
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities()));
    }

    private User profileUpdate(User base, String newDisplayName) {
        User update = new User();
        update.setUsername(base.getUsername());
        update.setEmail(base.getEmail());
        update.setDisplayName(newDisplayName);
        update.setTimezone(base.getTimezone());
        return update;
    }
}
