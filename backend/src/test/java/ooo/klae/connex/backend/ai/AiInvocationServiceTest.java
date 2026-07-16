package ooo.klae.connex.backend.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import ooo.klae.connex.backend.ai.masking.EntityKind;
import ooo.klae.connex.backend.ai.masking.MaskedPrompt;
import ooo.klae.connex.backend.ai.masking.MaskingContext;
import ooo.klae.connex.backend.ai.masking.MaskingEngine;
import ooo.klae.connex.backend.ai.masking.MaskingLeakException;
import ooo.klae.connex.backend.ai.masking.PromptAssembly;
import ooo.klae.connex.backend.ai.introrationale.IntroRationaleContent;
import ooo.klae.connex.backend.ai.provider.AiCompletionRequest;
import ooo.klae.connex.backend.ai.provider.AiCompletionResult;
import ooo.klae.connex.backend.ai.provider.AiCredentials;
import ooo.klae.connex.backend.ai.provider.AiInputImage;
import ooo.klae.connex.backend.ai.provider.AiProvider;
import ooo.klae.connex.backend.ai.provider.AiProviderException;
import ooo.klae.connex.backend.ai.provider.AiProviderRouter;
import ooo.klae.connex.backend.ai.provider.ResolvedAiProvider;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.exceptions.TooManyRequestsException;
import ooo.klae.connex.backend.services.AiProviderConfigService;
import ooo.klae.connex.backend.services.AuditService;
import ooo.klae.connex.backend.services.WorkspaceService;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class AiInvocationServiceTest {
    private static final int WORKSPACE_ID = 11;
    private static final int ORG_ID = 22;
    private static final int ACTOR_ID = 33;
    private static final String FEATURE = "relationship.summary";

    @Mock private AiFeatureGate aiFeatureGate;
    @Mock private AiMediaAdmissionService aiMediaAdmissionService;
    @Mock private AiMediaAdmissionService.Lease mediaLease;
    @Mock private AiProviderConfigService aiProviderConfigService;
    @Mock private AiProvider aiProvider;
    @Mock private AiProviderRouter aiProviderRouter;
    @Mock private WorkspaceService workspaceService;
    @Mock private AuditService auditService;

    private AiInvocationService service;
    private ResolvedAiProvider resolved;

    @BeforeEach
    void setUp() {
        service = new AiInvocationService(aiFeatureGate, aiMediaAdmissionService, aiProviderConfigService,
                aiProviderRouter, workspaceService, auditService, new ObjectMapper());
        resolved = new ResolvedAiProvider("bedrock", "us-east-1", "anthropic.claude-3-sonnet-v1:0",
                null, null, null, null, false, true,
                AiCredentials.of(Map.of(
                        "accessKeyId", "AKIA_TEST",
                        "secretAccessKey", "SECRET_ACCESS_KEY")));
        lenient().when(workspaceService.getCurrentWorkspaceId()).thenReturn(WORKSPACE_ID);
        lenient().when(workspaceService.getCurrentOrgId()).thenReturn(ORG_ID);
        lenient().when(workspaceService.getCurrentUserId()).thenReturn(ACTOR_ID);
        lenient().when(aiProviderConfigService.resolveForOrg(ORG_ID)).thenReturn(resolved);
        lenient().when(aiProviderRouter.adapterFor("bedrock")).thenReturn(aiProvider);
        lenient().when(aiMediaAdmissionService.acquire(anyInt(), anyList())).thenReturn(mediaLease);
    }

    @Test
    void complete_gateDenies_auditsBlockedWithoutPromptText() {
        doThrow(new ForbiddenException("AI features are not available")).when(aiFeatureGate).requireAiUsable();
        AiInvocation invocation = invocation("Summarize relationship state");

        assertThrows(ForbiddenException.class, () -> service.complete(invocation));

        Map<?, ?> metadata = singleAuditMetadata();
        assertEquals("blocked", metadata.get("outcome"));
        assertEquals("gate", metadata.get("reason"));
        assertEquals("unresolved", metadata.get("provider"));
        assertEquals(1, metadata.get("messageCount"));
        assertNoContent(metadata);
        verify(aiProviderConfigService, never()).resolveForOrg(ORG_ID);
        verify(aiProvider, never()).complete(any());
    }

    @Test
    void complete_leakDetected_auditsBlockedAndDoesNotCallAdapter() {
        MaskingContext context = new MaskingContext();
        MaskingEngine.maskField(EntityKind.PERSON, "Mina Patel", context);
        MaskedPrompt prompt = PromptAssembly.builder()
                .system("Use concise analysis")
                .userTurn("Summarize Mina Patel")
                .build();
        AiInvocation invocation = new AiInvocation(FEATURE, context, prompt, 64, 0.2);

        assertThrows(MaskingLeakException.class, () -> service.complete(invocation));

        Map<?, ?> metadata = singleAuditMetadata();
        assertEquals("blocked", metadata.get("outcome"));
        assertEquals("leak", metadata.get("reason"));
        assertEquals("bedrock", metadata.get("provider"));
        assertNoContent(metadata);
        verify(aiProvider, never()).complete(any());
    }

    @Test
    void complete_success_demasksAndAuditsAttemptAndSuccess() {
        AiInvocation invocation = invocation("Summarize relationship state");
        when(aiProvider.complete(any(AiCompletionRequest.class)))
                .thenReturn(new AiCompletionResult("{{P1}} is ready for follow-up.", 12, 7, "end_turn"));

        AiCompletionOutcome outcome = service.complete(invocation);

        assertEquals("Mina Patel is ready for follow-up.", outcome.text());
        assertEquals(0, outcome.demaskWarnings());
        assertEquals(12, outcome.inputTokens());
        assertEquals(7, outcome.outputTokens());
        List<Map<?, ?>> audits = auditMetadata();
        assertEquals("attempt", audits.get(0).get("outcome"));
        assertEquals("success", audits.get(1).get("outcome"));
        assertEquals(12, audits.get(1).get("inputTokens"));
        assertEquals(7, audits.get(1).get("outputTokens"));
        assertEquals("end_turn", audits.get(1).get("stopReason"));
        assertEquals(0, audits.get(1).get("demaskWarnings"));
        assertNoContent(audits.get(0));
        assertNoContent(audits.get(1));
    }

    @Test
    void completeWithImagePropagatesBoundedMediaAndAuditsMetadataOnly() {
        AiInvocation base = invocation("Summarize relationship state");
        AiInputImage image = new AiInputImage(
                "image/jpeg", new byte[] {(byte) 0xff, (byte) 0xd8, (byte) 0xff, 1}, 100, 50);
        AiInvocation invocation = new AiInvocation(
                base.feature(), base.context(), base.prompt(), List.of(image), 64, 0.2);
        when(aiProvider.complete(any(AiCompletionRequest.class)))
                .thenReturn(new AiCompletionResult("{{P1}} is ready for follow-up.", 12, 7, "end_turn"));

        service.complete(invocation);

        verify(aiFeatureGate).requireAiImageUsable();
        verify(aiFeatureGate, never()).requireAiUsable();
        ArgumentCaptor<AiCompletionRequest> requestCaptor = ArgumentCaptor.forClass(AiCompletionRequest.class);
        verify(aiProvider).complete(requestCaptor.capture());
        assertEquals(1, requestCaptor.getValue().images().size());
        assertEquals(4, requestCaptor.getValue().images().getFirst().size());
        List<Map<?, ?>> audits = auditMetadata();
        assertEquals(1, audits.get(0).get("mediaCount"));
        assertEquals(4, audits.get(0).get("mediaBytes"));
        assertEquals(List.of("image/jpeg"), audits.get(0).get("mediaTypes"));
        assertNoContent(audits.get(0));
        assertNoContent(audits.get(1));
        assertMediaLeaseClosesBeforeTerminalAudit();
    }

    @Test
    void completeWithImageAdmissionDeniedAuditsAttemptAndBlockedWithoutProviderEgress() {
        AiInvocation base = invocation("Summarize relationship state");
        AiInputImage image = new AiInputImage(
                "image/jpeg", new byte[] {(byte) 0xff, (byte) 0xd8, (byte) 0xff, 1}, 100, 50);
        AiInvocation invocation = new AiInvocation(
                base.feature(), base.context(), base.prompt(), List.of(image), 64, 0.2);
        when(aiMediaAdmissionService.acquire(anyInt(), anyList()))
                .thenThrow(new TooManyRequestsException("AI image processing is busy"));

        assertThrows(TooManyRequestsException.class, () -> service.complete(invocation));

        List<Map<?, ?>> audits = auditMetadata();
        assertEquals("attempt", audits.get(0).get("outcome"));
        assertEquals("blocked", audits.get(1).get("outcome"));
        assertEquals("media_admission", audits.get(1).get("reason"));
        assertEquals(1, audits.get(1).get("mediaCount"));
        assertNoContent(audits.get(0));
        assertNoContent(audits.get(1));
        verify(aiProvider, never()).complete(any());
    }

    @Test
    void completeWithImageRejectsResolvedTextOnlyProviderBeforeAdapterEgress() {
        AiInvocation base = invocation("Summarize relationship state");
        AiInputImage image = new AiInputImage(
                "image/jpeg", new byte[] {(byte) 0xff, (byte) 0xd8, (byte) 0xff, 1}, 100, 50);
        AiInvocation invocation = new AiInvocation(
                base.feature(), base.context(), base.prompt(), List.of(image), 64, 0.2);
        resolved = new ResolvedAiProvider("openai_compatible", null, "llama3.3:70b",
                "https://provider.example.test/v1", null, null, null, false, false,
                AiCredentials.of(Map.of()));
        when(aiProviderConfigService.resolveForOrg(ORG_ID)).thenReturn(resolved);

        AiProviderException exception = assertThrows(AiProviderException.class,
                () -> service.complete(invocation));

        assertEquals("Configured AI model does not support image input", exception.getMessage());
        Map<?, ?> metadata = singleAuditMetadata();
        assertEquals("blocked", metadata.get("outcome"));
        assertEquals("provider_capability", metadata.get("reason"));
        assertEquals("openai_compatible", metadata.get("provider"));
        assertNoContent(metadata);
        verify(aiMediaAdmissionService, never()).acquire(anyInt(), anyList());
        verify(aiProviderRouter, never()).adapterFor(any());
        verify(aiProvider, never()).complete(any());
    }

    @Test
    void completeStructuredWithImageReleasesAdmissionBeforeMalformedTerminalAudit() {
        AiInvocation invocation = withImage(invocation("Summarize relationship state"));
        when(aiProvider.complete(any(AiCompletionRequest.class)))
                .thenReturn(new AiCompletionResult("not json", 10, 3, "end_turn"));

        AiStructuredOutcome<IntroRationaleContent> outcome =
                service.completeStructured(invocation, IntroRationaleContent.class);

        AiStructuredOutcome.Malformed<IntroRationaleContent> malformed = asMalformed(outcome);
        assertEquals(AiStructuredOutcome.REASON_MALFORMED, malformed.reason());
        assertMediaLeaseClosesBeforeTerminalAudit();
    }

    @Test
    void completeStructuredWithImageReleasesAdmissionAfterBindingFailure() {
        AiInvocation invocation = withImage(invocation("Summarize relationship state"));
        when(aiProvider.complete(any(AiCompletionRequest.class)))
                .thenReturn(new AiCompletionResult("{\"rationale\":{}}", 10, 3, "end_turn"));

        AiStructuredOutcome<IntroRationaleContent> outcome =
                service.completeStructured(invocation, IntroRationaleContent.class);

        assertInstanceOf(AiStructuredOutcome.Malformed.class, outcome);
        verify(mediaLease).close();
    }

    @Test
    void complete_adapterThrows_auditsFailureAndPropagates() {
        AiInvocation invocation = invocation("Summarize relationship state");
        AiProviderException expected = new AiProviderException("transport unavailable");
        when(aiProvider.complete(any(AiCompletionRequest.class))).thenThrow(expected);

        AiProviderException thrown = assertThrows(AiProviderException.class, () -> service.complete(invocation));

        assertEquals(expected, thrown);
        List<Map<?, ?>> audits = auditMetadata();
        assertEquals("attempt", audits.get(0).get("outcome"));
        assertEquals("failure", audits.get(1).get("outcome"));
        assertEquals("provider_exception", audits.get(1).get("reason"));
        assertNoContent(audits.get(1));
    }

    @Test
    void completeStructured_cleanObject_returnsParsedDemaskedValue() {
        AiInvocation invocation = invocation("Summarize relationship state");
        when(aiProvider.complete(any(AiCompletionRequest.class)))
                .thenReturn(new AiCompletionResult("{\"rationale\":\"Follow up with {{P1}} soon.\"}", 30, 12, "end_turn"));

        AiStructuredOutcome<IntroRationaleContent> outcome =
                service.completeStructured(invocation, IntroRationaleContent.class);

        AiStructuredOutcome.Parsed<IntroRationaleContent> parsed = asParsed(outcome);
        assertEquals("Follow up with Mina Patel soon.", parsed.value().rationale());
        assertEquals(0, parsed.demaskWarnings());
        assertEquals(30, parsed.inputTokens());
        assertEquals(12, parsed.outputTokens());
        assertEquals("end_turn", parsed.stopReason());
    }

    @Test
    void completeStructured_nonJsonProse_returnsMalformedOutput() {
        AiInvocation invocation = invocation("Summarize relationship state");
        when(aiProvider.complete(any(AiCompletionRequest.class)))
                .thenReturn(new AiCompletionResult("Sorry, I cannot produce a structured answer.", 10, 3, "end_turn"));

        AiStructuredOutcome<IntroRationaleContent> outcome =
                service.completeStructured(invocation, IntroRationaleContent.class);

        AiStructuredOutcome.Malformed<IntroRationaleContent> malformed = asMalformed(outcome);
        assertEquals(AiStructuredOutcome.REASON_MALFORMED, malformed.reason());
        assertEquals("end_turn", malformed.stopReason());
    }

    @Test
    void completeStructured_truncatedObjectAtTokenLimit_returnsTruncated() {
        AiInvocation invocation = invocation("Summarize relationship state");
        when(aiProvider.complete(any(AiCompletionRequest.class)))
                .thenReturn(new AiCompletionResult("{\"rationale\":\"Follow up with {{P1}}", 64, 64, "max_tokens"));

        AiStructuredOutcome<IntroRationaleContent> outcome =
                service.completeStructured(invocation, IntroRationaleContent.class);

        AiStructuredOutcome.Malformed<IntroRationaleContent> malformed = asMalformed(outcome);
        assertEquals(AiStructuredOutcome.REASON_TRUNCATED, malformed.reason());
        assertEquals("max_tokens", malformed.stopReason());
    }

    @Test
    void completeStructured_stripsLeadingReasoningPreambleBeforeJson() {
        AiInvocation invocation = invocation("Summarize relationship state");
        when(aiProvider.complete(any(AiCompletionRequest.class)))
                .thenReturn(new AiCompletionResult(
                        "<thought>plan the reply first</thought>{\"rationale\":\"Ping {{P1}}.\"}", 40, 15, "end_turn"));

        AiStructuredOutcome<IntroRationaleContent> outcome =
                service.completeStructured(invocation, IntroRationaleContent.class);

        AiStructuredOutcome.Parsed<IntroRationaleContent> parsed = asParsed(outcome);
        assertEquals("Ping Mina Patel.", parsed.value().rationale());
    }

    @Test
    void completeStructured_success_emitsAttemptAndSuccessAuditWithoutContent() {
        AiInvocation invocation = invocation("Summarize relationship state");
        when(aiProvider.complete(any(AiCompletionRequest.class)))
                .thenReturn(new AiCompletionResult("{\"rationale\":\"Ping {{P1}}.\"}", 20, 8, "end_turn"));

        service.completeStructured(invocation, IntroRationaleContent.class);

        List<Map<?, ?>> audits = auditMetadata();
        assertEquals("attempt", audits.get(0).get("outcome"));
        assertEquals("success", audits.get(1).get("outcome"));
        assertEquals(Boolean.TRUE, audits.get(1).get("structured"));
        assertEquals("parsed", audits.get(1).get("parseOutcome"));
        assertNoContent(audits.get(0));
        assertNoContent(audits.get(1));
    }

    private static <T> AiStructuredOutcome.Parsed<T> asParsed(AiStructuredOutcome<T> outcome) {
        if (outcome instanceof AiStructuredOutcome.Parsed<T> parsed) {
            return parsed;
        }
        throw new AssertionError("Expected a parsed structured outcome but was " + outcome);
    }

    private static <T> AiStructuredOutcome.Malformed<T> asMalformed(AiStructuredOutcome<T> outcome) {
        if (outcome instanceof AiStructuredOutcome.Malformed<T> malformed) {
            return malformed;
        }
        throw new AssertionError("Expected a malformed structured outcome but was " + outcome);
    }

    private AiInvocation invocation(String maskedPromptText) {
        MaskingContext context = new MaskingContext();
        String person = MaskingEngine.maskField(EntityKind.PERSON, "Mina Patel", context);
        MaskedPrompt prompt = PromptAssembly.builder()
                .system("Use concise analysis")
                .userTurn(maskedPromptText + " for " + person)
                .build();
        return new AiInvocation(FEATURE, context, prompt, 64, 0.2);
    }

    private static AiInvocation withImage(AiInvocation invocation) {
        AiInputImage image = new AiInputImage(
                "image/jpeg", new byte[] {(byte) 0xff, (byte) 0xd8, (byte) 0xff, 1}, 100, 50);
        return new AiInvocation(
                invocation.feature(), invocation.context(), invocation.prompt(), List.of(image),
                invocation.maxTokens(), invocation.temperature());
    }

    private void assertMediaLeaseClosesBeforeTerminalAudit() {
        InOrder order = inOrder(auditService, aiProvider, mediaLease);
        order.verify(auditService).recordIndependentScoped(
                eq("ai.llm.call"), eq("ai_call"), isNull(), eq(WORKSPACE_ID), eq(ORG_ID),
                any(), any(), any());
        order.verify(aiProvider).complete(any());
        order.verify(mediaLease).close();
        order.verify(auditService).recordIndependentScoped(
                eq("ai.llm.call"), eq("ai_call"), isNull(), eq(WORKSPACE_ID), eq(ORG_ID),
                any(), any(), any());
    }

    private Map<?, ?> singleAuditMetadata() {
        List<Map<?, ?>> audits = auditMetadata(1);
        return audits.getFirst();
    }

    private List<Map<?, ?>> auditMetadata() {
        return auditMetadata(2);
    }

    private List<Map<?, ?>> auditMetadata(int count) {
        ArgumentCaptor<Object> metadataCaptor = ArgumentCaptor.forClass(Object.class);
        verify(auditService, times(count)).recordIndependentScoped(eq("ai.llm.call"), eq("ai_call"), isNull(),
                eq(WORKSPACE_ID), eq(ORG_ID), any(), any(), metadataCaptor.capture());
        return metadataCaptor.getAllValues().stream()
                .<Map<?, ?>>map(AiInvocationServiceTest::metadataMap)
                .toList();
    }

    private static Map<?, ?> metadataMap(Object value) {
        assertInstanceOf(Map.class, value);
        return (Map<?, ?>) value;
    }

    private static void assertNoContent(Map<?, ?> metadata) {
        String serialized = metadata.toString();
        assertFalse(serialized.contains("Mina Patel"));
        assertFalse(serialized.contains("{{P1}}"));
        assertFalse(serialized.contains("Summarize"));
        assertFalse(serialized.contains("Use concise analysis"));
        assertFalse(serialized.contains("ready for follow-up"));
        assertFalse(serialized.contains("SECRET_ACCESS_KEY"));
        assertFalse(serialized.contains("/9j/"));
    }
}
