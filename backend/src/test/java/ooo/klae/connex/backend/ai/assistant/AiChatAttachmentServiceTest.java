package ooo.klae.connex.backend.ai.assistant;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import ooo.klae.connex.backend.beans.AiChatSession;
import ooo.klae.connex.backend.beans.Attachment;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.dto.AiChatStepFrameDto;
import ooo.klae.connex.backend.exceptions.ConflictException;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.mappers.AiChatMapper;
import ooo.klae.connex.backend.mappers.AttachmentMapper;
import ooo.klae.connex.backend.notifications.AiChatRealtimeDispatcher;
import ooo.klae.connex.backend.services.AttachmentWriteOperations;
import ooo.klae.connex.backend.services.AuditService;
import ooo.klae.connex.backend.services.AuthService;
import ooo.klae.connex.backend.services.WorkspaceService;
import ooo.klae.connex.backend.storage.ManagedObjectService;
import ooo.klae.connex.backend.storage.UploadSource;

class AiChatAttachmentServiceTest {
    private static final int WORKSPACE_ID = 7;
    private static final int USER_ID = 11;
    private static final int SESSION_ID = 13;

    private AiChatMapper chatMapper;
    private AttachmentMapper attachmentMapper;
    private AttachmentWriteOperations writeOperations;
    private AiChatAttachmentPolicy attachmentPolicy;
    private WorkspaceService workspaceService;
    private AuthService authService;
    private AiChatRealtimeDispatcher realtimeDispatcher;
    private AiChatAttachmentService service;

    @BeforeEach
    void setUp() {
        chatMapper = mock(AiChatMapper.class);
        attachmentMapper = mock(AttachmentMapper.class);
        writeOperations = mock(AttachmentWriteOperations.class);
        attachmentPolicy = mock(AiChatAttachmentPolicy.class);
        workspaceService = mock(WorkspaceService.class);
        authService = mock(AuthService.class);
        realtimeDispatcher = mock(AiChatRealtimeDispatcher.class);
        service = new AiChatAttachmentService(
                chatMapper,
                attachmentMapper,
                writeOperations,
                attachmentPolicy,
                mock(ManagedObjectService.class),
                workspaceService,
                authService,
                mock(AuditService.class),
                realtimeDispatcher);
        when(workspaceService.getCurrentWorkspaceId()).thenReturn(WORKSPACE_ID);
        when(workspaceService.getCurrentUserId()).thenReturn(USER_ID);
    }

    @Test
    void anotherWorkspaceCannotReadSessionAttachments() {
        when(workspaceService.getCurrentWorkspaceId()).thenReturn(99);

        assertThrows(ResourceNotFoundException.class, () -> service.list(SESSION_ID));

        verify(chatMapper).getAccessibleSessionById(99, USER_ID, SESSION_ID);
        verify(attachmentMapper, never()).getAssistantSessionAttachments(anyInt(), anyInt());
    }

    @Test
    void uploadLimitIsEnforcedBeforeReadingTheFile() {
        AiChatSession session = activeSession();
        User actor = mock(User.class);
        when(actor.getId()).thenReturn(USER_ID);
        when(workspaceService.getMembers(WORKSPACE_ID)).thenReturn(List.of(actor));
        when(chatMapper.getSessionByIdForUpdate(WORKSPACE_ID, USER_ID, SESSION_ID))
                .thenReturn(session);
        when(attachmentMapper.countAssistantSessionAttachments(WORKSPACE_ID, SESSION_ID))
                .thenReturn(AiChatAttachmentPolicy.MAX_ATTACHMENTS);
        UploadSource source = UploadSource.from(
                "notes.txt", "text/plain", "content".getBytes(StandardCharsets.UTF_8));

        assertThrows(ConflictException.class, () -> service.upload(SESSION_ID, source));

        verify(attachmentPolicy, never()).prepare(source);
        verify(writeOperations, never()).uploadAssistantSession(
                anyInt(), anyInt(), any(), any());
    }

    @Test
    void uploadingAttachmentEmitsRealtimeInvalidationAfterCommit() {
        AiChatSession session = activeSession();
        User actor = mock(User.class);
        when(actor.getId()).thenReturn(USER_ID);
        when(workspaceService.getMembers(WORKSPACE_ID)).thenReturn(List.of(actor));
        when(chatMapper.getSessionByIdForUpdate(WORKSPACE_ID, USER_ID, SESSION_ID))
                .thenReturn(session);
        UploadSource source = UploadSource.from(
                "notes.txt", "text/plain", "content".getBytes(StandardCharsets.UTF_8));
        Attachment uploaded = new Attachment();
        uploaded.setId(31);
        uploaded.setWorkspaceId(WORKSPACE_ID);
        uploaded.setEntityType("ai_chat_session");
        uploaded.setEntityId(SESSION_ID);
        uploaded.setFileName("notes.txt");
        uploaded.setContentType("text/plain");
        uploaded.setSize(7L);
        when(attachmentPolicy.prepare(source)).thenReturn(source);
        when(authService.getCurrentUser()).thenReturn(actor);
        when(writeOperations.uploadAssistantSession(
                eq(WORKSPACE_ID), eq(SESSION_ID), eq(source), eq(actor)))
                .thenReturn(uploaded);

        service.upload(SESSION_ID, source);

        verify(realtimeDispatcher).sessionAfterCommit(
                WORKSPACE_ID,
                SESSION_ID,
                new AiChatStepFrameDto(
                        WORKSPACE_ID,
                        SESSION_ID,
                        0,
                        0,
                        "session",
                        null,
                        "attachments_changed",
                        null));
    }

    private static AiChatSession activeSession() {
        AiChatSession session = new AiChatSession();
        session.setId(SESSION_ID);
        session.setWorkspaceId(WORKSPACE_ID);
        session.setCreatedByUserId(USER_ID);
        session.setVisibility("private");
        session.setStatus("active");
        return session;
    }
}
