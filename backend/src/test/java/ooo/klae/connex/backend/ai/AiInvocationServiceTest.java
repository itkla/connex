package ooo.klae.connex.backend.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import ooo.klae.connex.backend.ai.masking.EntityKind;
import ooo.klae.connex.backend.ai.masking.MaskedPrompt;
import ooo.klae.connex.backend.ai.assistant.AiAssistantAccessFence;
import ooo.klae.connex.backend.ai.masking.MaskingContext;
import ooo.klae.connex.backend.ai.masking.MaskingEngine;
import ooo.klae.connex.backend.ai.masking.MaskingLeakException;
import ooo.klae.connex.backend.ai.masking.PromptAssembly;
import ooo.klae.connex.backend.ai.introrationale.IntroRationaleContent;
import ooo.klae.connex.backend.ai.provider.AiCompletionRequest;
import ooo.klae.connex.backend.ai.provider.AiCompletionResult;
import ooo.klae.connex.backend.ai.provider.AiCredentials;
import ooo.klae.connex.backend.ai.provider.AiInputImage;
import ooo.klae.connex.backend.ai.provider.AiOutputMode;
import ooo.klae.connex.backend.ai.provider.AiProvider;
import ooo.klae.connex.backend.ai.provider.AiProviderCapabilities;
import ooo.klae.connex.backend.ai.provider.AiProviderException;
import ooo.klae.connex.backend.ai.provider.AiProviderRequestRejectedException;
import ooo.klae.connex.backend.ai.provider.AiProviderRouter;
import ooo.klae.connex.backend.ai.provider.AiReasoningMode;
import ooo.klae.connex.backend.ai.provider.AiResponseSchema;
import ooo.klae.connex.backend.ai.provider.AiStructuredOutputEnforcement;
import ooo.klae.connex.backend.ai.provider.ResolvedAiProvider;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.exceptions.AiBudgetExhaustedException;
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
    private static final AiFeature FEATURE = AiFeature.DEAL_BRIEF;

    @Mock private AiFeatureGate aiFeatureGate;
    @Mock private AiInvocationAdmissionService aiInvocationAdmissionService;
    @Mock private AiInvocationAdmissionService.Admission invocationAdmission;
    @Mock private AiInvocationAdmissionService.DirectAdmission directAdmission;
    @Mock private AiInvocationAdmissionService.DirectAdmission fallbackAdmission;
    @Mock private AiMediaAdmissionService aiMediaAdmissionService;
    @Mock private AiMediaAdmissionService.Lease mediaLease;
    @Mock private AiProviderConfigService aiProviderConfigService;
    @Mock private AiProvider aiProvider;
    @Mock private AiProviderRouter aiProviderRouter;
    @Mock private Runnable providerAttemptGuard;
    @Mock private Runnable providerTransport;
    @Mock private WorkspaceService workspaceService;
    @Mock private AuditService auditService;
    @Mock private AiOrganizationBudgetCoordinator budgetCoordinator;
    @Mock private AiOrganizationBudgetCoordinator.Lease budgetLease;
    @Mock private AiOrganizationBudgetCoordinator.Lease fallbackBudgetLease;

    private AiInvocationService service;
    private AiRestrictionEpoch restrictionEpoch;
    private ResolvedAiProvider resolved;

    @BeforeEach
    void setUp() {
        restrictionEpoch = new AiRestrictionEpoch();
        service = new AiInvocationService(
                aiFeatureGate, aiInvocationAdmissionService, aiMediaAdmissionService,
                aiProviderConfigService, aiProviderRouter, restrictionEpoch,
                new AiAssistantAccessFence(),
                workspaceService, auditService, new ObjectMapper(), budgetCoordinator);
        resolved = new ResolvedAiProvider("bedrock", "us-east-1", "anthropic.claude-3-sonnet-v1:0",
                null, null, null, null, false, true,
                AiCredentials.of(Map.of(
                        "accessKeyId", "AKIA_TEST",
                        "secretAccessKey", "SECRET_ACCESS_KEY")));
        lenient().when(workspaceService.getCurrentWorkspaceId()).thenReturn(WORKSPACE_ID);
        lenient().when(workspaceService.getCurrentOrgId()).thenReturn(ORG_ID);
        lenient().when(workspaceService.getCurrentUserId()).thenReturn(ACTOR_ID);
        lenient().when(aiProviderConfigService.resolveForOrg(ORG_ID, ACTOR_ID)).thenReturn(resolved);
        lenient().when(aiProviderRouter.adapterFor("bedrock")).thenReturn(aiProvider);
        lenient().when(aiProvider.reasoningCapability(any())).thenReturn(AiReasoningMode.TAGGED);
        lenient().when(aiProvider.contextWindowTokens(any())).thenReturn(4096);
        lenient().when(aiMediaAdmissionService.acquire(anyInt(), anyList())).thenReturn(mediaLease);
        lenient().when(budgetCoordinator.reserve(
                eq(ORG_ID), any(AiInvocation.class), anyString()))
                .thenReturn(budgetLease, fallbackBudgetLease);
    }

    @Test
    void complete_gateDenies_auditsBlockedWithoutPromptText() {
        doThrow(new ForbiddenException("AI features are not available"))
                .when(aiFeatureGate).requireAiUsable(AiFeature.DEAL_BRIEF);
        AiInvocation invocation = invocation("Summarize relationship state");

        assertThrows(ForbiddenException.class, () -> service.complete(invocation));

        Map<?, ?> metadata = singleAuditMetadata();
        assertEquals("blocked", metadata.get("outcome"));
        assertEquals("gate", metadata.get("reason"));
        assertEquals("unresolved", metadata.get("provider"));
        assertEquals(1, metadata.get("messageCount"));
        assertNoContent(metadata);
        verify(aiProviderConfigService, never()).resolveForOrg(ORG_ID, ACTOR_ID);
        verify(aiProvider, never()).complete(any());
    }

    @Test
    void currentProviderCapabilitiesResolveConfiguredAdapterWithoutEgress() {
        when(aiProvider.structuredOutputCapability(resolved.target()))
                .thenReturn(AiStructuredOutputEnforcement.PROMPT_ONLY);
        when(aiProvider.reasoningCapability(resolved.target()))
                .thenReturn(AiReasoningMode.NATIVE);
        when(aiProvider.contextWindowTokens(resolved.target())).thenReturn(200_000);

        AiProviderCapabilities capabilities =
                service.currentProviderCapabilities(AiFeature.ASSISTANT_CHAT);

        assertEquals(AiStructuredOutputEnforcement.PROMPT_ONLY, capabilities.structuredOutput());
        assertEquals(AiReasoningMode.NATIVE, capabilities.reasoning());
        assertEquals(200_000, capabilities.contextWindowTokens());
        verify(aiProvider, never()).complete(any());
    }

    @Test
    void completeRejectsPromptThatExceedsResolvedProviderContextWindow() {
        when(aiProvider.contextWindowTokens(resolved.target())).thenReturn(65);
        AiInvocation invocation = invocation("Summarize relationship state");

        AiProviderException thrown = assertThrows(
                AiProviderException.class,
                () -> service.complete(invocation));

        assertEquals("AI prompt exceeds the configured model context window", thrown.getMessage());
        assertEquals("context_window", singleAuditMetadata().get("reason"));
        verify(aiProvider, never()).complete(any());
        verify(budgetCoordinator, never()).reserve(eq(ORG_ID), any(AiInvocation.class));
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
        providerReturns(new AiCompletionResult(
                "{{P1}} is ready for follow-up.", 12, 7, "end_turn"));

        AiCompletionOutcome outcome = service.complete(invocation);

        assertEquals("Mina Patel is ready for follow-up.", outcome.text());
        assertEquals(0, outcome.demaskWarnings());
        assertEquals(12, outcome.inputTokens());
        assertEquals(7, outcome.outputTokens());
        ArgumentCaptor<AiCompletionRequest> requestCaptor = ArgumentCaptor.forClass(AiCompletionRequest.class);
        verify(aiProvider).complete(requestCaptor.capture());
        assertEquals(AiOutputMode.TEXT, requestCaptor.getValue().outputMode());
        List<Map<?, ?>> audits = auditMetadata();
        assertEquals("attempt", audits.get(0).get("outcome"));
        assertEquals("success", audits.get(1).get("outcome"));
        assertEquals(12, audits.get(1).get("inputTokens"));
        assertEquals(7, audits.get(1).get("outputTokens"));
        assertEquals("end_turn", audits.get(1).get("stopReason"));
        assertEquals(0, audits.get(1).get("demaskWarnings"));
        assertNoContent(audits.get(0));
        assertNoContent(audits.get(1));
        verify(budgetLease).settle(12, 7);
    }

    @Test
    void complete_exhaustedOrganizationBudgetIsAnExplicitBlockedState() {
        AiInvocation invocation = invocation("Summarize relationship state");
        when(budgetCoordinator.reserve(eq(ORG_ID), same(invocation), anyString()))
                .thenThrow(new AiBudgetExhaustedException());

        AiBudgetExhaustedException exhausted = assertThrows(
                AiBudgetExhaustedException.class,
                () -> service.complete(invocation));

        assertEquals(
                "The organization daily AI token budget is exhausted",
                exhausted.getMessage());
        Map<?, ?> metadata = singleAuditMetadata();
        assertEquals("blocked", metadata.get("outcome"));
        assertEquals("budget_exhausted", metadata.get("reason"));
        verify(aiProvider, never()).complete(any());
    }

    @Test
    void completeStructured_staleAsyncRestrictionEpochSkipsQuotaAndProvider() {
        AiInvocation invocation = invocation("Summarize relationship state");
        long expectedEpoch = restrictionEpoch.current(WORKSPACE_ID);
        restrictionEpoch.bump(WORKSPACE_ID);
        providerReturns(new AiCompletionResult(
                "{\"rationale\":\"Ping {{P1}}.\"}", 20, 8, "end_turn"));

        assertThrows(IllegalStateException.class, () -> restrictionEpoch.runWithExpectedEgressEpoch(
                WORKSPACE_ID,
                expectedEpoch,
                () -> service.completeStructured(
                        invocation, IntroRationaleContent.class, invocationAdmission)));

        List<Map<?, ?>> audits = auditMetadata();
        assertEquals("attempt", audits.get(0).get("outcome"));
        assertEquals("blocked", audits.get(1).get("outcome"));
        assertEquals("restriction_epoch", audits.get(1).get("reason"));
        assertNoContent(audits.get(1));
        verify(invocationAdmission, never()).commitLeaderInvocation();
        verify(providerTransport, never()).run();
        verify(budgetLease).close();
    }

    @Test
    void complete_strictAttemptAuditFailurePreventsProviderEgress() {
        AiInvocation invocation = withImage(invocation("Summarize relationship state"));
        IllegalStateException failure = new IllegalStateException("audit unavailable");
        doThrow(failure).when(auditService).recordStrictIndependentScoped(
            eq("ai.llm.call"), eq("ai_call"), isNull(), eq(WORKSPACE_ID), eq(ORG_ID),
            any(), any(), any());

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
            () -> service.complete(invocation));

        assertEquals(failure, thrown);
        ArgumentCaptor<Object> metadataCaptor = ArgumentCaptor.forClass(Object.class);
        verify(auditService).recordStrictIndependentScoped(
            eq("ai.llm.call"), eq("ai_call"), isNull(), eq(WORKSPACE_ID), eq(ORG_ID),
            any(), any(), metadataCaptor.capture());
        Map<?, ?> metadata = metadataMap(metadataCaptor.getValue());
        assertEquals("attempt", metadata.get("outcome"));
        assertNoContent(metadata);
        verify(aiMediaAdmissionService, never()).acquire(anyInt(), anyList());
        verify(aiProvider, never()).complete(any());
        verify(auditService, never()).recordIndependentScoped(
            any(), any(), any(), any(), any(), any(), any(), any());
        verify(budgetLease).close();
    }

    @Test
    void completeWithImagePropagatesBoundedMediaAndAuditsMetadataOnly() {
        AiInvocation base = invocation("Summarize relationship state");
        AiInputImage image = new AiInputImage(
                "image/jpeg", new byte[] {(byte) 0xff, (byte) 0xd8, (byte) 0xff, 1}, 100, 50);
        AiInvocation invocation = new AiInvocation(
                AiFeature.BUSINESS_CARD_EXTRACTION, base.context(), base.prompt(), List.of(image), 64, 0.2);
        providerReturns(new AiCompletionResult(
                "{{P1}} is ready for follow-up.", 12, 7, "end_turn"));

        service.complete(invocation);

        verify(aiFeatureGate, times(2)).requireAiUsable(AiFeature.BUSINESS_CARD_EXTRACTION);
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
                AiFeature.BUSINESS_CARD_EXTRACTION, base.context(), base.prompt(), List.of(image), 64, 0.2);
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
        verify(budgetLease).close();
    }

    @Test
    void completeWithImageRejectsResolvedTextOnlyProviderBeforeAdapterEgress() {
        AiInvocation base = invocation("Summarize relationship state");
        AiInputImage image = new AiInputImage(
                "image/jpeg", new byte[] {(byte) 0xff, (byte) 0xd8, (byte) 0xff, 1}, 100, 50);
        AiInvocation invocation = new AiInvocation(
                AiFeature.BUSINESS_CARD_EXTRACTION, base.context(), base.prompt(), List.of(image), 64, 0.2);
        resolved = new ResolvedAiProvider("openai_compatible", null, "llama3.3:70b",
                "https://provider.example.test/v1", null, null, null, false, false,
                AiCredentials.of(Map.of()));
        when(aiProviderConfigService.resolveForOrg(ORG_ID, ACTOR_ID)).thenReturn(resolved);

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
        providerReturns(new AiCompletionResult("not json", 10, 3, "end_turn"));

        AiStructuredOutcome<IntroRationaleContent> outcome =
                service.completeStructured(invocation, IntroRationaleContent.class);

        AiStructuredOutcome.Malformed<IntroRationaleContent> malformed = asMalformed(outcome);
        assertEquals(AiStructuredOutcome.REASON_MALFORMED, malformed.reason());
        ArgumentCaptor<AiCompletionRequest> requestCaptor = ArgumentCaptor.forClass(AiCompletionRequest.class);
        verify(aiProvider).complete(requestCaptor.capture());
        assertEquals(AiOutputMode.JSON, requestCaptor.getValue().outputMode());
        assertEquals(1, requestCaptor.getValue().images().size());
        assertMediaLeaseClosesBeforeTerminalAudit();
    }

    @Test
    void completeStructuredWithImageReleasesAdmissionAfterBindingFailure() {
        AiInvocation invocation = withImage(invocation("Summarize relationship state"));
        providerReturns(new AiCompletionResult("{\"rationale\":{}}", 10, 3, "end_turn"));

        AiStructuredOutcome<IntroRationaleContent> outcome =
                service.completeStructured(invocation, IntroRationaleContent.class);

        assertInstanceOf(AiStructuredOutcome.Malformed.class, outcome);
        verify(mediaLease).close();
    }

    @Test
    void complete_adapterThrows_auditsFailureAndPropagates() {
        AiInvocation invocation = invocation("Summarize relationship state");
        AiProviderException expected = new AiProviderException("transport unavailable");
        providerThrows(expected);

        AiProviderException thrown = assertThrows(AiProviderException.class, () -> service.complete(invocation));

        assertEquals(expected, thrown);
        List<Map<?, ?>> audits = auditMetadata();
        assertEquals("attempt", audits.get(0).get("outcome"));
        assertEquals("failure", audits.get(1).get("outcome"));
        assertEquals("provider_exception", audits.get(1).get("reason"));
        assertNoContent(audits.get(1));
        verify(budgetLease).close();
    }

    @Test
    void completeStructured_cleanObject_returnsParsedDemaskedValue() {
        AiInvocation invocation = invocation("Summarize relationship state");
        providerReturns(new AiCompletionResult(
                "{\"rationale\":\"Follow up with {{P1}} soon.\"}", 30, 12, "end_turn"));

        AiStructuredOutcome<IntroRationaleContent> outcome =
                service.completeStructured(invocation, IntroRationaleContent.class);

        AiStructuredOutcome.Parsed<IntroRationaleContent> parsed = asParsed(outcome);
        assertEquals("Follow up with Mina Patel soon.", parsed.value().rationale());
        assertEquals(0, parsed.demaskWarnings());
        assertEquals(30, parsed.inputTokens());
        assertEquals(12, parsed.outputTokens());
        assertEquals("end_turn", parsed.stopReason());
        ArgumentCaptor<AiCompletionRequest> requestCaptor = ArgumentCaptor.forClass(AiCompletionRequest.class);
        verify(aiProvider).complete(requestCaptor.capture());
        assertEquals(AiOutputMode.JSON, requestCaptor.getValue().outputMode());
    }

    @Test
    void completeStructured_nonJsonProse_returnsMalformedOutput() {
        AiInvocation invocation = invocation("Summarize relationship state");
        providerReturns(new AiCompletionResult(
                "Sorry, I cannot produce a structured answer.", 10, 3, "end_turn"));

        AiStructuredOutcome<IntroRationaleContent> outcome =
                service.completeStructured(invocation, IntroRationaleContent.class);

        AiStructuredOutcome.Malformed<IntroRationaleContent> malformed = asMalformed(outcome);
        assertEquals(AiStructuredOutcome.REASON_MALFORMED, malformed.reason());
        assertEquals("end_turn", malformed.stopReason());
    }

    @Test
    void completeStructuredRepairableReturnsBoundedMaskedOutputAndRedactedDiagnostic() throws Exception {
        AiInvocation invocation = invocation("Summarize relationship state");
        String providerOutput = "{\"rationale\":\"Follow up with {{P1}} soon.\"}";
        providerReturns(new AiCompletionResult(
                providerOutput, 10, 3, "end_turn",
                AiStructuredOutputEnforcement.JSON_SCHEMA));
        AiResponseSchema schema = new AiResponseSchema(
                "intro_rationale",
                new ObjectMapper().readTree("{\"type\":\"object\"}"));

        AiStructuredRepairAttempt<IntroRationaleContent> attempt =
                service.completeStructuredRepairable(
                        invocation, IntroRationaleContent.class, output -> false, schema);

        AiStructuredOutcome.Malformed<IntroRationaleContent> malformed =
                asMalformed(attempt.outcome());
        assertEquals(AiStructuredOutcome.REASON_MALFORMED, malformed.reason());
        assertEquals("raw_guard_rejected", attempt.repair().orElseThrow().schemaRule());
        assertEquals(providerOutput, attempt.repair().orElseThrow().offendingOutput());
        ArgumentCaptor<AiCompletionRequest> request = ArgumentCaptor.forClass(AiCompletionRequest.class);
        verify(aiProvider).complete(request.capture());
        assertEquals("intro_rationale", request.getValue().responseSchema().name());
        List<Map<?, ?>> audits = auditMetadata();
        Map<?, ?> terminal = audits.get(1);
        assertEquals("raw_guard_rejected", terminal.get("schemaRule"));
        assertEquals(providerOutput.length(), terminal.get("outputLength"));
        assertEquals(Boolean.TRUE, terminal.get("objectExtracted"));
        assertEquals("json_schema", terminal.get("structuredEnforcement"));
        assertNoContent(terminal);
    }

    @Test
    void completeStructured_truncatedObjectAtTokenLimit_returnsTruncated() {
        AiInvocation invocation = invocation("Summarize relationship state");
        providerReturns(new AiCompletionResult(
                "{\"rationale\":\"Follow up with {{P1}}", 64, 64, "max_tokens"));

        AiStructuredOutcome<IntroRationaleContent> outcome =
                service.completeStructured(invocation, IntroRationaleContent.class);

        AiStructuredOutcome.Malformed<IntroRationaleContent> malformed = asMalformed(outcome);
        assertEquals(AiStructuredOutcome.REASON_TRUNCATED, malformed.reason());
        assertEquals("max_tokens", malformed.stopReason());
    }

    @Test
    void completeStructured_stripsLeadingReasoningPreambleBeforeJson() {
        AiInvocation invocation = invocation("Summarize relationship state");
        providerReturns(new AiCompletionResult(
                "<thought>plan the reply first</thought>{\"rationale\":\"Ping {{P1}}.\"}",
                40, 15, "end_turn"));

        AiStructuredOutcome<IntroRationaleContent> outcome =
                service.completeStructured(invocation, IntroRationaleContent.class);

        AiStructuredOutcome.Parsed<IntroRationaleContent> parsed = asParsed(outcome);
        assertEquals("Ping Mina Patel.", parsed.value().rationale());
    }

    @Test
    void completeStructuredRepairableCapturesAndDemasksReasoningSeparately() throws Exception {
        AiInvocation invocation = reasoningInvocation("Summarize relationship state");
        providerReturns(new AiCompletionResult(
                "<thinking>Compare {{P1}} with the active renewal.</thinking>"
                        + "{\"rationale\":\"Ping {{P1}}.\"}",
                40, 15, "end_turn",
                AiStructuredOutputEnforcement.PROMPT_ONLY,
                "",
                AiReasoningMode.TAGGED));
        AiResponseSchema schema = new AiResponseSchema(
                "intro_rationale",
                new ObjectMapper().readTree("{\"type\":\"object\"}"));

        AiStructuredRepairAttempt<IntroRationaleContent> attempt =
                service.completeStructuredRepairable(
                        invocation,
                        IntroRationaleContent.class,
                        AiRawOutputGuard.PERMIT_ALL,
                        schema);

        assertEquals("Ping Mina Patel.", asParsed(attempt.outcome()).value().rationale());
        assertEquals(
                "Compare Mina Patel with the active renewal.",
                attempt.reasoning().orElseThrow());
        ArgumentCaptor<AiCompletionRequest> request =
                ArgumentCaptor.forClass(AiCompletionRequest.class);
        verify(aiProvider).complete(request.capture());
        assertEquals(AiReasoningMode.TAGGED, request.getValue().reasoningMode());
        assertTrue(request.getValue().systemPrompt().contains("<thinking>"));
    }

    @Test
    void completeStructuredRepairableFailsClosedOnAmbiguousReasoningBoundary() throws Exception {
        AiInvocation invocation = reasoningInvocation("Summarize relationship state");
        providerReturns(new AiCompletionResult(
                "<thinking>tagged plan</thinking>{\"rationale\":\"Ping {{P1}}.\"}",
                40, 15, "end_turn",
                AiStructuredOutputEnforcement.PROMPT_ONLY,
                "native plan",
                AiReasoningMode.NATIVE));
        AiResponseSchema schema = new AiResponseSchema(
                "intro_rationale",
                new ObjectMapper().readTree("{\"type\":\"object\"}"));

        AiStructuredRepairAttempt<IntroRationaleContent> attempt =
                service.completeStructuredRepairable(
                        invocation,
                        IntroRationaleContent.class,
                        AiRawOutputGuard.PERMIT_ALL,
                        schema);

        assertInstanceOf(AiStructuredOutcome.Malformed.class, attempt.outcome());
        assertTrue(attempt.reasoning().isEmpty());
        assertTrue(attempt.repair().isEmpty());
    }

    @Test
    void completeStructuredRepairableFailsClosedOnTaggedReasoningInsideAnswer() throws Exception {
        AiInvocation invocation = reasoningInvocation("Summarize relationship state");
        providerReturns(new AiCompletionResult(
                "{\"rationale\":\"<thinking>private plan</thinking>Ping {{P1}}.\"}",
                40, 15, "end_turn",
                AiStructuredOutputEnforcement.PROMPT_ONLY,
                "",
                AiReasoningMode.TAGGED));
        AiResponseSchema schema = new AiResponseSchema(
                "intro", new ObjectMapper().readTree("{\"type\":\"object\"}"));

        AiStructuredRepairAttempt<IntroRationaleContent> attempt =
                service.completeStructuredRepairable(
                        invocation,
                        IntroRationaleContent.class,
                        AiRawOutputGuard.PERMIT_ALL,
                        schema);

        assertInstanceOf(AiStructuredOutcome.Malformed.class, attempt.outcome());
        assertTrue(attempt.reasoning().isEmpty());
        assertTrue(attempt.repair().isEmpty());
    }

    @Test
    void completeStructuredRepairableFailsClosedOnReasoningTagInsideNativeAnswer() throws Exception {
        AiInvocation invocation = reasoningInvocation("Summarize relationship state");
        providerReturns(new AiCompletionResult(
                "{\"rationale\":\"<thinking>private plan</thinking>Ping {{P1}}.\"}",
                40, 15, "end_turn",
                AiStructuredOutputEnforcement.PROMPT_ONLY,
                "native plan",
                AiReasoningMode.NATIVE));
        AiResponseSchema schema = new AiResponseSchema(
                "intro", new ObjectMapper().readTree("{\"type\":\"object\"}"));

        AiStructuredRepairAttempt<IntroRationaleContent> attempt =
                service.completeStructuredRepairable(
                        invocation,
                        IntroRationaleContent.class,
                        AiRawOutputGuard.PERMIT_ALL,
                        schema);

        assertInstanceOf(AiStructuredOutcome.Malformed.class, attempt.outcome());
        assertTrue(attempt.reasoning().isEmpty());
        assertTrue(attempt.repair().isEmpty());
    }

    @Test
    void structuredRepairAttemptToStringRedactsReasoning() {
        AiStructuredRepairAttempt<IntroRationaleContent> attempt =
                new AiStructuredRepairAttempt<>(
                        new AiStructuredOutcome.Malformed<>("malformed", 1, 2, "stop"),
                        Optional.empty(),
                        Optional.of("PRIVATE_REASONING"));

        assertFalse(attempt.toString().contains("PRIVATE_REASONING"));
        assertTrue(attempt.toString().contains("reasoning=<redacted>"));
    }

    @Test
    void completeStructuredRepairableRejectsReasoningWithDirectIdentifierLeak() throws Exception {
        AiInvocation invocation = reasoningInvocation("Summarize relationship state");
        providerReturns(new AiCompletionResult(
                "{\"rationale\":\"Ping {{P1}}.\"}",
                40, 15, "end_turn",
                AiStructuredOutputEnforcement.PROMPT_ONLY,
                "Contact ada@example.com before continuing.",
                AiReasoningMode.NATIVE));
        AiResponseSchema schema = new AiResponseSchema(
                "intro_rationale",
                new ObjectMapper().readTree("{\"type\":\"object\"}"));

        AiStructuredRepairAttempt<IntroRationaleContent> attempt =
                service.completeStructuredRepairable(
                        invocation,
                        IntroRationaleContent.class,
                        AiRawOutputGuard.PERMIT_ALL,
                        schema);

        assertInstanceOf(AiStructuredOutcome.Malformed.class, attempt.outcome());
        assertTrue(attempt.reasoning().isEmpty());
    }

    @Test
    void completeStructured_success_emitsAttemptAndSuccessAuditWithoutContent() {
        AiInvocation invocation = invocation("Summarize relationship state");
        providerReturns(new AiCompletionResult(
                "{\"rationale\":\"Ping {{P1}}.\"}", 20, 8, "end_turn"));

        service.completeStructured(invocation, IntroRationaleContent.class);

        List<Map<?, ?>> audits = auditMetadata();
        assertEquals("attempt", audits.get(0).get("outcome"));
        assertEquals("success", audits.get(1).get("outcome"));
        assertEquals(Boolean.TRUE, audits.get(1).get("structured"));
        assertEquals("parsed", audits.get(1).get("parseOutcome"));
        assertNoContent(audits.get(0));
        assertNoContent(audits.get(1));
    }

    @Test
    void completeStructuredWithAdmissionCommitsQuotaImmediatelyBeforeProviderAttempt() {
        AiInvocation invocation = invocation("Summarize relationship state");
        providerReturns(new AiCompletionResult(
                "{\"rationale\":\"Ping {{P1}}.\"}", 20, 8, "end_turn"));

        service.completeStructured(invocation, IntroRationaleContent.class, invocationAdmission);

        InOrder order = inOrder(auditService, invocationAdmission, providerTransport);
        order.verify(auditService).recordStrictIndependentScoped(
                eq("ai.llm.call"), eq("ai_call"), isNull(), eq(WORKSPACE_ID), eq(ORG_ID),
                any(), any(), any());
        order.verify(invocationAdmission).commitLeaderInvocation();
        order.verify(providerTransport).run();
    }

    @Test
    void completeStructuredRepairableCommitsDirectQuotaImmediatelyBeforeProviderAttempt() throws Exception {
        AiInvocation invocation = invocation("Summarize relationship state");
        providerReturns(new AiCompletionResult(
                "{\"rationale\":\"Ping {{P1}}.\"}", 20, 8, "end_turn"));
        AiResponseSchema schema = new AiResponseSchema(
                "intro_rationale",
                new ObjectMapper().readTree("{\"type\":\"object\"}"));

        service.completeStructuredRepairable(
                invocation, IntroRationaleContent.class,
                AiRawOutputGuard.PERMIT_ALL, schema, directAdmission);

        InOrder order = inOrder(auditService, directAdmission, providerTransport);
        order.verify(auditService).recordStrictIndependentScoped(
                eq("ai.llm.call"), eq("ai_call"), isNull(), eq(WORKSPACE_ID), eq(ORG_ID),
                any(), any(), any());
        order.verify(directAdmission).commitInvocation();
        order.verify(providerTransport).run();
    }

    @Test
    void repairableProviderAttemptGuardRunsBeforeEveryProviderAttempt() throws Exception {
        AiInvocation invocation = invocation("Summarize relationship state");
        when(aiInvocationAdmissionService.acquireDirect()).thenReturn(fallbackAdmission);
        when(aiProvider.complete(any(AiCompletionRequest.class))).thenAnswer(call -> {
            AiCompletionRequest request = call.getArgument(0);
            try {
                request.providerAttemptExecutor().execute(() -> {
                    providerTransport.run();
                    throw new AiProviderRequestRejectedException("provider", 400);
                });
            } catch (AiProviderRequestRejectedException exception) {
                assertEquals("provider invocation failed with status 400", exception.getMessage());
            }
            request.providerAttemptExecutor().execute(() -> {
                providerTransport.run();
                return "fallback response";
            });
            return new AiCompletionResult(
                    "{\"rationale\":\"Ping {{P1}}.\"}", 20, 8, "end_turn",
                    AiStructuredOutputEnforcement.JSON_OBJECT);
        });
        AiResponseSchema schema = new AiResponseSchema(
                "intro_rationale",
                new ObjectMapper().readTree("{\"type\":\"object\"}"));

        service.completeStructuredRepairable(
                invocation, IntroRationaleContent.class,
                AiRawOutputGuard.PERMIT_ALL, schema, directAdmission, providerAttemptGuard);

        InOrder order = inOrder(
                providerAttemptGuard, directAdmission, fallbackAdmission, providerTransport);
        order.verify(providerAttemptGuard).run();
        order.verify(directAdmission).commitInvocation();
        order.verify(providerTransport).run();
        order.verify(providerAttemptGuard).run();
        order.verify(fallbackAdmission).commitInvocation();
        order.verify(providerTransport).run();
    }

    @Test
    void providerConfigurationChangeRefusesStaleResolvedCredentialsAtEgress() {
        ResolvedAiProvider changed = new ResolvedAiProvider(
                "openai_compatible", null, "gemma-4-31b-it",
                "https://api.example.test/v1", null, null, null,
                false, true, AiCredentials.of(Map.of("apiKey", "NEW_KEY")));
        when(aiProviderConfigService.resolveForOrg(ORG_ID, ACTOR_ID))
                .thenReturn(resolved, changed);
        providerReturns(new AiCompletionResult(
                "{\"rationale\":\"done\"}", 20, 8, "stop"));

        AiProviderException exception = assertThrows(
                AiProviderException.class,
                () -> service.completeStructured(
                        invocation("Summarize relationship state"),
                        IntroRationaleContent.class));

        assertEquals("AI provider configuration changed before egress", exception.getMessage());
        verify(providerTransport, never()).run();
    }

    @Test
    void structuredFallbackGetsItsOwnQuotaCommitAuditAndEgressFence() {
        AiInvocation invocation = invocation("Summarize relationship state");
        when(aiInvocationAdmissionService.acquireDirect()).thenReturn(fallbackAdmission);
        when(aiProvider.complete(any(AiCompletionRequest.class))).thenAnswer(call -> {
            AiCompletionRequest request = call.getArgument(0);
            try {
                request.providerAttemptExecutor().execute(() -> {
                    providerTransport.run();
                    throw new AiProviderRequestRejectedException("provider", 400);
                });
            } catch (AiProviderRequestRejectedException exception) {
                assertEquals("provider invocation failed with status 400", exception.getMessage());
            }
            request.providerAttemptExecutor().execute(() -> {
                providerTransport.run();
                return "fallback response";
            });
            return new AiCompletionResult(
                    "{\"rationale\":\"Ping {{P1}}.\"}", 20, 8, "end_turn",
                    AiStructuredOutputEnforcement.JSON_OBJECT);
        });

        service.completeStructured(
                invocation, IntroRationaleContent.class, invocationAdmission);

        verify(invocationAdmission).commitLeaderInvocation();
        verify(fallbackAdmission).commitInvocation();
        verify(fallbackAdmission).close();
        verify(providerTransport, times(2)).run();
        verify(budgetCoordinator, times(2)).reserve(
                eq(ORG_ID), same(invocation), anyString());
        verify(budgetLease).close();
        verify(fallbackBudgetLease).settle(20, 8);
        verify(auditService, times(2)).recordStrictIndependentScoped(
                eq("ai.llm.call"), eq("ai_call"), isNull(), eq(WORKSPACE_ID), eq(ORG_ID),
                any(), any(), any());
        verify(auditService, times(2)).recordIndependentScoped(
                eq("ai.llm.call"), eq("ai_call"), isNull(), eq(WORKSPACE_ID), eq(ORG_ID),
                any(), any(), any());
    }

    @Test
    void restrictionEpochIsRecheckedBeforeFallbackProviderEgress() {
        AiInvocation invocation = invocation("Summarize relationship state");
        long expectedEpoch = restrictionEpoch.current(WORKSPACE_ID);
        when(aiInvocationAdmissionService.acquireDirect()).thenReturn(fallbackAdmission);
        when(aiProvider.complete(any(AiCompletionRequest.class))).thenAnswer(call -> {
            AiCompletionRequest request = call.getArgument(0);
            try {
                request.providerAttemptExecutor().execute(() -> {
                    providerTransport.run();
                    throw new AiProviderRequestRejectedException("provider", 400);
                });
            } catch (AiProviderRequestRejectedException exception) {
                assertEquals("provider invocation failed with status 400", exception.getMessage());
            }
            restrictionEpoch.bump(WORKSPACE_ID);
            request.providerAttemptExecutor().execute(() -> {
                providerTransport.run();
                return "forbidden fallback";
            });
            throw new IllegalStateException("Restriction fence did not reject fallback egress");
        });

        assertThrows(IllegalStateException.class, () ->
                restrictionEpoch.runWithExpectedEgressEpoch(
                        WORKSPACE_ID,
                        expectedEpoch,
                        () -> service.completeStructured(
                                invocation, IntroRationaleContent.class, invocationAdmission)));

        verify(invocationAdmission).commitLeaderInvocation();
        verify(fallbackAdmission, never()).commitInvocation();
        verify(fallbackAdmission).close();
        verify(providerTransport).run();
    }

    @Test
    void featureGateIsRecheckedBeforeFallbackProviderEgress() {
        AiInvocation invocation = invocation("Summarize relationship state");
        ForbiddenException disabled = new ForbiddenException("AI features are not available");
        doNothing().doNothing().doThrow(disabled)
                .when(aiFeatureGate).requireAiUsable(FEATURE);
        when(aiInvocationAdmissionService.acquireDirect()).thenReturn(fallbackAdmission);
        when(aiProvider.complete(any(AiCompletionRequest.class))).thenAnswer(call -> {
            AiCompletionRequest request = call.getArgument(0);
            try {
                request.providerAttemptExecutor().execute(() -> {
                    providerTransport.run();
                    throw new AiProviderRequestRejectedException("provider", 400);
                });
            } catch (AiProviderRequestRejectedException exception) {
                assertEquals("provider invocation failed with status 400", exception.getMessage());
            }
            request.providerAttemptExecutor().execute(() -> {
                providerTransport.run();
                return "forbidden fallback";
            });
            throw new IllegalStateException("Feature gate did not reject fallback egress");
        });

        ForbiddenException thrown = assertThrows(
                ForbiddenException.class,
                () -> service.completeStructured(
                        invocation, IntroRationaleContent.class, invocationAdmission));

        assertEquals(disabled, thrown);
        verify(invocationAdmission).commitLeaderInvocation();
        verify(fallbackAdmission, never()).commitInvocation();
        verify(fallbackAdmission).close();
        verify(providerTransport).run();
        verify(budgetLease).close();
        verify(fallbackBudgetLease).close();
    }

    @Test
    void strictFallbackAttemptAuditFailureReleasesReservedQuota() {
        AiInvocation invocation = invocation("Summarize relationship state");
        IllegalStateException failure = new IllegalStateException("audit unavailable");
        when(aiInvocationAdmissionService.acquireDirect()).thenReturn(fallbackAdmission);
        doNothing().doThrow(failure).when(auditService).recordStrictIndependentScoped(
                eq("ai.llm.call"), eq("ai_call"), isNull(), eq(WORKSPACE_ID), eq(ORG_ID),
                any(), any(), any());
        when(aiProvider.complete(any(AiCompletionRequest.class))).thenAnswer(call -> {
            AiCompletionRequest request = call.getArgument(0);
            try {
                request.providerAttemptExecutor().execute(() -> {
                    providerTransport.run();
                    throw new AiProviderRequestRejectedException("provider", 400);
                });
            } catch (AiProviderRequestRejectedException exception) {
                assertEquals("provider invocation failed with status 400", exception.getMessage());
            }
            request.providerAttemptExecutor().execute(() -> {
                providerTransport.run();
                return "fallback response";
            });
            throw new IllegalStateException("Strict fallback audit failure was not propagated");
        });

        IllegalStateException thrown = assertThrows(
                IllegalStateException.class,
                () -> service.completeStructured(
                        invocation, IntroRationaleContent.class, invocationAdmission));

        assertEquals(failure, thrown);
        verify(fallbackAdmission, never()).commitInvocation();
        verify(fallbackAdmission).close();
        verify(providerTransport).run();
    }

    @Test
    void completeStructuredWithAdmissionDoesNotCommitQuotaWhenGateDenies() {
        doThrow(new ForbiddenException("AI features are not available"))
                .when(aiFeatureGate).requireAiUsable(AiFeature.DEAL_BRIEF);
        AiInvocation invocation = invocation("Summarize relationship state");

        assertThrows(ForbiddenException.class, () ->
                service.completeStructured(invocation, IntroRationaleContent.class, invocationAdmission));

        verify(invocationAdmission, never()).commitLeaderInvocation();
        verify(aiProviderConfigService, never()).resolveForOrg(ORG_ID, ACTOR_ID);
        verify(aiProvider, never()).complete(any());
    }

    private void providerReturns(AiCompletionResult result) {
        when(aiProvider.complete(any(AiCompletionRequest.class))).thenAnswer(invocation -> {
            AiCompletionRequest request = invocation.getArgument(0);
            request.providerAttemptExecutor().execute(() -> {
                providerTransport.run();
                return "provider response";
            });
            return result;
        });
    }

    private void providerThrows(RuntimeException failure) {
        when(aiProvider.complete(any(AiCompletionRequest.class))).thenAnswer(invocation -> {
            AiCompletionRequest request = invocation.getArgument(0);
            request.providerAttemptExecutor().execute(() -> {
                providerTransport.run();
                throw failure;
            });
            throw new IllegalStateException("Provider failure was not propagated");
        });
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

    private AiInvocation reasoningInvocation(String maskedPromptText) {
        AiInvocation invocation = invocation(maskedPromptText);
        return new AiInvocation(
                invocation.feature(),
                invocation.context(),
                invocation.prompt(),
                invocation.maxTokens(),
                invocation.temperature(),
                true);
    }

    private static AiInvocation withImage(AiInvocation invocation) {
        AiInputImage image = new AiInputImage(
                "image/jpeg", new byte[] {(byte) 0xff, (byte) 0xd8, (byte) 0xff, 1}, 100, 50);
        return new AiInvocation(
                AiFeature.BUSINESS_CARD_EXTRACTION, invocation.context(), invocation.prompt(), List.of(image),
                invocation.maxTokens(), invocation.temperature());
    }

    private void assertMediaLeaseClosesBeforeTerminalAudit() {
        InOrder order = inOrder(auditService, aiProvider, mediaLease);
        order.verify(auditService).recordStrictIndependentScoped(
                eq("ai.llm.call"), eq("ai_call"), isNull(), eq(WORKSPACE_ID), eq(ORG_ID),
                any(), any(), any());
        order.verify(aiProvider).complete(any());
        order.verify(mediaLease).close();
        order.verify(auditService).recordIndependentScoped(
                eq("ai.llm.call"), eq("ai_call"), isNull(), eq(WORKSPACE_ID), eq(ORG_ID),
                any(), any(), any());
    }

    private Map<?, ?> singleAuditMetadata() {
        ArgumentCaptor<Object> metadataCaptor = ArgumentCaptor.forClass(Object.class);
        verify(auditService).recordIndependentScoped(eq("ai.llm.call"), eq("ai_call"), isNull(),
                eq(WORKSPACE_ID), eq(ORG_ID), any(), any(), metadataCaptor.capture());
        return metadataMap(metadataCaptor.getValue());
    }

    private List<Map<?, ?>> auditMetadata() {
        ArgumentCaptor<Object> attemptCaptor = ArgumentCaptor.forClass(Object.class);
        verify(auditService).recordStrictIndependentScoped(eq("ai.llm.call"), eq("ai_call"), isNull(),
                eq(WORKSPACE_ID), eq(ORG_ID), any(), any(), attemptCaptor.capture());
        return List.of(metadataMap(attemptCaptor.getValue()), singleAuditMetadata());
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
