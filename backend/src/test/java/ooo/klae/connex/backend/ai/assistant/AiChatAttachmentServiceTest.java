package ooo.klae.connex.backend.ai.assistant;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.EnumSet;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InOrder;

import ooo.klae.connex.backend.beans.AiChatSession;
import ooo.klae.connex.backend.beans.Attachment;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.dto.AiChatStepFrameDto;
import ooo.klae.connex.backend.exceptions.ConflictException;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.mappers.AiChatMapper;
import ooo.klae.connex.backend.mappers.AttachmentMapper;
import ooo.klae.connex.backend.mappers.AttachmentScanMapper;
import ooo.klae.connex.backend.notifications.AiChatRealtimeDispatcher;
import ooo.klae.connex.backend.services.AttachmentWriteOperations;
import ooo.klae.connex.backend.services.AuditService;
import ooo.klae.connex.backend.services.AuthService;
import ooo.klae.connex.backend.services.WorkspaceService;
import ooo.klae.connex.backend.storage.ManagedObjectService;
import ooo.klae.connex.backend.storage.ScannedUpload;
import ooo.klae.connex.backend.storage.UploadContentInspector.InspectedUpload;
import ooo.klae.connex.backend.storage.UploadMalwareScanner;
import ooo.klae.connex.backend.storage.UploadPolicy.UploadFormat;
import ooo.klae.connex.backend.storage.UploadSource;
import ooo.klae.connex.backend.tenant.Permission;

class AiChatAttachmentServiceTest {
    private static final int WORKSPACE_ID = 7;
    private static final int USER_ID = 11;
    private static final int SESSION_ID = 13;
    private static final int ATTACHMENT_ID = 31;
    private static final String MANAGED_URL =
            "/api/attachments/content/0f8fad5b-d9cb-469f-a165-70867728950e.txt";

    private AiChatMapper chatMapper;
    private AiAssistantPrivacyService organizationAuthority;
    private AiChatPrivacyGuard privacyGuard;
    private AttachmentMapper attachmentMapper;
    private AttachmentWriteOperations writeOperations;
    private AiChatAttachmentPolicy attachmentPolicy;
    private UploadMalwareScanner uploadMalwareScanner;
    private WorkspaceService workspaceService;
    private AuthService authService;
    private AiChatRealtimeDispatcher realtimeDispatcher;
    private AttachmentScanMapper scanMapper;
    private ManagedObjectService managedObjectService;
    private AuditService auditService;
    private AiChatAttachmentService service;

    @BeforeEach
    void setUp() {
        chatMapper = mock(AiChatMapper.class);
        organizationAuthority = mock(AiAssistantPrivacyService.class);
        when(organizationAuthority.organizationId(org.mockito.ArgumentMatchers.anyInt())).thenReturn(1);
        when(organizationAuthority.current(1)).thenReturn(1L);
        when(organizationAuthority.freshCurrent(1)).thenReturn(1L);
        privacyGuard = new AiChatPrivacyGuard(organizationAuthority);
        attachmentMapper = mock(AttachmentMapper.class);
        writeOperations = mock(AttachmentWriteOperations.class);
        attachmentPolicy = mock(AiChatAttachmentPolicy.class);
        uploadMalwareScanner = mock(UploadMalwareScanner.class);
        workspaceService = mock(WorkspaceService.class);
        authService = mock(AuthService.class);
        realtimeDispatcher = mock(AiChatRealtimeDispatcher.class);
        scanMapper = mock(AttachmentScanMapper.class);
        managedObjectService = mock(ManagedObjectService.class);
        auditService = mock(AuditService.class);
        service = new AiChatAttachmentService(
                privacyGuard,
                chatMapper,
                attachmentMapper,
                scanMapper,
                writeOperations,
                attachmentPolicy,
                uploadMalwareScanner,
                new AiChatAttachmentTransactions(),
                managedObjectService,
                workspaceService,
                authService,
                auditService,
                mock(AiAssistantSessionReadAudit.class),
                realtimeDispatcher);
        var barrier = new ooo.klae.connex.backend.beans.AiAgentPrivacyBarrier();
        barrier.setWorkspaceId(WORKSPACE_ID);
        barrier.setGeneration(1L);
        when(chatMapper.getPrivacyBarrier(WORKSPACE_ID)).thenReturn(barrier);
        when(chatMapper.lockPrivacyBarrierForShare(WORKSPACE_ID)).thenReturn(barrier);
        when(chatMapper.getPrivacySession(WORKSPACE_ID, SESSION_ID)).thenAnswer(invocation -> activeSession());
        when(workspaceService.getCurrentWorkspaceId()).thenReturn(WORKSPACE_ID);
        when(workspaceService.getCurrentUserId()).thenReturn(USER_ID);
        when(workspaceService.lockedPermissionsFor(WORKSPACE_ID, USER_ID))
                .thenReturn(EnumSet.of(Permission.AI_USE, Permission.ATTACHMENT_CREATE));
    }

    @Test
    void anotherWorkspaceCannotReadSessionAttachments() {
        when(workspaceService.getCurrentWorkspaceId()).thenReturn(99);

        assertThrows(ResourceNotFoundException.class, () -> service.list(SESSION_ID));

        verify(chatMapper).getAccessibleSessionById(99, USER_ID, SESSION_ID, 1L);
        verify(attachmentMapper, never()).getAssistantSessionAttachments(anyInt(), anyInt(), eq(1L));
    }

    @Test
    void uploadLimitIsEnforcedBeforeReadingTheFile() {
        AiChatSession session = activeSession();
        User actor = mock(User.class);
        when(actor.getId()).thenReturn(USER_ID);
        when(workspaceService.getMembers(WORKSPACE_ID)).thenReturn(List.of(actor));
        when(chatMapper.getAccessibleSessionById(WORKSPACE_ID, USER_ID, SESSION_ID, 1L))
                .thenReturn(session);
        when(attachmentMapper.countAssistantSessionAttachments(WORKSPACE_ID, SESSION_ID, 1L))
                .thenReturn(AiChatAttachmentPolicy.MAX_ATTACHMENTS);
        UploadSource source = UploadSource.from(
                "notes.txt", "text/plain", "content".getBytes(StandardCharsets.UTF_8));

        assertThrows(ConflictException.class, () -> service.upload(SESSION_ID, source));

        verify(attachmentPolicy, never()).prepare(source);
        verify(writeOperations, never()).uploadAssistantSession(
                anyInt(), anyInt(), any(), any(), any(Runnable.class));
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
        InspectedUpload prepared = inspected("content".getBytes(StandardCharsets.UTF_8));
        ScannedUpload scanned = mock(ScannedUpload.class);
        Attachment uploaded = new Attachment();
        uploaded.setId(31);
        uploaded.setWorkspaceId(WORKSPACE_ID);
        uploaded.setEntityType("ai_chat_session");
        uploaded.setEntityId(SESSION_ID);
        uploaded.setFileName("notes.txt");
        uploaded.setContentType("text/plain");
        uploaded.setSize(7L);
        when(chatMapper.getAccessibleSessionById(WORKSPACE_ID, USER_ID, SESSION_ID, 1L))
                .thenReturn(session);
        when(chatMapper.getSessionByIdForUpdate(WORKSPACE_ID, USER_ID, SESSION_ID))
                .thenReturn(session);
        when(attachmentPolicy.prepare(source)).thenReturn(prepared);
        when(uploadMalwareScanner.scan(prepared)).thenReturn(scanned);
        when(authService.getCurrentUser()).thenReturn(actor);
        when(writeOperations.uploadAssistantSession(
                eq(WORKSPACE_ID), eq(SESSION_ID), eq(scanned), eq(actor), any(Runnable.class)))
                .thenReturn(uploaded);

        service.upload(SESSION_ID, source);

        verify(attachmentMapper, times(2)).countAssistantSessionAttachments(
                WORKSPACE_ID, SESSION_ID, 1L);
        InOrder admissionOrder = org.mockito.Mockito.inOrder(
                chatMapper, uploadMalwareScanner);
        admissionOrder.verify(chatMapper).getAccessibleSessionById(
                WORKSPACE_ID, USER_ID, SESSION_ID, 1L);
        admissionOrder.verify(uploadMalwareScanner).scan(prepared);
        admissionOrder.verify(chatMapper).getSessionByIdForUpdate(
                WORKSPACE_ID, USER_ID, SESSION_ID);
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

    @Test
    void scannerFailureCannotReachAssistantPersistence() {
        AiChatSession session = activeSession();
        User actor = mock(User.class);
        when(actor.getId()).thenReturn(USER_ID);
        when(workspaceService.getMembers(WORKSPACE_ID)).thenReturn(List.of(actor));
        when(chatMapper.getAccessibleSessionById(WORKSPACE_ID, USER_ID, SESSION_ID, 1L))
                .thenReturn(session);
        UploadSource source = UploadSource.from(
                "notes.txt", "text/plain", "content".getBytes(StandardCharsets.UTF_8));
        InspectedUpload prepared = inspected("content".getBytes(StandardCharsets.UTF_8));
        when(attachmentPolicy.prepare(source)).thenReturn(prepared);
        when(uploadMalwareScanner.scan(prepared))
                .thenThrow(new ooo.klae.connex.backend.exceptions.ServiceUnavailableException(
                        "scanner unavailable"));

        assertThrows(
                ooo.klae.connex.backend.exceptions.ServiceUnavailableException.class,
                () -> service.upload(SESSION_ID, source));

        verify(writeOperations, never()).uploadAssistantSession(
                anyInt(), anyInt(), any(), any(), any(Runnable.class));
        verify(chatMapper, never()).getSessionByIdForUpdate(anyInt(), anyInt(), anyInt());
    }

    @ParameterizedTest
    @ValueSource(strings = {"quarantined", "infected", "unscannable", "suspicious"})
    void deniedAttachmentWithoutQuarantineAuthorityIsRefusedBeforeMutation(String state) {
        stubDeletableSession(state, state);
        doThrow(new ForbiddenException("Requires the ATTACHMENT_QUARANTINE_MANAGE permission"))
                .when(workspaceService)
                .requirePermission(WORKSPACE_ID, USER_ID, Permission.ATTACHMENT_QUARANTINE_MANAGE);

        assertThrows(ForbiddenException.class, () -> service.delete(SESSION_ID, ATTACHMENT_ID));

        InOrder order = inOrder(attachmentMapper, scanMapper, workspaceService);
        order.verify(attachmentMapper).lockIdsByUrl(WORKSPACE_ID, MANAGED_URL);
        order.verify(scanMapper).lockById(WORKSPACE_ID, ATTACHMENT_ID);
        order.verify(workspaceService).requirePermission(
                WORKSPACE_ID, USER_ID, Permission.ATTACHMENT_QUARANTINE_MANAGE);
        verify(managedObjectService, never()).deleteAttachmentAfterCommit(anyInt(), any());
        verify(attachmentMapper, never()).delete(anyInt(), anyInt());
        verifyNoInteractions(auditService);
    }

    @Test
    void securityStateIsTakenFromTheLockedRowNotTheDiscoveryRead() {
        stubDeletableSession("clean", "quarantined");
        doThrow(new ForbiddenException("Requires the ATTACHMENT_QUARANTINE_MANAGE permission"))
                .when(workspaceService)
                .requirePermission(WORKSPACE_ID, USER_ID, Permission.ATTACHMENT_QUARANTINE_MANAGE);

        assertThrows(ForbiddenException.class, () -> service.delete(SESSION_ID, ATTACHMENT_ID));

        verify(attachmentMapper, never()).delete(anyInt(), anyInt());
        verify(managedObjectService, never()).deleteAttachmentAfterCommit(anyInt(), any());
        verifyNoInteractions(auditService);
    }

    @Test
    void quarantineAuthorityDeletionIsAuditedStrictly() {
        stubDeletableSession("quarantined", "quarantined");

        service.delete(SESSION_ID, ATTACHMENT_ID);

        InOrder order = inOrder(workspaceService, managedObjectService, organizationAuthority, attachmentMapper, auditService);
        order.verify(workspaceService).requirePermission(
                WORKSPACE_ID, USER_ID, Permission.ATTACHMENT_QUARANTINE_MANAGE);
        order.verify(managedObjectService).deleteAttachmentAfterCommit(WORKSPACE_ID, MANAGED_URL);
        order.verify(organizationAuthority).retain(1, 1L);
        order.verify(attachmentMapper).delete(WORKSPACE_ID, ATTACHMENT_ID);
        order.verify(auditService).recordStrict("malware.quarantine_deleted", "attachment",
                ATTACHMENT_ID, null, "Deleted quarantined assistant attachment", null);
        verify(auditService, never()).record(any(), any(), any(), any(), any(), any());
    }

    @Test
    void strictAuditFailurePropagatesSoTheDeletionCannotCommit() {
        stubDeletableSession("infected", "infected");
        IllegalStateException failure = new IllegalStateException("audit append failed");
        doThrow(failure).when(auditService).recordStrict(
                eq("malware.quarantine_deleted"), any(), any(), any(), any(), any());

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> service.delete(SESSION_ID, ATTACHMENT_ID));

        assertSame(failure, thrown);
        verify(auditService, never()).record(any(), any(), any(), any(), any(), any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"clean", "pending", "scanning", "error"})
    void ordinaryAssistantDeletionIsUnchanged(String state) {
        stubDeletableSession(state, state);

        service.delete(SESSION_ID, ATTACHMENT_ID);

        verify(workspaceService).requirePermission(
                WORKSPACE_ID, USER_ID, Permission.ATTACHMENT_DELETE);
        verify(workspaceService, never()).requirePermission(
                WORKSPACE_ID, USER_ID, Permission.ATTACHMENT_QUARANTINE_MANAGE);
        verify(managedObjectService).deleteAttachmentAfterCommit(WORKSPACE_ID, MANAGED_URL);
        verify(attachmentMapper).delete(WORKSPACE_ID, ATTACHMENT_ID);
        verify(auditService).record(eq("attachment.delete"), eq("attachment"), eq(ATTACHMENT_ID),
                eq("notes.txt"), eq("Deleted assistant attachment notes.txt"), any());
        verify(auditService, never()).recordStrict(any(), any(), any(), any(), any(), any());
    }

    private void stubDeletableSession(String discoveredState, String lockedState) {
        AiChatSession session = activeSession();
        User actor = mock(User.class);
        when(actor.getId()).thenReturn(USER_ID);
        when(workspaceService.getMembers(WORKSPACE_ID)).thenReturn(List.of(actor));
        when(chatMapper.getSessionByIdForUpdate(WORKSPACE_ID, USER_ID, SESSION_ID))
                .thenReturn(session);
        when(attachmentMapper.getAssistantSessionAttachment(WORKSPACE_ID, SESSION_ID, ATTACHMENT_ID, 1L))
                .thenReturn(sessionAttachment(discoveredState));
        when(attachmentMapper.lockIdsByUrl(WORKSPACE_ID, MANAGED_URL))
                .thenReturn(List.of(ATTACHMENT_ID));
        when(scanMapper.lockById(WORKSPACE_ID, ATTACHMENT_ID))
                .thenReturn(sessionAttachment(lockedState));
        when(managedObjectService.isManagedAttachmentUrl(MANAGED_URL)).thenReturn(true);
    }

    private static Attachment sessionAttachment(String state) {
        Attachment attachment = new Attachment();
        attachment.setId(ATTACHMENT_ID);
        attachment.setWorkspaceId(WORKSPACE_ID);
        attachment.setEntityType("ai_chat_session");
        attachment.setEntityId(SESSION_ID);
        attachment.setFileName("notes.txt");
        attachment.setUrl(MANAGED_URL);
        attachment.setScanState(state);
        return attachment;
    }

    private static AiChatSession activeSession() {
        AiChatSession session = new AiChatSession();
        session.setId(SESSION_ID);
        session.setWorkspaceId(WORKSPACE_ID);
        session.setOrganizationPrivacyGeneration(1L);
        session.setCreatedByUserId(USER_ID);
        session.setVisibility("private");
        session.setStatus("active");
        return session;
    }

    private static InspectedUpload inspected(byte[] content) {
        try {
            return new InspectedUpload(
                "notes.txt",
                "text/plain",
                "txt",
                UploadFormat.TEXT,
                content,
                MessageDigest.getInstance("SHA-256").digest(content));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
