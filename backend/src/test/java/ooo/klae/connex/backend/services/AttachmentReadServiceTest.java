package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import ooo.klae.connex.backend.beans.Attachment;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.dto.UserDisplayNameDto;
import ooo.klae.connex.backend.mappers.AttachmentMapper;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.tenant.TenantWorkScope;

/** Verifies attachment labels are hydrated only from tenant-derived user ids. */
@ExtendWith(MockitoExtension.class)
class AttachmentReadServiceTest {
    @Mock private AttachmentMapper attachmentMapper;
    @Mock private UserMapper userMapper;
    @Mock private TenantWorkScope tenantWorkScope;
    @Mock private AuthService authService;

    private AttachmentReadService service;

    @BeforeEach
    void setUp() {
        service = new AttachmentReadService(
            attachmentMapper, userMapper, tenantWorkScope, authService);
    }

    @AfterEach
    void clearTransactionState() {
        TransactionSynchronizationManager.clear();
    }

    @Test
    void getAllHydratesUploaderAndUserTargetLabelsWithoutDroppingRows() {
        allowUnroutedWork();
        Attachment first = attachment(1, 5, "company", 41, 7, "Local Company");
        Attachment second = attachment(2, 5, "user", 9, 8, null);
        Attachment third = attachment(3, 5, "user", 8, 7, null);
        List<Attachment> attachments = List.of(first, second, third);
        when(attachmentMapper.getAll(5)).thenReturn(attachments);
        when(userMapper.getDisplayNamesByIds(List.of(7, 8))).thenReturn(List.of(
            new UserDisplayNameDto(7, "User Seven")));
        when(userMapper.getActiveWorkspaceMemberDisplayNamesByIds(5, List.of(8, 9)))
            .thenReturn(List.of(new UserDisplayNameDto(9, "User Nine")));

        List<Attachment> result = service.getAll(5);

        assertSame(attachments, result);
        assertEquals(List.of(1, 2, 3), result.stream().map(Attachment::getId).toList());
        assertEquals("User Seven", first.getUploadedBy().getDisplayName());
        assertEquals("Local Company", first.getEntityLabel());
        assertNull(second.getUploadedBy().getDisplayName());
        assertEquals("User Nine", second.getEntityLabel());
        assertEquals("User Seven", third.getUploadedBy().getDisplayName());
        assertNull(third.getEntityLabel());
        verify(tenantWorkScope, times(2)).unrouted(any());
    }

    @Test
    void hydrationBatchesLargeTenantDerivedIdSets() {
        allowUnroutedWork();
        List<Attachment> attachments = new ArrayList<>();
        for (int id = 1; id <= 1_001; id++) {
            attachments.add(attachment(id, 5, "company", id, id, null));
        }
        when(attachmentMapper.getAll(5)).thenReturn(attachments);
        when(userMapper.getDisplayNamesByIds(any())).thenReturn(List.of());

        service.getAll(5);

        ArgumentCaptor<List<Integer>> batches = ArgumentCaptor.captor();
        verify(userMapper, times(2)).getDisplayNamesByIds(batches.capture());
        assertEquals(1_000, batches.getAllValues().get(0).size());
        assertEquals(List.of(1_001), batches.getAllValues().get(1));
    }

    @Test
    void hydrationRejectsRowsOutsideTheRequestedWorkspaceBeforeControlLookup() {
        when(attachmentMapper.getAll(5)).thenReturn(List.of(
            attachment(1, 6, "company", 41, 7, null)));

        assertThrows(IllegalStateException.class, () -> service.getAll(5));

        verify(tenantWorkScope, never()).unrouted(any());
        verify(userMapper, never()).getDisplayNamesByIds(any());
    }

    @Test
    void hydrationRejectsControlResultsOutsideTheirCandidateBatch() {
        allowUnroutedWork();
        when(attachmentMapper.getAll(5)).thenReturn(List.of(
            attachment(1, 5, "company", 41, 7, null)));
        when(userMapper.getDisplayNamesByIds(List.of(7)))
            .thenReturn(List.of(new UserDisplayNameDto(8, "Escaped")));

        assertThrows(IllegalStateException.class, () -> service.getAll(5));
    }

    @Test
    void activeTenantTransactionRejectsLabelsForOtherUsers() {
        User principal = user(7, "Current User");
        Attachment currentUploader = attachment(1, 5, "company", 41, 7, null);
        Attachment otherUploader = attachment(2, 5, "company", 42, 8, null);
        Attachment currentTarget = attachment(3, 5, "user", 7, 8, null);
        Attachment otherTarget = attachment(4, 5, "user", 8, 7, null);
        when(attachmentMapper.getAll(5)).thenReturn(List.of(
            currentUploader, otherUploader, currentTarget, otherTarget));
        when(authService.getCurrentPrincipal()).thenReturn(principal);
        TransactionSynchronizationManager.setActualTransactionActive(true);

        assertThrows(IllegalStateException.class, () -> service.getAll(5));

        verify(tenantWorkScope, never()).unrouted(any());
        verify(userMapper, never()).getDisplayNamesByIds(any());
    }

    @Test
    void activeTenantTransactionHydratesOnlyCurrentPrincipalReferences() {
        User principal = user(7, "Current User");
        Attachment currentUploader = attachment(1, 5, "company", 41, 7, null);
        Attachment currentTarget = attachment(2, 5, "user", 7, 7, null);
        when(attachmentMapper.getAll(5)).thenReturn(List.of(currentUploader, currentTarget));
        when(authService.getCurrentPrincipal()).thenReturn(principal);
        TransactionSynchronizationManager.setActualTransactionActive(true);

        service.getAll(5);

        assertEquals("Current User", currentUploader.getUploadedBy().getDisplayName());
        assertEquals("Current User", currentTarget.getUploadedBy().getDisplayName());
        assertEquals("Current User", currentTarget.getEntityLabel());
        verify(tenantWorkScope, never()).unrouted(any());
    }

    @Test
    void activeMemberTargetLookupIsWorkspaceConstrainedAndContained() {
        allowUnroutedWork();
        when(userMapper.getActiveWorkspaceMemberDisplayNamesByIds(5, List.of(9)))
            .thenReturn(List.of(new UserDisplayNameDto(9, "Member Nine")));

        UserDisplayNameDto label = service.getActiveWorkspaceMemberLabel(5, 9);

        assertEquals(new UserDisplayNameDto(9, "Member Nine"), label);
    }

    @Test
    void activeMemberTargetLookupRejectsAnExistingTenantTransaction() {
        TransactionSynchronizationManager.setActualTransactionActive(true);

        assertThrows(IllegalStateException.class,
            () -> service.getActiveWorkspaceMemberLabel(5, 9));

        verify(tenantWorkScope, never()).unrouted(any());
        verify(userMapper, never())
            .getActiveWorkspaceMemberDisplayNamesByIds(5, List.of(9));
    }

    @Test
    void knownMutationLabelsRequireNoPostWriteLookup() {
        Attachment attachment = attachment(1, 5, "user", 9, 7, null);
        User uploader = user(7, "Uploader Seven");

        Attachment hydrated = service.hydrateKnown(
            5, attachment, uploader, new UserDisplayNameDto(9, "Member Nine"));

        assertSame(attachment, hydrated);
        assertEquals("Uploader Seven", attachment.getUploadedBy().getDisplayName());
        assertEquals("Member Nine", attachment.getEntityLabel());
        verify(tenantWorkScope, never()).unrouted(any());
        verify(userMapper, never()).getDisplayNamesByIds(any());
    }

    private void allowUnroutedWork() {
        when(tenantWorkScope.unrouted(any())).thenAnswer(invocation -> {
            Supplier<?> work = invocation.getArgument(0);
            return work.get();
        });
    }

    private static Attachment attachment(
            int id, int workspaceId, String entityType, int entityId,
            int uploaderId, String entityLabel) {
        Attachment attachment = new Attachment();
        attachment.setId(id);
        attachment.setWorkspaceId(workspaceId);
        attachment.setEntityType(entityType);
        attachment.setEntityId(entityId);
        attachment.setEntityLabel(entityLabel);
        attachment.setUploadedBy(user(uploaderId, null));
        return attachment;
    }

    private static User user(int id, String displayName) {
        User user = new User();
        user.setId(id);
        user.setDisplayName(displayName);
        return user;
    }
}
