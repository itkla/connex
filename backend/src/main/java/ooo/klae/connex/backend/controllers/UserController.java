package ooo.klae.connex.backend.controllers;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import ooo.klae.connex.backend.dto.ActivityDto;
import ooo.klae.connex.backend.dto.NoteDto;
import ooo.klae.connex.backend.dto.RegisterDto;
import ooo.klae.connex.backend.dto.TaskDto;
import ooo.klae.connex.backend.dto.UpdateLocaleDto;
import ooo.klae.connex.backend.dto.UpdateTimezoneDto;
import ooo.klae.connex.backend.dto.UserDto;
import ooo.klae.connex.backend.services.AuthService;
import ooo.klae.connex.backend.services.SessionSecurityService;
import ooo.klae.connex.backend.services.UserService;
import ooo.klae.connex.backend.services.WorkspaceService;
import ooo.klae.connex.backend.tenant.Permission;

import java.util.List;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * REST controller for managing {@code User} records (Connex account holders).
 * Exposes endpoints for reading and updating the current user's profile.
 */

@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {
    private final UserService userService;
    private final AuthService authService;
    private final WorkspaceService workspaceService;
    private final SessionSecurityService sessionSecurityService;

    /**
     * GET endpoint to retrieve all users. This will return *all* users, not necessarily just the current user
     * @return
     */
    @GetMapping
    public List<UserDto> getAllUsers() {
        return userService.getAllUsers().stream().map(UserDto::from).toList();
    }

    /**
     * GET endpoint to retrieve a single user by ID.
     * @param id
     * @return
     */
    @GetMapping("/{id}")
    public UserDto getUserById(@PathVariable int id) {
        return UserDto.from(userService.getUserById(id));
    }

    /**
     * POST endpoint to create a new user.
     * @param dto
     * @return
     */
    @PostMapping
    public UserDto createUser(@Valid @RequestBody RegisterDto dto) {
        workspaceService.requirePermission(Permission.MEMBER_MANAGE);
        sessionSecurityService.requireRecentAuthentication(authService.getCurrentUser().getId());
        return UserDto.from(authService.register(dto, true));
    }

    /**
     * PUT endpoint to update an existing user.
     * @param id
     * @param user
     * @return
     */
    @PutMapping("/{id}")
    public UserDto updateUser(@PathVariable int id, @Valid @RequestBody UserDto dto) {
        return UserDto.from(userService.update(id, dto.toBean()));
    }

    /**
     * DELETE endpoint to delete a user by ID.
     * @param id
     */
    @DeleteMapping("/{id}")
    public void deleteUser(@PathVariable int id) {
        userService.delete(id);
    }

    /**
     * GET endpoint to retrieve activities created by a user.
     * @param id
     * @return
     */
    @GetMapping("/{id}/activities")
    public List<ActivityDto> getActivitiesForUser(@PathVariable int id) {
        return userService.getActivitiesByUserId(id).stream().map(ActivityDto::from).toList();
    }

    /**
     * GET endpoint to retrieve tasks assigned to a user.
     * @param id
     * @return
     */
    @GetMapping("/{id}/tasks")
    public List<TaskDto> getTasksForUser(@PathVariable int id) {
        return userService.getTasksByUserId(id).stream().map(TaskDto::from).toList();
    }

    /**
     * GET endpoint to retrieve notes authored by a user.
     * @param id
     * @return
     */
    @GetMapping("/{id}/notes")
    public List<NoteDto> getNotesForUser(@PathVariable int id) {
        return userService.getNotesByUserId(id).stream().map(NoteDto::from).toList();
    }

    /**
     * PUT endpoint to update the profile picture of a user.
     * @param id
     * @param profilePictureUrl
     * @return
     */
    @PutMapping("/{id}/profile-picture")
    public UserDto updateProfilePicture(@PathVariable int id, @RequestBody String profilePictureUrl) {
        return UserDto.from(userService.updateProfilePictureUrl(id, profilePictureUrl));
    }

    @PatchMapping("/me")
    public UserDto updateCurrentUser(@Valid @RequestBody UpdateTimezoneDto dto) {
        return UserDto.from(userService.updateTimezone(authService.getCurrentUser().getId(), dto.getTimezone()));
    }

    @PatchMapping("/me/locale")
    public UserDto updateCurrentUserLocale(@Valid @RequestBody UpdateLocaleDto dto) {
        return UserDto.from(userService.updateLocale(authService.getCurrentUser().getId(), dto.getLocale()));
    }
}
