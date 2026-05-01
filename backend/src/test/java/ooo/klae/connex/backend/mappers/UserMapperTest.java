package ooo.klae.connex.backend.mappers;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

import ooo.klae.connex.backend.beans.User;

class UserMapperTest extends AbstractMapperTest {

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

    /**
     * Deletes a user and checks if the user is removed.
     */
    @Test
    void delete_removesRow() {
        User user = newUser();

        userMapper.delete(user.getId());

        assertNull(userMapper.getUserById(user.getId()));
    }
}
