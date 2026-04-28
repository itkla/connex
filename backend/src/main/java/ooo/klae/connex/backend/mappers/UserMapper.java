package ooo.klae.connex.backend.mappers;

import ooo.klae.connex.backend.beans.User;

import java.util.List;

/**
 * Mpper interface for {@code User} persistence.
 * SQL is defined in {@code resources/mappers/UserMapper.xml}.
 * Used by {@code UserService} and {@code AuthService}.
 */

public interface UserMapper {
    List<User> getAllUsers();
    User getUserById(int id);
    User getUserByUsername(String username);
    int insert(User user);
    int update(User user);
    int delete(int id);
}
