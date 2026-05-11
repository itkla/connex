package ooo.klae.connex.backend.mappers;

import org.apache.ibatis.annotations.Param;

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
    User getUserByEmail(String email);
    int insert(User user);
    int update(User user);
    int delete(int id);
    int updateLastLoginAt(int id);
    int updateProfilePictureUrl(@Param("id") int id, @Param("profilePictureUrl") String profilePictureUrl);
    int updateDisplayName(@Param("id") int id, @Param("displayName") String displayName);
    int updateEmail(@Param("id") int id, @Param("email") String email);
    int updateUsername(@Param("id") int id, @Param("username") String username);
    int updatePasswordHash(@Param("id") int id, @Param("passwordHash") String passwordHash);
    int updateCreatedAt(@Param("id") int id, @Param("createdAt") String createdAt);
    int updateUpdatedAt(@Param("id") int id, @Param("updatedAt") String updatedAt);
}
