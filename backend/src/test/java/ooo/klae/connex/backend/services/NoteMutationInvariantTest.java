package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import tools.jackson.databind.ObjectMapper;

import ooo.klae.connex.backend.beans.Note;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.mappers.DealMapper;
import ooo.klae.connex.backend.mappers.NoteMapper;
import ooo.klae.connex.backend.mappers.PersonMapper;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.notifications.NotificationDelivery;
import ooo.klae.connex.backend.tenant.Permission;

/** Ensures a missed note write cannot produce successful mutation side effects. */
@ExtendWith(MockitoExtension.class)
class NoteMutationInvariantTest {
    @Mock private NoteMapper noteMapper;
    @Mock private UserMapper userMapper;
    @Mock private DealMapper dealMapper;
    @Mock private PersonMapper personMapper;
    @Mock private AuditService auditService;
    @Mock private WorkspaceService workspaceService;
    @Mock private AuthService authService;
    @Mock private ReferenceService referenceService;
    @Mock private NotificationDelivery notificationDelivery;
    @Mock private NotificationPreferenceService notificationPreferenceService;
    @Mock private ObjectMapper objectMapper;
    @Mock private WorkspaceService.LockedPermissionSnapshot authority;
    @InjectMocks private NoteService noteService;

    @Test
    void updateChecksAffectedRowsBeforeReferencesAndAudit() {
        User actor = new User();
        actor.setId(41);
        Note before = new Note();
        before.setAuthor(actor);
        before.setVisibility("workspace");
        Note update = new Note();
        update.setContent("Edited");
        when(workspaceService.getCurrentWorkspaceId()).thenReturn(17);
        when(authService.getCurrentUser()).thenReturn(actor);
        when(workspaceService.lockAndRequirePermissionsSnapshot(17, Map.of(41, Set.of(Permission.NOTE_UPDATE))))
            .thenReturn(authority);
        when(noteMapper.getVisibleNoteByIdForUpdate(17, 29, 41)).thenReturn(before);

        assertThrows(ResourceNotFoundException.class, () -> noteService.update(29, update));

        var order = inOrder(workspaceService, noteMapper, authority);
        order.verify(workspaceService).lockAndRequirePermissionsSnapshot(17, Map.of(41, Set.of(Permission.NOTE_UPDATE)));
        order.verify(noteMapper).getVisibleNoteByIdForUpdate(17, 29, 41);
        order.verify(authority).revalidate();
        order.verify(noteMapper).update(update);
        verifyNoInteractions(referenceService, auditService, notificationDelivery);
    }

    @Test
    void deleteChecksAffectedRowsBeforeReferencesAndAudit() {
        when(workspaceService.getCurrentWorkspaceId()).thenReturn(17);
        when(workspaceService.getCurrentUserId()).thenReturn(41);
        when(workspaceService.lockAndRequirePermissionsSnapshot(17, Map.of(41, Set.of(Permission.NOTE_DELETE))))
            .thenReturn(authority);
        when(noteMapper.getVisibleNoteByIdForUpdate(17, 29, 41)).thenReturn(new Note());

        assertThrows(ResourceNotFoundException.class, () -> noteService.delete(29));

        var order = inOrder(workspaceService, noteMapper, authority);
        order.verify(workspaceService).lockAndRequirePermissionsSnapshot(17, Map.of(41, Set.of(Permission.NOTE_DELETE)));
        order.verify(noteMapper).getVisibleNoteByIdForUpdate(17, 29, 41);
        order.verify(authority).revalidate();
        order.verify(noteMapper).delete(17, 29);
        verifyNoInteractions(referenceService, auditService, notificationDelivery);
    }
}
