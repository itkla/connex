package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.storage.ManagedObjectService;
import ooo.klae.connex.backend.storage.ManagedObjectService.StoredImage;
import ooo.klae.connex.backend.storage.UploadSource;

@ExtendWith(MockitoExtension.class)
class UserProfilePictureTransactionTest {
    @Mock UserMapper userMapper;
    @Mock ManagedObjectService managedObjectService;

    @Test
    void commitsMetadataAndOldImageDeletionIntentTogether() {
        User before = user(8, "/api/users/8/profile-picture/old.png");
        User after = user(8, "/api/users/8/profile-picture/new.png");
        UploadSource source = UploadSource.from(
            "portrait.png", "image/png", new byte[] {1, 2, 3});
        when(userMapper.lockById(8)).thenReturn(8);
        when(userMapper.getUserById(8)).thenReturn(before, after);
        when(managedObjectService.storeUserImage(8, source))
            .thenReturn(new StoredImage(after.getProfilePictureUrl(), 3, "image/png"));
        when(userMapper.updateProfilePictureUrlIfCurrent(
            8, before.getProfilePictureUrl(), after.getProfilePictureUrl())).thenReturn(1);
        UserProfilePictureTransaction transaction = new UserProfilePictureTransaction(
            userMapper, managedObjectService);

        UserProfilePictureTransaction.Result result = transaction.update(8, source);

        assertSame(before, result.before());
        assertSame(after, result.after());
        verify(managedObjectService).storeUserImage(8, source);
        verify(managedObjectService).deleteUserImageAfterCommit(
            8, before.getProfilePictureUrl());
    }

    private static User user(int id, String profilePictureUrl) {
        User user = new User();
        user.setId(id);
        user.setProfilePictureUrl(profilePictureUrl);
        return user;
    }
}
