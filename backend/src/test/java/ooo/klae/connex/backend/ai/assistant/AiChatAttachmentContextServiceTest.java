package ooo.klae.connex.backend.ai.assistant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isA;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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
import ooo.klae.connex.backend.ai.masking.EntityKind;
import ooo.klae.connex.backend.ai.masking.MaskingContext;
import ooo.klae.connex.backend.ai.masking.MaskingEngine;
import ooo.klae.connex.backend.ai.provider.AiInputImage;
import ooo.klae.connex.backend.beans.Attachment;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
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
        verify(invocationService, never()).complete(
                any(AiInvocation.class), any(), anyLong(), any());
    }

    @Test
    void textAttachmentContactDataIsRedactedBeforeThePromptBoundary() {
        Attachment attachment = attachment(31, "notes.txt", "text/plain");
        when(persistenceService.loadAttachments(TURN)).thenReturn(List.of(attachment));
        when(managedObjectService.openAttachment(TURN.workspaceId(), attachment))
                .thenReturn(content(new byte[] {1}));
        when(attachmentPolicy.readText(any(), eq(1L)))
                .thenReturn("x".repeat(31_989)
                        + " victim@example.com "
                        + "tail".repeat(20));

        AiChatAttachmentContext context = service.prepare(TURN, NOW.plusSeconds(70));

        String retained = (String) context.data().getFirst().get("content");
        assertEquals(32_000, retained.length());
        assertTrue(retained.endsWith("[redacted]"));
        assertFalse(retained.contains("victim"));
        assertFalse(retained.contains("@example"));
        assertEquals(true, context.data().getFirst().get("truncated"));
    }

    @Test
    void textAttachmentIdentifierIsRedactedBeforeThePromptBoundary() {
        Attachment attachment = attachment(31, "notes.txt", "text/plain");
        when(persistenceService.loadAttachments(TURN)).thenReturn(List.of(attachment));
        when(managedObjectService.openAttachment(TURN.workspaceId(), attachment))
                .thenReturn(content(new byte[] {1}));
        when(attachmentPolicy.readText(any(), eq(1L)))
                .thenReturn("x".repeat(31_988)
                        + " Ada Lovelace "
                        + "tail".repeat(20));
        MaskingContext maskingContext = new MaskingContext();
        MaskingEngine.maskField(EntityKind.PERSON, "Ada Lovelace", maskingContext);

        AiChatAttachmentContext context = service.prepare(
                TURN, NOW.plusSeconds(70), maskingContext);

        String retained = (String) context.data().getFirst().get("content");
        assertEquals(32_000, retained.length());
        assertTrue(retained.contains("[redacted]"));
        assertFalse(retained.contains("Ada"));
        assertFalse(retained.contains("Lovelace"));
        assertEquals(true, context.data().getFirst().get("truncated"));
    }

    @Test
    void textAttachmentSpecialCareTextIsScreenedBeforeThePromptBoundary() {
        Attachment attachment = attachment(31, "notes.txt", "text/plain");
        when(persistenceService.loadAttachments(TURN)).thenReturn(List.of(attachment));
        when(managedObjectService.openAttachment(TURN.workspaceId(), attachment))
                .thenReturn(content(new byte[] {1}));
        when(attachmentPolicy.readText(any(), eq(1L)))
                .thenReturn("x".repeat(31_985)
                        + " The contact discussed a diagnosis.");

        AiChatAttachmentContext context = service.prepare(TURN, NOW.plusSeconds(70));

        assertEquals(
                "[omitted by policy]",
                context.data().getFirst().get("content"));
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
        when(invocationService.complete(
                any(AiInvocation.class),
                eq(directAdmission),
                eq(TURN.restrictionEpoch()),
                isA(Runnable.class)))
                .thenReturn(new AiCompletionOutcome("A scanned note", 0, 12, 4, "stop"));

        AiChatAttachmentContext context = service.prepare(TURN, NOW.plusSeconds(70));

        assertEquals("A scanned note", context.data().getFirst().get("content"));
        assertEquals(12, context.inputTokens());
        assertEquals(4, context.outputTokens());
        ArgumentCaptor<AiInvocation> invocation = ArgumentCaptor.forClass(AiInvocation.class);
        verify(invocationService).complete(
                invocation.capture(),
                eq(directAdmission),
                eq(TURN.restrictionEpoch()),
                isA(Runnable.class));
        verify(persistenceService).requireRunning(TURN);
        assertEquals(List.of(inputImage), invocation.getValue().images());
        assertEquals(NOW.plusSeconds(70), invocation.getValue().callerDeadline());
        assertTrue(invocation.getValue().prompt().getSystemPrompt().contains("untrusted content"));
    }

    @Test
    void participantRemovedMidTurnDoesNotEgressRemainingImages() {
        Attachment first = attachment(31, "first.jpg", "image/jpeg");
        Attachment second = attachment(32, "second.jpg", "image/jpeg");
        byte[] jpeg = { (byte) 0xff, (byte) 0xd8, (byte) 0xff, 1 };
        AiInputImage inputImage = new AiInputImage("image/jpeg", jpeg, 1, 1);
        when(persistenceService.loadAttachments(TURN)).thenReturn(List.of(first, second));
        doNothing()
                .doThrow(new ResourceNotFoundException("Assistant session is not accessible"))
                .when(persistenceService)
                .requireRunning(TURN);
        when(managedObjectService.openAttachment(TURN.workspaceId(), first))
                .thenReturn(content(jpeg));
        when(attachmentPolicy.readImage(eq("first.jpg"), any(), eq((long) jpeg.length)))
                .thenReturn(inputImage);
        when(invocationAdmissionService.acquireDirect()).thenReturn(directAdmission);
        when(invocationService.complete(
                any(AiInvocation.class),
                eq(directAdmission),
                eq(TURN.restrictionEpoch()),
                isA(Runnable.class)))
                .thenReturn(new AiCompletionOutcome("First image", 0, 12, 4, "stop"));

        assertThrows(
                ResourceNotFoundException.class,
                () -> service.prepare(TURN, NOW.plusSeconds(70)));

        verify(persistenceService, times(2)).requireRunning(TURN);
        verify(invocationService).complete(
                any(AiInvocation.class),
                eq(directAdmission),
                eq(TURN.restrictionEpoch()),
                isA(Runnable.class));
        verify(managedObjectService, never()).openAttachment(TURN.workspaceId(), second);
    }

    @Test
    void participantRemovedWhileReadingAnImageIsRejectedAtProviderEgress() {
        Attachment image = attachment(33, "scan.jpg", "image/jpeg");
        byte[] jpeg = { (byte) 0xff, (byte) 0xd8, (byte) 0xff, 1 };
        AiInputImage inputImage = new AiInputImage("image/jpeg", jpeg, 1, 1);
        when(persistenceService.loadAttachments(TURN)).thenReturn(List.of(image));
        doNothing()
                .doThrow(new ResourceNotFoundException("Assistant session is not accessible"))
                .when(persistenceService)
                .requireRunning(TURN);
        when(managedObjectService.openAttachment(TURN.workspaceId(), image))
                .thenReturn(content(jpeg));
        when(attachmentPolicy.readImage(eq("scan.jpg"), any(), eq((long) jpeg.length)))
                .thenReturn(inputImage);
        when(invocationAdmissionService.acquireDirect()).thenReturn(directAdmission);
        when(invocationService.complete(
                any(AiInvocation.class),
                eq(directAdmission),
                eq(TURN.restrictionEpoch()),
                isA(Runnable.class)))
                .thenAnswer(invocation -> {
                    invocation.getArgument(3, Runnable.class).run();
                    return new AiCompletionOutcome("unreachable", 0, 12, 4, "stop");
                });

        assertThrows(
                ResourceNotFoundException.class,
                () -> service.prepare(TURN, NOW.plusSeconds(70)));

        verify(attachmentPolicy).readImage(
                eq("scan.jpg"), any(), eq((long) jpeg.length));
        verify(persistenceService, times(2)).requireRunning(TURN);
    }

    @Test
    void expiredTurnDeadlinePreventsAttachmentReadsAndProviderCalls() {
        assertThrows(
                AiAssistantLoopException.class,
                () -> service.prepare(TURN, NOW));

        verify(persistenceService, never()).loadAttachments(TURN);
        verify(invocationService, never()).complete(
                any(AiInvocation.class), any(), anyLong(), any());
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
