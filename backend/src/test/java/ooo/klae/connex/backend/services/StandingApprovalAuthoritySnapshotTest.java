package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.tenant.Permission;

class StandingApprovalAuthoritySnapshotTest {
    @Test
    void capturedAuthorityRetainsEveryHistoryBindingWithoutFurtherReads() {
        var epochs = new WorkspaceService.AuthorityEpochs(31, 7, "member", 12, 9L,
                WorkspaceService.BUILT_IN_PERMISSION_POLICY_VERSION);
        var snapshot = new WorkspaceService.LockedPermissionSnapshot(
                Map.of(3, Set.of(Permission.AI_USE)), Map.of(3, Set.of(Permission.AI_USE)), Map.of(3, epochs));
        assertSame(epochs, snapshot.authorityFor(3));
        assertEquals(31, snapshot.authorityFor(3).membershipId());
        assertEquals(7, snapshot.authorityFor(3).authorizationEpoch());
        assertEquals(9L, snapshot.authorityFor(3).permissionEpoch());
        snapshot.revalidate();
        var restored = new WorkspaceService.AuthorityEpochs(31, 9, "member", 12, 11L, 1);
        assertNotEquals(epochs, restored);
        assertNotEquals(epochs, new WorkspaceService.AuthorityEpochs(32, 7, "member", 12, 9L, 1));
    }

    @Test
    void missingMetadataOrUnlockedPermissionsRefuseStandingAuthority() {
        var snapshot = new WorkspaceService.LockedPermissionSnapshot(
                Map.of(3, Set.of(Permission.AI_USE)), Map.of(3, Set.of(Permission.AI_USE)));
        assertThrows(ForbiddenException.class, () -> snapshot.authorityFor(3));
        assertThrows(IllegalArgumentException.class, () -> snapshot.authorityFor(4));
    }
}
