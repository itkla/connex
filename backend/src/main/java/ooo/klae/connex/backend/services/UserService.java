package ooo.klae.connex.backend.services;

import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import ooo.klae.connex.backend.mappers.UserMapper;
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
}
