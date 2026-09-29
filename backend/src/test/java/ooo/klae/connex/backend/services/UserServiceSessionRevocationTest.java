package ooo.klae.connex.backend.services;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.connectedaccounts.ProviderAccountOffboardingService;
import ooo.klae.connex.backend.mappers.ActivityMapper;
import ooo.klae.connex.backend.mappers.NoteMapper;
import ooo.klae.connex.backend.mappers.TaskMapper;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.notifications.NotificationChangePublisher;
import ooo.klae.connex.backend.storage.ManagedObjectService;
import ooo.klae.connex.backend.tenant.TenantWorkScope;

class UserServiceSessionRevocationTest {

    private static final int WORKSPACE_ID = 7;
    private static final int USER_ID = 9;

    private final UserMapper userMapper = mock(UserMapper.class);
    private final WorkspaceService workspaceService = mock(WorkspaceService.class);
    private final AccountSessionRevocationService accountSessionRevocationService =
        mock(AccountSessionRevocationService.class);
    private final UserService userService = new UserService(
        userMapper,
        mock(ActivityMapper.class),
        mock(NoteMapper.class),
        mock(TaskMapper.class),
        mock(AuditService.class),
        workspaceService,
        mock(NotificationChangePublisher.class),
        mock(ReferenceService.class),
        mock(ManagedObjectService.class),
        mock(UserProfilePictureTransaction.class),
        mock(TenantWorkScope.class),
        mock(ProviderAccountOffboardingService.class),
        mock(UserAccountCatalogOffboardingService.class),
        mock(UserDeletionTransaction.class),
        accountSessionRevocationService);

    @Test
    void aProfileUpdateKeepsHttpSessionsAndClosesSocketsOnlyOnRename() {
        User member = user("user_member", "Member");
        User profileUpdated = user(member.getUsername(), "My New Name");
        User renamed = user(member.getUsername() + "renamed", member.getDisplayName());
        when(workspaceService.getCurrentWorkspaceId()).thenReturn(WORKSPACE_ID);
        when(workspaceService.isMember(WORKSPACE_ID, USER_ID)).thenReturn(true);
        when(userMapper.getUserById(USER_ID)).thenReturn(member, profileUpdated, profileUpdated, renamed);

        userService.update(USER_ID, profileUpdate(member, "My New Name"));
        verify(accountSessionRevocationService, never()).closeWebSocketsAfterRename(anyInt());

        User rename = profileUpdate(member, member.getDisplayName());
        rename.setUsername(member.getUsername() + "renamed");
        userService.update(USER_ID, rename);

        verify(accountSessionRevocationService).closeWebSocketsAfterRename(USER_ID);
        verify(accountSessionRevocationService, never()).expireAll(anyInt());
    }

    private User user(String username, String displayName) {
        User user = new User();
        user.setId(USER_ID);
        user.setUsername(username);
        user.setDisplayName(displayName);
        user.setEmail("member@example.com");
        user.setTimezone("UTC");
        return user;
    }

    private User profileUpdate(User base, String newDisplayName) {
        User update = new User();
        update.setUsername(base.getUsername());
        update.setEmail(base.getEmail());
        update.setDisplayName(newDisplayName);
        update.setTimezone(base.getTimezone());
        return update;
    }
}
