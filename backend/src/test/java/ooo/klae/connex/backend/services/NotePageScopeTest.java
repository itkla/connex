package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.stream.IntStream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import tools.jackson.databind.ObjectMapper;

import ooo.klae.connex.backend.beans.Note;
import ooo.klae.connex.backend.mappers.DealMapper;
import ooo.klae.connex.backend.mappers.NoteMapper;
import ooo.klae.connex.backend.mappers.PersonMapper;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.notifications.NotificationDelivery;
import ooo.klae.connex.backend.services.RecordVisibilityScopeControlAccess.RecordVisibilityScope;

/** A page and count share one control scope and the original, uncapped author-ID list. */
class NotePageScopeTest {
    private final NoteMapper notes = mock(NoteMapper.class);
    private final UserMapper users = mock(UserMapper.class);
    private final WorkspaceService workspace = mock(WorkspaceService.class);
    private final ReferenceService references = mock(ReferenceService.class);
    private final RecordVisibilityScopeControlAccess scopes = ooo.klae.connex.backend.support.RecordVisibilityScopes.unfiltered();
    private final NoteService service = new NoteService(notes, users, mock(DealMapper.class), mock(PersonMapper.class),
        mock(AuditService.class), workspace, mock(AuthService.class), references, mock(NotificationDelivery.class),
        mock(NotificationPreferenceService.class), mock(ObjectMapper.class), scopes);

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void pageAndCountResolveOneScopeAndOneCompleteAuthorSnapshot(boolean workspaceOnly) {
        authorize();
        List<Integer> authors = IntStream.rangeClosed(1, 501).boxed().toList();
        List<Note> rows = List.of(new Note());
        List<Note> hydrated = List.of(new Note());
        when(users.findMatchingWorkspaceMemberIds(7, "%needle%")).thenReturn(authors);
        when(references.hydrate(7, rows)).thenReturn(hydrated);
        if (workspaceOnly) {
            when(notes.getWorkspaceNotesPage(7, "[7,8]", "%needle%", authors, "title", "asc", 25, 50)).thenReturn(rows);
            when(notes.countWorkspaceNotes(7, "[7,8]", "%needle%", authors)).thenReturn(73L);
        } else {
            when(notes.getVisibleNotesPage(7, "[7,8]", 11, "%needle%", authors, "title", "asc", 25, 50)).thenReturn(rows);
            when(notes.countVisibleNotes(7, "[7,8]", 11, "%needle%", authors)).thenReturn(73L);
        }

        var page = service.getNotesPage("%needle%", "title", null, 25, 50, workspaceOnly);

        assertSame(hydrated, page.items());
        assertEquals(73, page.total());
        var order = inOrder(scopes, users, notes);
        order.verify(scopes).withWorkspaceScopeRead(eq(7), org.mockito.ArgumentMatchers.any());
        order.verify(scopes).getForWorkspace(7);
        order.verify(users).findMatchingWorkspaceMemberIds(7, "%needle%");
        if (workspaceOnly) {
            order.verify(notes).getWorkspaceNotesPage(7, "[7,8]", "%needle%", authors, "title", "asc", 25, 50);
            order.verify(notes).countWorkspaceNotes(7, "[7,8]", "%needle%", authors);
        } else {
            order.verify(notes).getVisibleNotesPage(7, "[7,8]", 11, "%needle%", authors, "title", "asc", 25, 50);
            order.verify(notes).countVisibleNotes(7, "[7,8]", 11, "%needle%", authors);
        }
        verifyNoMoreInteractions(scopes, users, notes);
        verify(references).hydrate(7, rows);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void unfilteredPagesSkipAuthorLookupAndKeepDefaultOrdering(boolean workspaceOnly) {
        authorize();
        when(references.hydrate(eq(7), anyList())).thenAnswer(invocation -> invocation.getArgument(1));

        var page = service.getNotesPage(null, "unknown", "unknown", 10, 0, workspaceOnly);

        assertEquals(0, page.total());
        verify(scopes).withWorkspaceScopeRead(eq(7), org.mockito.ArgumentMatchers.any());
        verify(scopes).getForWorkspace(7);
        verifyNoMoreInteractions(scopes);
        verifyNoInteractions(users);
        if (workspaceOnly) {
            verify(notes).getWorkspaceNotesPage(7, "[7,8]", null, List.of(), "updated", "desc", 10, 0);
            verify(notes).countWorkspaceNotes(7, "[7,8]", null, List.of());
        } else {
            verify(notes).getVisibleNotesPage(7, "[7,8]", 11, null, List.of(), "updated", "desc", 10, 0);
            verify(notes).countVisibleNotes(7, "[7,8]", 11, null, List.of());
        }
        verifyNoMoreInteractions(notes);
    }

    private void authorize() {
        when(workspace.getCurrentWorkspaceId()).thenReturn(7);
        when(workspace.getCurrentUserId()).thenReturn(11);
        when(scopes.getForWorkspace(7)).thenReturn(new RecordVisibilityScope(900, List.of(7, 8)));
    }
}
