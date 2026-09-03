package ooo.klae.connex.backend.services;

import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import ooo.klae.connex.backend.beans.DisqualificationReason;
import ooo.klae.connex.backend.beans.PersonDisqualificationReason;
import ooo.klae.connex.backend.dto.DisqualificationReasonDto;
import ooo.klae.connex.backend.dto.DisqualificationReasonRequest;
import ooo.klae.connex.backend.mappers.DisqualificationReasonMapper;

/** Lock-order proof for the workspace materialization mutex and post-lock authorization. */
class DisqualificationReasonServiceLockOrderTest {
    @Test
    void lifecycleSynthesizesBuiltInsWithoutMaterializingAnUntouchedWorkspace() {
        Fixture fixture = fixture();
        when(fixture.mapper.getAll(7)).thenReturn(List.of());

        DisqualificationReasonDto resolved = fixture.service.lockForLifecycle(
            7, PersonDisqualificationReason.OTHER);

        org.junit.jupiter.api.Assertions.assertEquals(
            PersonDisqualificationReason.OTHER, resolved.code());
        org.junit.jupiter.api.Assertions.assertTrue(resolved.requiresNote());
        verify(fixture.workspaceService)
            .lockAndRequirePermissionsWithWorkspaceMutex(anyInt(), anyMap());
        verify(fixture.mapper, never())
            .insertBuiltIn(anyInt(), anyString(), anyBoolean(), anyInt());
    }

    @Test
    void lifecycleUsesTheExactPersistedReasonLockWhenTheWorkspaceHasRows() {
        Fixture fixture = fixture();
        DisqualificationReason stored = reason(8, "CUSTOM", "Custom", true, 1, false);
        when(fixture.mapper.getByCodeForUpdate(7, "CUSTOM")).thenReturn(stored);

        DisqualificationReasonDto resolved = fixture.service.lockForLifecycle(7, "CUSTOM");

        org.junit.jupiter.api.Assertions.assertEquals("Custom", resolved.label());
        InOrder order = inOrder(fixture.workspaceService, fixture.mapper);
        order.verify(fixture.workspaceService)
            .lockAndRequirePermissionsWithWorkspaceMutex(anyInt(), anyMap());
        order.verify(fixture.mapper).getByCodeForUpdate(7, "CUSTOM");
        verify(fixture.mapper, never()).getAll(7);
    }

    @Test
    void lifecycleRejectsACollationMatchWhoseStoredCodeIsNotCanonical() {
        Fixture fixture = fixture();
        DisqualificationReason stored = reason(8, "other", "Wrong match", false, 1, false);
        when(fixture.mapper.getByCodeForUpdate(7, PersonDisqualificationReason.OTHER))
            .thenReturn(stored);

        DisqualificationReasonDto resolved = fixture.service.lockForLifecycle(
            7, PersonDisqualificationReason.OTHER);

        org.junit.jupiter.api.Assertions.assertNull(resolved);
    }

    @Test
    void updateLocksAuthorizationOnceBeforeTheReasonAndRevalidatesTheSnapshotBeforeWriting() {
        Fixture fixture = fixture();
        DisqualificationReason stored = reason(8, "NO_FIT", null, false, 1, true);
        when(fixture.mapper.getByIdForUpdate(7, 8)).thenReturn(stored);
        when(fixture.mapper.getById(7, 8)).thenReturn(stored);

        fixture.service.update(8, request("NO_FIT", "Not suitable", true, 2));

        InOrder order = inOrder(
            fixture.workspaceService, fixture.mapper, fixture.authorization);
        order.verify(fixture.workspaceService)
            .lockAndRequirePermissionsWithWorkspaceMutex(anyInt(), anyMap());
        order.verify(fixture.mapper, times(9))
            .insertBuiltIn(anyInt(), anyString(), anyBoolean(), anyInt());
        order.verify(fixture.mapper).getByIdForUpdate(7, 8);
        order.verify(fixture.authorization).revalidate();
        order.verify(fixture.mapper).update(org.mockito.ArgumentMatchers.any());
        verify(fixture.workspaceService, times(1))
            .lockAndRequirePermissionsWithWorkspaceMutex(anyInt(), anyMap());
    }

    @Test
    void createLocksAuthorizationOnceBeforeTheReasonAndRevalidatesTheSnapshotBeforeInsert() {
        Fixture fixture = fixture();

        fixture.service.create(request("CUSTOM", "Custom", false, 10));

        InOrder order = inOrder(
            fixture.workspaceService, fixture.mapper, fixture.authorization);
        order.verify(fixture.workspaceService)
            .lockAndRequirePermissionsWithWorkspaceMutex(anyInt(), anyMap());
        order.verify(fixture.mapper, times(9))
            .insertBuiltIn(anyInt(), anyString(), anyBoolean(), anyInt());
        order.verify(fixture.mapper).getByCodeForUpdate(7, "CUSTOM");
        order.verify(fixture.authorization).revalidate();
        order.verify(fixture.mapper).insert(org.mockito.ArgumentMatchers.any());
        verify(fixture.workspaceService, times(1))
            .lockAndRequirePermissionsWithWorkspaceMutex(anyInt(), anyMap());
    }

    @Test
    void archiveLocksAuthorizationOnceBeforeTheReasonAndRevalidatesTheSnapshotBeforeWriting() {
        Fixture fixture = fixture();
        DisqualificationReason stored = reason(8, "CUSTOM", "Custom", false, 1, false);
        when(fixture.mapper.getByIdForUpdate(7, 8)).thenReturn(stored);
        when(fixture.mapper.archive(7, 8)).thenReturn(1);

        fixture.service.archive(8);

        InOrder order = inOrder(
            fixture.workspaceService, fixture.mapper, fixture.authorization);
        order.verify(fixture.workspaceService)
            .lockAndRequirePermissionsWithWorkspaceMutex(anyInt(), anyMap());
        order.verify(fixture.mapper, times(9))
            .insertBuiltIn(anyInt(), anyString(), anyBoolean(), anyInt());
        order.verify(fixture.mapper).getByIdForUpdate(7, 8);
        order.verify(fixture.authorization).revalidate();
        order.verify(fixture.mapper).archive(7, 8);
        verify(fixture.workspaceService, times(1))
            .lockAndRequirePermissionsWithWorkspaceMutex(anyInt(), anyMap());
    }

    @Test
    void restoreLocksAuthorizationOnceBeforeTheReasonAndRevalidatesTheSnapshotBeforeWriting() {
        Fixture fixture = fixture();
        DisqualificationReason stored = reason(8, "CUSTOM", "Custom", false, 1, false);
        stored.setArchivedAt(LocalDateTime.of(2026, 1, 2, 3, 4));
        when(fixture.mapper.getByIdForUpdate(7, 8)).thenReturn(stored);
        when(fixture.mapper.restore(7, 8)).thenReturn(1);

        fixture.service.restore(8);

        InOrder order = inOrder(
            fixture.workspaceService, fixture.mapper, fixture.authorization);
        order.verify(fixture.workspaceService)
            .lockAndRequirePermissionsWithWorkspaceMutex(anyInt(), anyMap());
        order.verify(fixture.mapper, times(9))
            .insertBuiltIn(anyInt(), anyString(), anyBoolean(), anyInt());
        order.verify(fixture.mapper).getByIdForUpdate(7, 8);
        order.verify(fixture.authorization).revalidate();
        order.verify(fixture.mapper).restore(7, 8);
        verify(fixture.workspaceService, times(1))
            .lockAndRequirePermissionsWithWorkspaceMutex(anyInt(), anyMap());
    }

    private static Fixture fixture() {
        DisqualificationReasonMapper mapper = mock(DisqualificationReasonMapper.class);
        WorkspaceService workspaceService = mock(WorkspaceService.class);
        AuditService auditService = mock(AuditService.class);
        WorkspaceService.LockedPermissionSnapshot authorization =
            mock(WorkspaceService.LockedPermissionSnapshot.class);
        when(workspaceService.getCurrentWorkspaceId()).thenReturn(7);
        when(workspaceService.getCurrentUserId()).thenReturn(11);
        when(workspaceService.lockAndRequirePermissionsWithWorkspaceMutex(anyInt(), anyMap()))
            .thenReturn(authorization);
        return new Fixture(
            mapper,
            workspaceService,
            authorization,
            new DisqualificationReasonService(mapper, workspaceService, auditService));
    }

    private static DisqualificationReason reason(
            int id,
            String code,
            String label,
            boolean requiresNote,
            int position,
            boolean builtIn) {
        DisqualificationReason reason = new DisqualificationReason();
        reason.setId(id);
        reason.setWorkspaceId(7);
        reason.setCode(code);
        reason.setLabel(label);
        reason.setRequiresNote(requiresNote);
        reason.setPosition(position);
        reason.setBuiltIn(builtIn);
        return reason;
    }

    private static DisqualificationReasonRequest request(
            String code,
            String label,
            boolean requiresNote,
            int position) {
        DisqualificationReasonRequest request = new DisqualificationReasonRequest();
        request.setCode(code);
        request.setLabel(label);
        request.setRequiresNote(requiresNote);
        request.setPosition(position);
        return request;
    }

    private record Fixture(
        DisqualificationReasonMapper mapper,
        WorkspaceService workspaceService,
        WorkspaceService.LockedPermissionSnapshot authorization,
        DisqualificationReasonService service
    ) {
    }
}
