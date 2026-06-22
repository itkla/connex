package ooo.klae.connex.backend.services;

import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import ooo.klae.connex.backend.mappers.ActivityMapper;
import ooo.klae.connex.backend.mappers.NoteMapper;
import ooo.klae.connex.backend.mappers.TaskMapper;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.beans.Activity;
import ooo.klae.connex.backend.beans.Note;
import ooo.klae.connex.backend.beans.Task;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;

import java.util.List;
import java.util.Set;

import lombok.RequiredArgsConstructor;

/**
 * Business logic for {@code User} management.
 * Also implements Spring Security's {@code UserDetailsService} to load users during authentication.
 * Delegates persistence to {@code UserMapper}.
 */

@Service
@RequiredArgsConstructor
public class UserService implements UserDetailsService {
    private final UserMapper userMapper;
    private final ActivityMapper activityMapper;
    private final NoteMapper noteMapper;
    private final TaskMapper taskMapper;
    private final AuditService auditService;

    private static final Set<String> AUDIT_FIELDS =
        Set.of("username", "displayName", "email", "department", "title",
               "employeeId", "phoneNumber", "profilePictureUrl");

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        User user = userMapper.getUserByUsername(username);
        if (user == null) throw new UsernameNotFoundException("User not found: " + username);
        return user;
    }

    public List<User> getAllUsers() {
        return userMapper.getAllUsers();
    }

    public User getUserById(int id) {
        User user = userMapper.getUserById(id);
        if (user == null) throw new ResourceNotFoundException("User not found with id: " + id);
        return user;
    }

    public User getUserByUsername(String username) {
        User user = userMapper.getUserByUsername(username);
        if (user == null) throw new ResourceNotFoundException("User not found with username: " + username);
        return user;
    }

    public User create(User user) {
        userMapper.insert(user);
        auditService.record("user.create", "user", user.getId(), user.getUsername(),
            "Created user " + user.getUsername(),
            auditService.diff(null, user, AUDIT_FIELDS));
        return user;
    }

    public User update(int id, User user) {
        User before = userMapper.getUserById(id);
        if (before == null) throw new ResourceNotFoundException("User not found with id: " + id);
        user.setId(id);
        userMapper.update(user);
        auditService.record("user.update", "user", id, user.getUsername(),
            "Updated user " + user.getUsername(),
            auditService.diff(before, user, AUDIT_FIELDS));
        return user;
    }

    public void delete(int id) {
        User before = userMapper.getUserById(id);
        if (before == null) throw new ResourceNotFoundException("User not found with id: " + id);
        userMapper.delete(id);
        auditService.record("user.delete", "user", id, before.getUsername(),
            "Deleted user " + before.getUsername(),
            auditService.diff(before, null, AUDIT_FIELDS));
    }

    /**
     * Retrieves the activities created by a user.
     * @param userId
     * @return
     */
    public List<Activity> getActivitiesByUserId(int userId) {
        if (userMapper.getUserById(userId) == null) throw new ResourceNotFoundException("User not found with id: " + userId);
        return activityMapper.getActivitiesByCreatedById(userId);
    }

    /**
     * Retrieves the tasks assigned to a user.
     * @param userId
     * @return
     */
    public List<Task> getTasksByUserId(int userId) {
        if (userMapper.getUserById(userId) == null) throw new ResourceNotFoundException("User not found with id: " + userId);
        return taskMapper.getTasksByAssignedToId(userId);
    }

    /**
     * Retrieves the notes authored by a user.
     * @param userId
     * @return
     */
    public List<Note> getNotesByUserId(int userId) {
        if (userMapper.getUserById(userId) == null) throw new ResourceNotFoundException("User not found with id: " + userId);
        return noteMapper.getNotesByAuthorId(userId);
    }

    /**
     * Updates the profile picture of a user.
     * @param userId
     * @param profilePictureUrl
     * @return
     */
    public User updateProfilePictureUrl(int userId, String profilePictureUrl) {
        User before = userMapper.getUserById(userId);
        if (before == null) throw new ResourceNotFoundException("User not found with id: " + userId);
        userMapper.updateProfilePictureUrl(userId, profilePictureUrl);
        auditService.record("user.updateAvatar", "user", userId, before.getUsername(),
            "Updated profile picture for " + before.getUsername(),
            auditService.singleChange("profilePictureUrl", before.getProfilePictureUrl(), profilePictureUrl));
        return userMapper.getUserById(userId);
    }
}
