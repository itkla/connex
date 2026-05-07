package ooo.klae.connex.backend.services;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import ooo.klae.connex.backend.beans.Activity;
import ooo.klae.connex.backend.beans.Note;
import ooo.klae.connex.backend.beans.Task;
import ooo.klae.connex.backend.beans.User;
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
}
