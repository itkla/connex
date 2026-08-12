package ooo.klae.connex.backend.ai.assistant;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import ooo.klae.connex.backend.beans.AiChatSession;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.exceptions.ConflictException;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.mappers.AiChatMapper;
import ooo.klae.connex.backend.mappers.AttachmentMapper;
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
    private AiChatAttachmentService service;

    @BeforeEach
    void setUp() {
        chatMapper = mock(AiChatMapper.class);
        attachmentMapper = mock(AttachmentMapper.class);
        writeOperations = mock(AttachmentWriteOperations.class);
        attachmentPolicy = mock(AiChatAttachmentPolicy.class);
        workspaceService = mock(WorkspaceService.class);
        service = new AiChatAttachmentService(
                chatMapper,
                attachmentMapper,
                writeOperations,
                attachmentPolicy,
                mock(ManagedObjectService.class),
                workspaceService,
                mock(AuthService.class),
                mock(AuditService.class));
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
