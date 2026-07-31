package ooo.klae.connex.backend.services;

import java.util.Set;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.exceptions.ConflictException;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.storage.ManagedObjectService;

/**
 * Single-catalog account deletion transaction entered after provider catalog fan-out.
 */
@Component
@RequiredArgsConstructor
public class UserDeletionTransaction {
    private static final Set<String> AUDIT_FIELDS =
        Set.of("username", "displayName", "email", "department", "title",
            "employeeId", "phoneNumber", "profilePictureUrl", "timezone", "locale");

    private final UserMapper userMapper;
    private final WorkspaceService workspaceService;
    private final OrgMemberService orgMemberService;
    private final ManagedObjectService managedObjectService;
    private final AuditService auditService;

    /** Guards the account and installs a durable reservation before provider fan-out. */
    @Transactional
    public void reserve(int id, String owner) {
        guard(id);
        if (userMapper.reserveAccountDeletion(id, owner) != 1) {
            throw new ConflictException("Account deletion is already in progress");
        }
    }

    /** Extends the owner-bound deletion lease between catalog fan-out phases. */
    @Transactional
    public void renew(int id, String owner) {
        if (userMapper.renewAccountDeletionReservation(id, owner) != 1) {
            throw new ConflictException("Account deletion reservation expired");
        }
    }

    /** Clears a failed account-deletion reservation so the operation can be retried. */
    @Transactional
    public void release(int id, String owner) {
        userMapper.clearAccountDeletionReservation(id, owner);
    }

    /** Locks, revalidates, erases, audits, and deletes the reserved control user atomically. */
    @Transactional
    public void delete(int id, String owner) {
        if (!userMapper.isAccountDeletionReservationOwner(id, owner)) {
            throw new ConflictException("Account deletion was not reserved");
        }
        User before = guard(id);
        managedObjectService.deleteUserImageAfterCommit(
            id, before.getProfilePictureUrl());
        auditService.record(
            "user.delete",
            "user",
            id,
            before.getUsername(),
            "Deleted user " + before.getUsername(),
            auditService.diff(before, null, AUDIT_FIELDS));
        userMapper.delete(id);
    }

    private User guard(int id) {
        if (userMapper.lockById(id) == null) {
            throw new ResourceNotFoundException("User not found with id: " + id);
        }
        var ownedWorkspaceIds =
            workspaceService.discoverOwnedWorkspaceIds(id);
        workspaceService.lockAccountWorkspaceRoots(
            ownedWorkspaceIds, java.util.List.of());
        workspaceService.assertNotSoleOwnerOfWorkspaces(ownedWorkspaceIds);
        orgMemberService.assertNotSoleOwnerOfAnyOrg(id);
        User before = userMapper.getUserById(id);
        if (before == null) {
            throw new ResourceNotFoundException("User not found with id: " + id);
        }
        return before;
    }
}
