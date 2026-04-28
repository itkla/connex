package ooo.klae.connex.backend.controllers;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.services.UserService;

import java.util.List;

import lombok.RequiredArgsConstructor;

/**
 * REST controller for managing {@code User} records (Connex account holders).
 * Exposes endpoints for reading and updating the current user's profile.
 * Delegates to {@code UserService}.
 */

@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {
    private final UserService userService;

    /**
     * GET endpoint to retrieve all users. This will return *all* users, not necessarily just the current user
     * @return
     */
    @GetMapping
    public List<User> getAllUsers() {
        return userService.getAllUsers();
    }

    /**
     * GET endpoint to retrieve a single user by ID.
     * @param id
     * @return
     */
    @GetMapping("/{id}")
    public User getUserById(@PathVariable int id) {
        return userService.getUserById(id);
    }

    /**
     * POST endpoint to create a new user.
     * @param user
     * @return
     */
    @PostMapping
    public User createUser(@RequestBody User user) {
        return userService.create(user);
    }

    /**
     * PUT endpoint to update an existing user.
     * @param id
     * @param user
     * @return
     */
    @PutMapping("/{id}")
    public User updateUser(@PathVariable int id, @RequestBody User user) {
        return userService.update(id, user);
    }

    /**
     * DELETE endpoint to delete a user by ID.
     * @param id
     */
    @DeleteMapping("/{id}")
    public void deleteUser(@PathVariable int id) {
        userService.delete(id);
    }
}
