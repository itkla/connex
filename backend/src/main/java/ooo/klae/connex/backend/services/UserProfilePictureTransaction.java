package ooo.klae.connex.backend.services;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.exceptions.ConflictException;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.storage.ManagedObjectService;
import ooo.klae.connex.backend.storage.ManagedObjectService.StoredImage;
import ooo.klae.connex.backend.storage.UploadSource;

/**
 * Commits control-plane user-image metadata and its deletion intent in one transaction.
 */
@Service
@RequiredArgsConstructor
public class UserProfilePictureTransaction {
    private final UserMapper userMapper;
    private final ManagedObjectService managedObjectService;

    @Transactional
    public Result update(int userId, UploadSource source) {
        if (userMapper.lockById(userId) == null) {
            throw new ResourceNotFoundException("User not found with id: " + userId);
        }
        User before = userMapper.getUserById(userId);
        if (before == null) {
            throw new ResourceNotFoundException("User not found with id: " + userId);
        }
        StoredImage stored = managedObjectService.storeUserImage(userId, source);
        int updated = userMapper.updateProfilePictureUrlIfCurrent(
            userId, before.getProfilePictureUrl(), stored.url());
        if (updated != 1) {
            throw new ConflictException("Profile picture changed while the image was uploading; retry");
        }
        managedObjectService.deleteUserImageAfterCommit(userId, before.getProfilePictureUrl());
        User after = userMapper.getUserById(userId);
        if (after == null) {
            throw new ResourceNotFoundException("User not found with id: " + userId);
        }
        return new Result(before, after);
    }

    public record Result(User before, User after) {}
}
