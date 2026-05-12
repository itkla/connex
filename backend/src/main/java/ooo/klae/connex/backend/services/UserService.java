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
        return user;
    }

    public User update(int id, User user) {
        if (userMapper.getUserById(id) == null) throw new ResourceNotFoundException("User not found with id: " + id);
        user.setId(id);
        userMapper.update(user);
        return user;
    }

    public void delete(int id) {
        if (userMapper.getUserById(id) == null) throw new ResourceNotFoundException("User not found with id: " + id);
        userMapper.delete(id);
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
        if (userMapper.getUserById(userId) == null) throw new ResourceNotFoundException("User not found with id: " + userId);
        userMapper.updateProfilePictureUrl(userId, profilePictureUrl);
        return userMapper.getUserById(userId);
    }
}
