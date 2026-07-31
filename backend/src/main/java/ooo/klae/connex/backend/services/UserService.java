package ooo.klae.connex.backend.services;

import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;

import ooo.klae.connex.backend.mappers.ActivityMapper;
import ooo.klae.connex.backend.mappers.NoteMapper;
import ooo.klae.connex.backend.mappers.TaskMapper;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.beans.Activity;
import ooo.klae.connex.backend.beans.Note;
import ooo.klae.connex.backend.beans.Task;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.dto.UserReferenceDto;
import ooo.klae.connex.backend.notifications.NotificationChangePublisher;
import ooo.klae.connex.backend.storage.ManagedObjectService;
import ooo.klae.connex.backend.storage.ManagedObjectService.ManagedContent;
import ooo.klae.connex.backend.storage.UploadSource;
import ooo.klae.connex.backend.tenant.TenantWorkScope;
import ooo.klae.connex.backend.connectedaccounts.ProviderAccountOffboardingService;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

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
    private final WorkspaceService workspaceService;
    private final NotificationChangePublisher notificationChanges;
    private final ReferenceService referenceService;
    private final ManagedObjectService managedObjectService;
    private final UserProfilePictureTransaction profilePictureTransaction;
    private final TenantWorkScope tenantWorkScope;
    private final ProviderAccountOffboardingService providerAccountOffboardingService;
    private final UserAccountCatalogOffboardingService catalogOffboardingService;
    private final UserDeletionTransaction userDeletionTransaction;

    private static final Set<String> AUDIT_FIELDS =
        Set.of("username", "displayName", "email", "department", "title",
               "employeeId", "phoneNumber", "profilePictureUrl", "timezone", "locale");

    /**
     * Resolves the authenticating principal by login identifier, which may be either a
     * username or an email address. Because a username can never contain an {@code @}
     * (registration validation forbids it), the presence of {@code @} unambiguously selects
     * an email lookup, keeping the username and email namespaces disjoint and collision-free.
     * @param identifier the submitted username or email address
     * @return the matching user as Spring Security {@code UserDetails}
     * @throws UsernameNotFoundException if no account matches the identifier
     */
    @Override
    public UserDetails loadUserByUsername(String identifier) throws UsernameNotFoundException {
        User user = identifier != null && identifier.indexOf('@') >= 0
            ? userMapper.getUserByEmail(identifier)
            : userMapper.getUserByUsername(identifier);
        if (user == null) throw new UsernameNotFoundException("User not found: " + identifier);
        return user;
    }

    public List<User> getAllUsers() {
        return workspaceService.getMembers(workspaceService.getCurrentWorkspaceId());
    }

    /** Returns requested active-member references without loading the whole workspace directory. */
    public List<UserReferenceDto> getActiveWorkspaceMemberReferences(List<Integer> ids) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        List<UserReferenceDto> references = tenantWorkScope.unrouted(() ->
            userMapper.getActiveWorkspaceMemberReferencesByIds(workspaceId, ids));
        Map<Integer, UserReferenceDto> referencesById = references.stream().collect(
            Collectors.toMap(UserReferenceDto::id, Function.identity()));
        return ids.stream()
            .map(referencesById::get)
            .filter(Objects::nonNull)
            .toList();
    }

    public User getUserById(int id) {
        if (!workspaceService.isMember(workspaceService.getCurrentWorkspaceId(), id)) {
            throw new ResourceNotFoundException("User not found with id: " + id);
        }
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

    /**
     * Updates the caller's own editable profile fields. The account email is
     * intentionally immutable here: because email is a trust anchor (email-bound
     * invites rely on it), it can only change through the verified, ownership-proving
     * flow in {@code EmailChangeService}, so any email in the request body is ignored.
     * @param id the user being updated (must be the caller)
     * @param user the submitted profile fields
     * @return the updated user
     */
    @Transactional
    public User update(int id, User user) {
        workspaceService.requireSelf(id);
        User before = getUserById(id);
        user.setId(id);
        user.setEmail(before.getEmail());
        if (user.getTimezone() == null || user.getTimezone().isBlank()) {
            user.setTimezone(before.getTimezone());
        } else {
            user.setTimezone(TimezoneSupport.validateIana(user.getTimezone(), null));
        }
        user.setLocale(before.getLocale());
        user.setProfilePictureUrl(before.getProfilePictureUrl());
        userMapper.update(user);
        User after = userMapper.getUserById(id);
        if (after == null) {
            throw new ResourceNotFoundException("User not found with id: " + id);
        }
        auditService.record("user.update", "user", id, after.getUsername(),
            "Updated user " + after.getUsername(),
            auditService.diff(before, after, AUDIT_FIELDS));
        return after;
    }

    /**
     * Deletes the caller's own account. Org-data references are guarded and
     * erased in the service layer ({@link UserOffboardingService}) rather than
     * by cross-plane foreign keys (#440 increment 3); control-plane rows
     * (memberships, credentials, sessions) still cascade from {@code app_user}.
     * The audit record is written while the actor row still exists — recording
     * after the delete violated the actor foreign key inside the transaction
     * and the event was silently swallowed, leaving account erasure unaudited.
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void delete(int id) {
        workspaceService.requireSelf(id);
        String reservationOwner = UUID.randomUUID().toString();
        tenantWorkScope.unrouted(() -> {
            userDeletionTransaction.reserve(id, reservationOwner);
            return null;
        });
        try {
            catalogOffboardingService.assertNoAuthoredContent(id);
            tenantWorkScope.unrouted(() -> {
                userDeletionTransaction.renew(id, reservationOwner);
                return null;
            });
            providerAccountOffboardingService.purgeBeforeAccountDeletion(id);
            tenantWorkScope.unrouted(() -> {
                userDeletionTransaction.renew(id, reservationOwner);
                return null;
            });
            catalogOffboardingService.eraseReferences(id);
            tenantWorkScope.unrouted(() -> {
                userDeletionTransaction.renew(id, reservationOwner);
                userDeletionTransaction.delete(id, reservationOwner);
                return null;
            });
        } catch (RuntimeException exception) {
            tenantWorkScope.unrouted(() -> {
                userDeletionTransaction.release(id, reservationOwner);
                return null;
            });
            throw exception;
        }
    }

    /**
     * Retrieves the activities created by a user.
     * @param userId
     * @return
     */
    public List<Activity> getActivitiesByUserId(int userId) {
        getUserById(userId);
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        return referenceService.hydrateActivities(workspaceId,
            activityMapper.getActivitiesByCreatedById(workspaceId, userId));
    }

    /**
     * Retrieves the tasks assigned to a user.
     * @param userId
     * @return
     */
    public List<Task> getTasksByUserId(int userId) {
        getUserById(userId);
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        return referenceService.hydrateTasks(workspaceId, taskMapper.getTasksByAssignedToId(workspaceId, userId));
    }

    /**
     * Retrieves the notes authored by a user.
     * @param userId
     * @return
     */
    public List<Note> getNotesByUserId(int userId) {
        getUserById(userId);
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        return referenceService.hydrate(workspaceId, noteMapper.getVisibleNotesByAuthorId(workspaceId, userId, workspaceService.getCurrentUserId()));
    }

    public User updateCurrentProfilePicture(int userId, UploadSource source) {
        workspaceService.requireSelf(userId);
        UserProfilePictureTransaction.Result result = tenantWorkScope.unrouted(
            () -> profilePictureTransaction.update(userId, source));
        User before = result.before();
        User after = result.after();
        auditService.record("user.updateAvatar", "user", userId, before.getUsername(),
            "Updated profile picture for " + before.getUsername(),
            auditService.singleChange("profilePictureUrl", before.getProfilePictureUrl(), after.getProfilePictureUrl()));
        return after;
    }

    public ManagedContent getProfilePictureContent(int userId, String token) {
        User user = getUserById(userId);
        return managedObjectService.openUserImage(userId, user.getProfilePictureUrl(), token);
    }

    public User updateTimezone(int userId, String timezone) {
        workspaceService.requireSelf(userId);
        User before = getUserById(userId);
        String validated = TimezoneSupport.validateIana(timezone, null);
        userMapper.updateTimezone(userId, validated);
        auditService.record("user.updateTimezone", "user", userId, before.getUsername(),
            "Updated timezone for " + before.getUsername(),
            auditService.singleChange("timezone", before.getTimezone(), validated));
        notificationChanges.publish(workspaceService.getCurrentWorkspaceId(), "user", userId);
        return userMapper.getUserById(userId);
    }

    public User updateLocale(int userId, String locale) {
        workspaceService.requireSelf(userId);
        User before = getUserById(userId);
        String validated = LocaleSupport.validate(locale, null);
        if (validated.equals(before.getLocale())) {
            return before;
        }
        userMapper.updateLocale(userId, validated);
        auditService.record("user.updateLocale", "user", userId, before.getUsername(),
            "Updated locale for " + before.getUsername(),
            auditService.singleChange("locale", before.getLocale(), validated));
        return userMapper.getUserById(userId);
    }
}
