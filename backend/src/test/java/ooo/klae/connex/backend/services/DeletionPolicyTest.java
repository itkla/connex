package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.services.WorkspaceService.Role;

@ExtendWith(MockitoExtension.class)
class DeletionPolicyTest {
    private static final int CURRENT_USER_ID = 11;

    @Mock private WorkspaceService workspaceService;

    private DeletionPolicy deletionPolicy;

    @BeforeEach
    void setUp() {
        deletionPolicy = new DeletionPolicy(workspaceService);
    }

    @Test
    void creatorMemberIsAllowed() {
        currentUserIs(CURRENT_USER_ID);

        assertDoesNotThrow(() -> deletionPolicy.requireDeletable(CURRENT_USER_ID));

        verify(workspaceService, never()).requireRole(Role.ADMIN);
    }

    @Test
    void nonCreatorMemberIsDenied() {
        currentUserIs(CURRENT_USER_ID);
        denyAdminRole();

        assertThrows(ForbiddenException.class, () -> deletionPolicy.requireDeletable(22));
    }

    @Test
    void nonCreatorAdminIsAllowed() {
        currentUserIs(CURRENT_USER_ID);

        assertDoesNotThrow(() -> deletionPolicy.requireDeletable(22));

        verify(workspaceService).requireRole(Role.ADMIN);
    }

    @Test
    void nonCreatorOwnerIsAllowed() {
        currentUserIs(CURRENT_USER_ID);

        assertDoesNotThrow(() -> deletionPolicy.requireDeletable(22));

        verify(workspaceService).requireRole(Role.ADMIN);
    }

    @Test
    void nullCreatorDeniesMember() {
        denyAdminRole();

        assertThrows(ForbiddenException.class, () -> deletionPolicy.requireDeletable(null));
    }

    @Test
    void nullCreatorAllowsAdminOrOwner() {
        assertDoesNotThrow(() -> deletionPolicy.requireDeletable(null));

        verify(workspaceService).requireRole(Role.ADMIN);
    }

    @Test
    void nonCreatorWithNullOrAbsentMembershipRoleIsDenied() {
        currentUserIs(CURRENT_USER_ID);
        denyAdminRole();

        assertThrows(ForbiddenException.class, () -> deletionPolicy.requireDeletable(22));
    }

    private void currentUserIs(int userId) {
        when(workspaceService.getCurrentUserId()).thenReturn(userId);
    }

    private void denyAdminRole() {
        doThrow(new ForbiddenException("Requires ADMIN role in this workspace"))
                .when(workspaceService).requireRole(Role.ADMIN);
    }
}
