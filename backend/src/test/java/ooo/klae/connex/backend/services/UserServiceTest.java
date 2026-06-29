package ooo.klae.connex.backend.services;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import ooo.klae.connex.backend.beans.Activity;
import ooo.klae.connex.backend.beans.Note;
import ooo.klae.connex.backend.beans.Task;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;

class UserServiceTest extends AbstractServiceTest {

    @Autowired UserService userService;

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
    void update_byWorkspaceAdmin_onAnotherUsersAccount_isForbidden() {
        User member = newUser();

        assertThrows(ForbiddenException.class,
            () -> userService.update(member.getId(), profileUpdate(member, "Renamed By Admin")));
        assertEquals(member.getDisplayName(), userMapper.getUserById(member.getId()).getDisplayName());
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
