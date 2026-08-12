package ooo.klae.connex.backend.ai.assistant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import ooo.klae.connex.backend.ai.AiCompletionOutcome;
import ooo.klae.connex.backend.ai.AiInvocation;
import ooo.klae.connex.backend.ai.AiInvocationAdmissionService;
import ooo.klae.connex.backend.ai.AiInvocationService;
import ooo.klae.connex.backend.ai.provider.AiInputImage;
import ooo.klae.connex.backend.beans.Attachment;
import ooo.klae.connex.backend.storage.ManagedObjectService;
import ooo.klae.connex.backend.storage.ManagedObjectService.ManagedContent;
import ooo.klae.connex.backend.storage.StoredObject;

class AiChatAttachmentContextServiceTest {
    private static final AiChatQueuedTurn TURN = new AiChatQueuedTurn(
            7, 11, 13, 17, 19, 1, 23L, true, List.of(), List.of(31, 32, 33));
    private static final Instant NOW = Instant.parse("2026-08-11T00:00:00Z");

    private AiChatTurnPersistenceService persistenceService;
    private AiChatAttachmentPolicy attachmentPolicy;
    private ManagedObjectService managedObjectService;
    private AiInvocationAdmissionService invocationAdmissionService;
    private AiInvocationAdmissionService.DirectAdmission directAdmission;
    private AiInvocationService invocationService;
    private AiChatAttachmentContextService service;

    @BeforeEach
    void setUp() {
        persistenceService = mock(AiChatTurnPersistenceService.class);
        attachmentPolicy = mock(AiChatAttachmentPolicy.class);
        managedObjectService = mock(ManagedObjectService.class);
        invocationAdmissionService = mock(AiInvocationAdmissionService.class);
        directAdmission = mock(AiInvocationAdmissionService.DirectAdmission.class);
        invocationService = mock(AiInvocationService.class);
        service = new AiChatAttachmentContextService(
                persistenceService,
                attachmentPolicy,
                managedObjectService,
                invocationAdmissionService,
                invocationService,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void exhaustedTextBudgetPreventsLaterImageEgress() {
        Attachment first = attachment(31, "first.txt", "text/plain");
        Attachment second = attachment(32, "second.txt", "text/plain");
        Attachment image = attachment(33, "scan.jpg", "image/jpeg");
        when(persistenceService.loadAttachments(TURN)).thenReturn(List.of(first, second, image));
        when(managedObjectService.openAttachment(TURN.workspaceId(), first))
                .thenReturn(content(new byte[] { 1 }));
        when(managedObjectService.openAttachment(TURN.workspaceId(), second))
                .thenReturn(content(new byte[] { 2 }));
        when(attachmentPolicy.readText(any(), eq(1L)))
                .thenReturn("a".repeat(AiChatAttachmentPolicy.MAX_PROMPT_TEXT_CHARS))
                .thenReturn("b".repeat(AiChatAttachmentPolicy.MAX_PROMPT_TEXT_CHARS));

        AiChatAttachmentContext context = service.prepare(TURN, NOW.plusSeconds(70));

        assertEquals(3, context.data().size());
        assertEquals("", context.data().get(2).get("content"));
        assertEquals(true, context.data().get(2).get("truncated"));
        verify(managedObjectService, never()).openAttachment(TURN.workspaceId(), image);
        verify(invocationService, never()).complete(any(AiInvocation.class), any());
    }

    @Test
    void imageDescriptionUsesTheAuditedInvocationPath() {
        Attachment image = attachment(33, "scan.jpg", "image/jpeg");
        byte[] jpeg = { (byte) 0xff, (byte) 0xd8, (byte) 0xff, 1 };
        AiInputImage inputImage = new AiInputImage("image/jpeg", jpeg, 1, 1);
        when(persistenceService.loadAttachments(TURN)).thenReturn(List.of(image));
        when(managedObjectService.openAttachment(TURN.workspaceId(), image))
                .thenReturn(content(jpeg));
        when(attachmentPolicy.readImage(eq("scan.jpg"), any(), eq((long) jpeg.length)))
                .thenReturn(inputImage);
        when(invocationAdmissionService.acquireDirect()).thenReturn(directAdmission);
        when(invocationService.complete(any(AiInvocation.class), eq(directAdmission)))
                .thenReturn(new AiCompletionOutcome("A scanned note", 0, 12, 4, "stop"));

        AiChatAttachmentContext context = service.prepare(TURN, NOW.plusSeconds(70));

        assertEquals("A scanned note", context.data().getFirst().get("content"));
        assertEquals(12, context.inputTokens());
        assertEquals(4, context.outputTokens());
        ArgumentCaptor<AiInvocation> invocation = ArgumentCaptor.forClass(AiInvocation.class);
        verify(invocationService).complete(invocation.capture(), eq(directAdmission));
        assertEquals(List.of(inputImage), invocation.getValue().images());
        assertTrue(invocation.getValue().prompt().getSystemPrompt().contains("untrusted content"));
    }

    @Test
    void expiredTurnDeadlinePreventsAttachmentReadsAndProviderCalls() {
        assertThrows(
                AiAssistantLoopException.class,
                () -> service.prepare(TURN, NOW));

        verify(persistenceService, never()).loadAttachments(TURN);
        verify(invocationService, never()).complete(any(AiInvocation.class), any());
    }

    private static Attachment attachment(int id, String fileName, String contentType) {
        Attachment attachment = new Attachment();
        attachment.setId(id);
        attachment.setWorkspaceId(TURN.workspaceId());
        attachment.setEntityType("ai_chat_session");
        attachment.setEntityId(TURN.sessionId());
        attachment.setFileName(fileName);
        attachment.setContentType(contentType);
        attachment.setSize(1L);
        return attachment;
    }

    private static ManagedContent content(byte[] bytes) {
        return new ManagedContent(
                new StoredObject(new ByteArrayInputStream(bytes), bytes.length),
                "application/octet-stream",
                "attachment");
    }
}
