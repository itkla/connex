package ooo.klae.connex.backend.ai;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.ai.masking.AiJson;
import ooo.klae.connex.backend.ai.masking.AiGeneratedContentScreen;
import ooo.klae.connex.backend.ai.masking.CompletionNormalizer;
import ooo.klae.connex.backend.ai.masking.Demasker;
import ooo.klae.connex.backend.ai.masking.MaskedMessage;
import ooo.klae.connex.backend.ai.masking.MaskedPrompt;
import ooo.klae.connex.backend.ai.masking.MaskingLeakException;
import ooo.klae.connex.backend.ai.masking.OutboundLeakScan;
import ooo.klae.connex.backend.ai.egress.AiRequestDeadline;
import ooo.klae.connex.backend.ai.provider.AiCompletionRequest;
import ooo.klae.connex.backend.ai.provider.AiCompletionResult;
import ooo.klae.connex.backend.ai.provider.AiInputImage;
import ooo.klae.connex.backend.ai.provider.AiProviderRequestRejectedException;
import ooo.klae.connex.backend.ai.provider.AiImageInputUnsupportedException;
import ooo.klae.connex.backend.ai.provider.AiInvocationProtocol;
import ooo.klae.connex.backend.ai.provider.AiMessage;
import ooo.klae.connex.backend.ai.provider.AiNativeToolRequest;
import ooo.klae.connex.backend.ai.provider.AiOutputMode;
import ooo.klae.connex.backend.ai.provider.AiProviderAttemptBlockedException;
import ooo.klae.connex.backend.ai.provider.AiProviderAttemptExecutor;
import ooo.klae.connex.backend.ai.provider.AiProviderCallerDeadlineExceededException;
import ooo.klae.connex.backend.ai.provider.AiProviderException;
import ooo.klae.connex.backend.ai.provider.AiProvider;
import ooo.klae.connex.backend.ai.provider.AiProviderCapabilities;
import ooo.klae.connex.backend.ai.provider.AiProviderRouter;
import ooo.klae.connex.backend.ai.provider.AiReasoningMode;
import ooo.klae.connex.backend.ai.provider.AiResponseSchema;
import ooo.klae.connex.backend.ai.provider.AiStructuredOutputEnforcement;
import ooo.klae.connex.backend.ai.provider.AiToolCall;
import ooo.klae.connex.backend.ai.provider.AiToolCallingMode;
import ooo.klae.connex.backend.ai.provider.AiToolDefinition;
import ooo.klae.connex.backend.ai.provider.AiToolExchange;
import ooo.klae.connex.backend.ai.provider.ResolvedAiProvider;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.exceptions.AiBudgetExhaustedException;
import ooo.klae.connex.backend.exceptions.TooManyRequestsException;
import ooo.klae.connex.backend.services.AiProviderConfigService;
import ooo.klae.connex.backend.services.AuditService;
import ooo.klae.connex.backend.services.WorkspaceService;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Single outbound LLM invocation path for AI features. This service enforces feature gating,
 * provider credential resolution, final leak scanning, provider invocation, demasking, and
 * metadata-only append-only audit for every attempted or blocked call. Features may request either
 * a demasked text completion ({@link #complete}), a demasked type-bound structured completion
 * ({@link #completeStructured}), or one native assistant-tool step; all route through the same
 * audited core.
 */
@Service
@RequiredArgsConstructor
public class AiInvocationService {
    private static final String AUDIT_ACTION = "ai.llm.call";
    private static final String AUDIT_ENTITY_TYPE = "ai_call";
    private static final String AUDIT_OUTCOME_ATTEMPT = "attempt";
    private static final String UNKNOWN_TARGET = "unresolved";
    private static final String PARSE_OUTCOME_PARSED = "parsed";
    private static final Set<String> TRUNCATION_STOP_REASONS = Set.of("length", "max_tokens");
    private static final int MAX_REASONING_CHARS = 16_000;
    private static final String TAGGED_REASONING_INSTRUCTION = """
            Before the final response, reason inside exactly one <thinking>...</thinking> block. \
            After the closing tag, emit only the requested final response shape. Never put answer \
            text inside the thinking block and never put thinking outside it.""";
    private static final Runnable NO_INVOCATION_COMMITMENT = () -> {};

    private final AiFeatureGate aiFeatureGate;
    private final AiInvocationAdmissionService aiInvocationAdmissionService;
    private final AiMediaAdmissionService aiMediaAdmissionService;
    private final AiProviderConfigService aiProviderConfigService;
    private final AiProviderRouter aiProviderRouter;
    private final AiRestrictionEpoch aiRestrictionEpoch;
    private final WorkspaceService workspaceService;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;
    private final AiOrganizationBudgetCoordinator budgetCoordinator;
    private final Clock clock;

    /**
     * Resolves adapter-declared capabilities for the current organization provider configuration.
     * @param feature feature whose provider gate must be satisfied
     * @return exact configured-target capabilities without performing provider egress
     */
    public AiProviderCapabilities currentProviderCapabilities(AiFeature feature) {
        Objects.requireNonNull(feature, "feature");
        aiFeatureGate.requireAiUsable(feature);
        int orgId = workspaceService.getCurrentOrgId();
        ResolvedAiProvider resolved = aiProviderConfigService.resolveForOrg(
                orgId, workspaceService.getCurrentUserId());
        AiProvider adapter = aiProviderRouter.adapterFor(resolved.provider());
        return new AiProviderCapabilities(
                adapter.structuredOutputCapability(resolved.target()),
                adapter.reasoningCapability(resolved.target()),
                adapter.contextWindowTokens(resolved.target()),
                adapter.toolCallingCapability(resolved.target()),
                adapter.nativeToolReasoningCapability(resolved.target()));
    }

    /**
     * Measures the exact UTF-8 bytes used by Connex's serialized provider-neutral prompt envelope.
     * @param prompt masked prompt whose fixed envelope is measured
     * @param responseSchema structured response schema included in the envelope
     * @param reasoningMode provider reasoning protocol included in the system prompt
     * @return exact serialized UTF-8 byte count
     */
    public int serializedPromptBytes(
            MaskedPrompt prompt,
            AiResponseSchema responseSchema,
            AiReasoningMode reasoningMode) {
        return serializedPromptBytes(prompt, responseSchema, reasoningMode, null);
    }

    /**
     * Measures the provider-neutral envelope including native definitions and completed exchanges.
     * @param prompt masked prompt whose fixed envelope is measured
     * @param responseSchema structured response schema included in the envelope
     * @param reasoningMode provider reasoning protocol included in the system prompt
     * @param nativeTools optional native tool protocol state
     * @return exact serialized UTF-8 byte count
     */
    public int serializedPromptBytes(
            MaskedPrompt prompt,
            AiResponseSchema responseSchema,
            AiReasoningMode reasoningMode,
            AiNativeToolRequest nativeTools) {
        Objects.requireNonNull(prompt, "prompt");
        Objects.requireNonNull(reasoningMode, "reasoningMode");
        return serializeProviderInput(prompt, responseSchema, reasoningMode, nativeTools)
                .getBytes(StandardCharsets.UTF_8).length;
    }

    /**
     * Completes a masked AI invocation and returns demasked text.
     * @param invocation masked invocation request
     * @return demasked completion outcome
     */
    public AiCompletionOutcome complete(AiInvocation invocation) {
        try (RawInvocation raw = invokeRaw(
                invocation, AiOutputMode.TEXT, null,
                NO_INVOCATION_COMMITMENT, NO_INVOCATION_COMMITMENT)) {
            AiCompletionResult result = raw.result();
            Demasker.DemaskResult demasked = Demasker.demask(
                    CompletionNormalizer.stripReasoning(result.text()), invocation.context());
            raw.close();
            emitAudit(raw, invocation, "success", result.inputTokens(), result.outputTokens(),
                    result.stopReason(), demasked.warnings(), null, false, null);
            return new AiCompletionOutcome(demasked.text(), demasked.warnings(),
                    result.inputTokens(), result.outputTokens(), result.stopReason());
        }
    }

    /**
     * Completes a masked direct invocation and commits its organization quota immediately before
     * provider egress.
     * @param invocation masked invocation request
     * @param admission active direct invocation admission
     * @return demasked completion outcome
     */
    public AiCompletionOutcome complete(
            AiInvocation invocation,
            AiInvocationAdmissionService.DirectAdmission admission) {
        Objects.requireNonNull(admission, "admission");
        return complete(invocation, admission, NO_INVOCATION_COMMITMENT);
    }

    private AiCompletionOutcome complete(
            AiInvocation invocation,
            AiInvocationAdmissionService.DirectAdmission admission,
            Runnable providerAttemptGuard) {
        try (RawInvocation raw = invokeRaw(
                invocation, AiOutputMode.TEXT, null,
                admission::commitInvocation,
                Objects.requireNonNull(providerAttemptGuard, "providerAttemptGuard"))) {
            AiCompletionResult result = raw.result();
            Demasker.DemaskResult demasked = Demasker.demask(
                    CompletionNormalizer.stripReasoning(result.text()), invocation.context());
            raw.close();
            emitAudit(raw, invocation, "success", result.inputTokens(), result.outputTokens(),
                    result.stopReason(), demasked.warnings(), null, false, null);
            return new AiCompletionOutcome(demasked.text(), demasked.warnings(),
                    result.inputTokens(), result.outputTokens(), result.stopReason());
        }
    }

    /**
     * Completes a masked direct invocation under the restriction epoch captured before its inputs
     * were assembled.
     * @param invocation masked invocation request
     * @param admission active direct invocation admission
     * @param expectedRestrictionEpoch restriction epoch captured before input assembly
     * @return demasked completion outcome
     */
    public AiCompletionOutcome complete(
            AiInvocation invocation,
            AiInvocationAdmissionService.DirectAdmission admission,
            long expectedRestrictionEpoch) {
        return complete(
                invocation, admission, expectedRestrictionEpoch,
                NO_INVOCATION_COMMITMENT);
    }

    /**
     * Completes a masked direct invocation under its restriction epoch and a caller-owned access
     * check immediately before every provider send.
     * @param invocation masked invocation request
     * @param admission active direct invocation admission
     * @param expectedRestrictionEpoch restriction epoch captured before input assembly
     * @param providerAttemptGuard access check run immediately before each provider attempt
     * @return demasked completion outcome
     */
    public AiCompletionOutcome complete(
            AiInvocation invocation,
            AiInvocationAdmissionService.DirectAdmission admission,
            long expectedRestrictionEpoch,
            Runnable providerAttemptGuard) {
        Objects.requireNonNull(admission, "admission");
        Objects.requireNonNull(providerAttemptGuard, "providerAttemptGuard");
        AtomicReference<AiCompletionOutcome> outcomeReference = new AtomicReference<>();
        aiRestrictionEpoch.runWithExpectedEgressEpoch(
                workspaceService.getCurrentWorkspaceId(),
                expectedRestrictionEpoch,
                () -> outcomeReference.set(complete(
                        invocation, admission, providerAttemptGuard)));
        return Objects.requireNonNull(outcomeReference.get(), "completion outcome");
    }

    /**
     * Completes a masked AI invocation, parses a single JSON object from the provider output, demasks
     * it, and binds it to the requested content type. Fails closed to {@link AiStructuredOutcome.Malformed}
     * (carrying no raw provider text) when no usable object is present or it cannot bind.
     * @param invocation masked invocation request
     * @param type content type to bind the parsed object to
     * @param <T> content type
     * @return parsed or malformed structured outcome
     */
    public <T> AiStructuredOutcome<T> completeStructured(AiInvocation invocation, Class<T> type) {
        return completeStructuredAttemptWithCommitment(
                invocation, type, AiRawOutputGuard.PERMIT_ALL, null,
                NO_INVOCATION_COMMITMENT, NO_INVOCATION_COMMITMENT, false).outcome();
    }

    /**
     * Completes a structured invocation and commits the leader's reserved organization quota only
     * immediately before the provider attempt.
     * @param invocation masked invocation request
     * @param type content type to bind the parsed object to
     * @param admission active cache-miss leader admission
     * @param <T> content type
     * @return parsed or malformed structured outcome
     */
    public <T> AiStructuredOutcome<T> completeStructured(
            AiInvocation invocation,
            Class<T> type,
            AiInvocationAdmissionService.Admission admission) {
        Objects.requireNonNull(admission, "admission");
        return completeStructuredAttemptWithCommitment(
                invocation, type, AiRawOutputGuard.PERMIT_ALL, null,
                admission::commitLeaderInvocation, NO_INVOCATION_COMMITMENT, false).outcome();
    }

    /**
     * As {@link #completeStructured(AiInvocation, Class)}, but validates the raw, still-masked output
     * with {@code guard} before demasking. A rejected output fails closed to
     * {@link AiStructuredOutcome.Malformed}.
     * @param invocation masked invocation request
     * @param type content type to bind the parsed object to
     * @param guard pre-demask validator run on the masked output
     * @param <T> content type
     * @return parsed or malformed structured outcome
     */
    public <T> AiStructuredOutcome<T> completeStructured(
            AiInvocation invocation, Class<T> type, AiRawOutputGuard guard) {
        return completeStructuredAttemptWithCommitment(
                invocation, type, guard, null,
                NO_INVOCATION_COMMITMENT, NO_INVOCATION_COMMITMENT, false).outcome();
    }

    /**
     * Completes a guarded structured invocation and commits the leader's reserved organization
     * quota only immediately before the provider attempt.
     * @param invocation masked invocation request
     * @param type content type to bind the parsed object to
     * @param guard pre-demask validator run on the masked output
     * @param admission active cache-miss leader admission
     * @param <T> content type
     * @return parsed or malformed structured outcome
     */
    public <T> AiStructuredOutcome<T> completeStructured(
            AiInvocation invocation,
            Class<T> type,
            AiRawOutputGuard guard,
            AiInvocationAdmissionService.Admission admission) {
        Objects.requireNonNull(admission, "admission");
        return completeStructuredAttemptWithCommitment(
                invocation, type, guard, null,
                admission::commitLeaderInvocation, NO_INVOCATION_COMMITMENT, false).outcome();
    }

    /**
     * Completes a schema-constrained invocation and retains one bounded masked repair payload when
     * validation fails. The payload is ephemeral and must never be logged or persisted.
     * @param invocation masked invocation request
     * @param type content type to bind the parsed object to
     * @param guard pre-demask validator run on the masked output
     * @param responseSchema provider-neutral response schema
     * @param <T> content type
     * @return parsed outcome or malformed outcome with an optional repair payload
     */
    public <T> AiStructuredRepairAttempt<T> completeStructuredRepairable(
            AiInvocation invocation,
            Class<T> type,
            AiRawOutputGuard guard,
            AiResponseSchema responseSchema) {
        return completeStructuredAttemptWithCommitment(
                invocation, type, guard, Objects.requireNonNull(responseSchema, "responseSchema"),
                NO_INVOCATION_COMMITMENT, NO_INVOCATION_COMMITMENT, true);
    }

    /**
     * Completes a repairable structured invocation and commits an interactive organization-quota
     * reservation immediately before provider egress.
     * @param invocation masked invocation request
     * @param type content type to bind the parsed object to
     * @param guard pre-demask validator run on the masked output
     * @param responseSchema provider-neutral response schema
     * @param admission active direct invocation admission
     * @param <T> content type
     * @return parsed outcome or malformed outcome with an optional repair payload
     */
    public <T> AiStructuredRepairAttempt<T> completeStructuredRepairable(
            AiInvocation invocation,
            Class<T> type,
            AiRawOutputGuard guard,
            AiResponseSchema responseSchema,
            AiInvocationAdmissionService.DirectAdmission admission) {
        Objects.requireNonNull(admission, "admission");
        return completeStructuredAttemptWithCommitment(
                invocation, type, guard, Objects.requireNonNull(responseSchema, "responseSchema"),
                admission::commitInvocation, NO_INVOCATION_COMMITMENT, true);
    }

    /**
     * Completes a repairable invocation with a caller-owned access check before every provider send.
     * @param invocation masked invocation request
     * @param type content type to bind the parsed object to
     * @param guard pre-demask validator run on the masked output
     * @param responseSchema provider-neutral response schema
     * @param admission active direct invocation admission
     * @param providerAttemptGuard access check run immediately before each provider attempt
     * @param <T> content type
     * @return parsed outcome or malformed outcome with an optional repair payload
     */
    public <T> AiStructuredRepairAttempt<T> completeStructuredRepairable(
            AiInvocation invocation,
            Class<T> type,
            AiRawOutputGuard guard,
            AiResponseSchema responseSchema,
            AiInvocationAdmissionService.DirectAdmission admission,
            Runnable providerAttemptGuard) {
        Objects.requireNonNull(admission, "admission");
        return completeStructuredAttemptWithCommitment(
                invocation, type, guard, Objects.requireNonNull(responseSchema, "responseSchema"),
                admission::commitInvocation,
                Objects.requireNonNull(providerAttemptGuard, "providerAttemptGuard"), true);
    }

    /**
     * Completes one native function-tool step or one structured terminal answer through the same
     * gate, masking, admission, budget, deadline, and audit boundary as every other model call.
     * Malformed native calls fail honestly without executing or replaying provider content.
     * @param invocation masked invocation request
     * @param type terminal content type
     * @param toolGuard raw synthetic tool-step guard
     * @param contentGuard raw terminal-content guard
     * @param responseSchema terminal-content response schema
     * @param nativeTools static definitions and ephemeral completed exchanges
     * @param admission active direct invocation admission
     * @param providerAttemptGuard access check run immediately before each provider attempt
     * @param <T> terminal content type
     * @return validated native tool call, structured content attempt, or malformed call
     */
    public <T> AiNativeToolCompletion<T> completeNativeToolsRepairable(
            AiInvocation invocation,
            Class<T> type,
            AiRawOutputGuard toolGuard,
            AiRawOutputGuard contentGuard,
            AiResponseSchema responseSchema,
            AiNativeToolRequest nativeTools,
            AiInvocationAdmissionService.DirectAdmission admission,
            Runnable providerAttemptGuard) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(toolGuard, "toolGuard");
        Objects.requireNonNull(contentGuard, "contentGuard");
        Objects.requireNonNull(responseSchema, "responseSchema");
        Objects.requireNonNull(nativeTools, "nativeTools");
        Objects.requireNonNull(admission, "admission");
        Objects.requireNonNull(providerAttemptGuard, "providerAttemptGuard");
        try (RawInvocation raw = invokeRaw(
                invocation, AiOutputMode.JSON, responseSchema, nativeTools,
                admission::commitInvocation, providerAttemptGuard)) {
            AiCompletionResult result = raw.result();
            if (result.toolCalls().isEmpty()) {
                AiStructuredRepairAttempt<T> attempt = parseStructuredAttempt(
                        raw, invocation, type, contentGuard, true);
                return new AiNativeToolCompletion.Content<>(
                        attempt,
                        result.inputTokens(),
                        result.outputTokens(),
                        result.stopReason(),
                        attempt.reasoning());
            }
            return parseNativeToolCall(raw, invocation, toolGuard, nativeTools);
        }
    }

    private <T> AiStructuredRepairAttempt<T> completeStructuredAttemptWithCommitment(
            AiInvocation invocation,
            Class<T> type,
            AiRawOutputGuard guard,
            AiResponseSchema responseSchema,
            Runnable invocationCommitment,
            Runnable providerAttemptGuard,
            boolean captureRepair) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(guard, "guard");
        Objects.requireNonNull(invocationCommitment, "invocationCommitment");
        Objects.requireNonNull(providerAttemptGuard, "providerAttemptGuard");
        try (RawInvocation raw = invokeRaw(
                invocation, AiOutputMode.JSON, responseSchema,
                invocationCommitment, providerAttemptGuard)) {
            return parseStructuredAttempt(raw, invocation, type, guard, captureRepair);
        }
    }

    private <T> AiStructuredRepairAttempt<T> parseStructuredAttempt(
            RawInvocation raw,
            AiInvocation invocation,
            Class<T> type,
            AiRawOutputGuard guard,
            boolean captureRepair) {
        AiCompletionResult result = raw.result();
        if (!result.toolCalls().isEmpty()) {
            return malformed(
                    raw, invocation, result, AiStructuredOutcome.REASON_MALFORMED,
                    "unexpected_tool_call", "", false, false);
        }
        CompletionNormalizer.CapturedCompletion captured =
                CompletionNormalizer.captureReasoning(result.text(), result.reasoning());
        if ((captured.ambiguous() && captured.answer().isBlank())
                || CompletionNormalizer.containsReasoningTag(captured.answer())) {
            return malformed(
                    raw, invocation, result, AiStructuredOutcome.REASON_MALFORMED,
                    "reasoning_boundary", "", false, false);
        }
        String stripped = captured.answer();
        ReasoningNormalization reasoning = captured.ambiguous()
                ? new ReasoningNormalization(Optional.empty(), "reasoning_boundary")
                : normalizeReasoning(captured.reasoning(), invocation);
        ObjectNode object = AiJson.extractObject(stripped, objectMapper);
        if (object == null) {
            return malformed(
                    raw, invocation, result, truncationReason(result.stopReason()),
                    "json_object_missing", stripped, false, captureRepair);
        }
        String rejectionReason = guard.rejectionReason(object);
        if (rejectionReason != null) {
            return malformed(
                    raw, invocation, result, AiStructuredOutcome.REASON_MALFORMED,
                    rejectionReason, stripped, true, captureRepair);
        }
        int warnings = Demasker.demaskTree(object, invocation.context());
        T value;
        try {
            value = objectMapper.treeToValue(object, type);
        } catch (JacksonException | IllegalArgumentException exception) {
            return malformed(
                    raw, invocation, result, AiStructuredOutcome.REASON_MALFORMED,
                    "binding_failed", stripped, true, captureRepair);
        }
        if (value == null) {
            return malformed(
                    raw, invocation, result, AiStructuredOutcome.REASON_MALFORMED,
                    "binding_failed", stripped, true, captureRepair);
        }
        raw.close();
        emitAudit(raw, invocation, "success", result.inputTokens(), result.outputTokens(),
                result.stopReason(), warnings, null, true, PARSE_OUTCOME_PARSED);
        return new AiStructuredRepairAttempt<>(
                new AiStructuredOutcome.Parsed<>(value, warnings,
                        result.inputTokens(), result.outputTokens(), result.stopReason()),
                Optional.empty(), reasoning.rejectionReason() == null
                        ? reasoning.content()
                        : Optional.empty());
    }

    private <T> AiNativeToolCompletion<T> parseNativeToolCall(
            RawInvocation raw,
            AiInvocation invocation,
            AiRawOutputGuard toolGuard,
            AiNativeToolRequest nativeTools) {
        AiCompletionResult result = raw.result();
        CompletionNormalizer.CapturedCompletion captured =
                CompletionNormalizer.captureReasoning(result.text(), result.reasoning());
        ReasoningNormalization reasoning = captured.ambiguous()
                ? new ReasoningNormalization(Optional.empty(), "reasoning_boundary")
                : normalizeReasoning(captured.reasoning(), invocation);
        if (result.toolCalls().size() != 1) {
            return malformedNativeTool(
                    raw, invocation, result, reasoning, "native_multiple_calls");
        }
        if (captured.ambiguous()
                || !captured.answer().isBlank()
                || CompletionNormalizer.containsReasoningTag(captured.answer())) {
            return malformedNativeTool(
                    raw, invocation, result, reasoning, "native_call_content");
        }
        AiToolCall call = result.toolCalls().getFirst();
        if (nativeTools.exchanges().stream()
                .anyMatch(exchange -> exchange.call().id().equals(call.id()))) {
            return malformedNativeTool(
                    raw, invocation, result, reasoning, "native_duplicate_call_id");
        }
        JsonNode arguments;
        try {
            arguments = objectMapper.readTree(call.arguments());
        } catch (JacksonException | IllegalArgumentException exception) {
            return malformedNativeTool(
                    raw, invocation, result, reasoning, "native_arguments_not_object");
        }
        if (arguments == null || !arguments.isObject()) {
            return malformedNativeTool(
                    raw, invocation, result, reasoning, "native_arguments_not_object");
        }
        ObjectNode step = objectMapper.createObjectNode();
        ObjectNode tool = step.putObject("tool");
        tool.put("name", call.name());
        tool.set("args", arguments);
        step.putNull("final");
        String rejectionReason = toolGuard.rejectionReason(step);
        if (rejectionReason != null) {
            return malformedNativeTool(
                    raw,
                    invocation,
                    result,
                    reasoning,
                    "tool_name".equals(rejectionReason)
                            ? "native_unknown_tool"
                            : "native_invalid_arguments");
        }
        int warnings = Demasker.demaskTree(arguments, invocation.context());
        raw.close();
        emitAudit(raw, invocation, "success", result.inputTokens(), result.outputTokens(),
                result.stopReason(), warnings, null, true, PARSE_OUTCOME_PARSED);
        return new AiNativeToolCompletion.Tool<>(
                call,
                arguments,
                warnings,
                result.inputTokens(),
                result.outputTokens(),
                result.stopReason(),
                reasoning.rejectionReason() == null ? reasoning.content() : Optional.empty());
    }

    private <T> AiNativeToolCompletion<T> malformedNativeTool(
            RawInvocation raw,
            AiInvocation invocation,
            AiCompletionResult result,
            ReasoningNormalization reasoning,
            String repairRule) {
        raw.close();
        emitAudit(
                raw, invocation, "success", result.inputTokens(), result.outputTokens(),
                result.stopReason(), null, null, true, AiStructuredOutcome.REASON_MALFORMED,
                new MalformedDiagnostic("native_tool_call", result.text().length(), false));
        return new AiNativeToolCompletion.Malformed<>(
                result.inputTokens(),
                result.outputTokens(),
                result.stopReason(),
                reasoning.rejectionReason() == null ? reasoning.content() : Optional.empty(),
                repairRule);
    }

    private <T> AiStructuredRepairAttempt<T> malformed(
            RawInvocation raw,
            AiInvocation invocation,
            AiCompletionResult result,
            String parseOutcome,
            String schemaRule,
            String offendingOutput,
            boolean objectExtracted,
            boolean captureRepair) {
        raw.close();
        MalformedDiagnostic diagnostic = new MalformedDiagnostic(
                schemaRule, result.text().length(), objectExtracted);
        emitAudit(raw, invocation, "success", result.inputTokens(), result.outputTokens(),
                result.stopReason(), null, null, true, parseOutcome, diagnostic);
        Optional<AiStructuredRepair> repair = captureRepair
                ? Optional.of(AiStructuredRepair.from(schemaRule, offendingOutput))
                : Optional.empty();
        return new AiStructuredRepairAttempt<>(
                new AiStructuredOutcome.Malformed<>(parseOutcome,
                        result.inputTokens(), result.outputTokens(), result.stopReason()),
                repair, Optional.empty());
    }

    private RawInvocation invokeRaw(
            AiInvocation invocation,
            AiOutputMode outputMode,
            AiResponseSchema responseSchema,
            Runnable invocationCommitment,
            Runnable providerAttemptGuard) {
        return invokeRaw(
                invocation, outputMode, responseSchema, null,
                invocationCommitment, providerAttemptGuard);
    }

    private RawInvocation invokeRaw(
            AiInvocation invocation,
            AiOutputMode outputMode,
            AiResponseSchema responseSchema,
            AiNativeToolRequest nativeTools,
            Runnable invocationCommitment,
            Runnable providerAttemptGuard) {
        Objects.requireNonNull(invocation, "invocation");
        Objects.requireNonNull(outputMode, "outputMode");
        Objects.requireNonNull(invocationCommitment, "invocationCommitment");
        Objects.requireNonNull(providerAttemptGuard, "providerAttemptGuard");
        boolean structured = outputMode == AiOutputMode.JSON;
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        int orgId = workspaceService.getCurrentOrgId();
        int userId = workspaceService.getCurrentUserId();
        String correlationId = UUID.randomUUID().toString();

        try {
            aiFeatureGate.requireAiUsable(invocation.feature());
        } catch (ForbiddenException exception) {
            emitAudit(workspaceId, orgId, null, invocation, correlationId, "blocked",
                    null, null, null, null, "gate", structured, null);
            throw exception;
        }

        return invokeAdmitted(
                invocation, outputMode, responseSchema, nativeTools,
                workspaceId, orgId, userId, correlationId,
                invocationCommitment, providerAttemptGuard);
    }

    private AiMediaAdmissionService.Lease acquireMedia(
            int workspaceId,
            int orgId,
            String correlationId,
            AiInvocation invocation,
            boolean structured) {
        try {
            return aiMediaAdmissionService.acquire(orgId, invocation.images());
        } catch (TooManyRequestsException exception) {
            emitAudit(workspaceId, orgId, null, invocation, correlationId, "blocked",
                    null, null, null, null, "media_admission", structured, null);
            throw exception;
        }
    }

    private RawInvocation invokeAdmitted(
            AiInvocation invocation,
            AiOutputMode outputMode,
            AiResponseSchema responseSchema,
            AiNativeToolRequest nativeTools,
            int workspaceId,
            int orgId,
            int userId,
            String correlationId,
            Runnable invocationCommitment,
            Runnable providerAttemptGuard) {
        boolean structured = outputMode == AiOutputMode.JSON;

        ResolvedAiProvider resolved;
        try {
            resolved = aiProviderConfigService.resolveForOrg(orgId, userId);
        } catch (ForbiddenException | AiProviderException exception) {
            emitAudit(workspaceId, orgId, null, invocation, correlationId, "blocked",
                    null, null, null, null, "provider", structured, null);
            throw exception;
        }

        if (!invocation.images().isEmpty() && !resolved.imageInputSupported()) {
            emitAudit(workspaceId, orgId, resolved, invocation, correlationId, "blocked",
                    null, null, null, null, "provider_capability", structured, null);
            throw new AiImageInputUnsupportedException();
        }

        AiProvider adapter = aiProviderRouter.adapterFor(resolved.provider());
        if (nativeTools != null
                && adapter.toolCallingCapability(resolved.target())
                        != AiToolCallingMode.NATIVE_FUNCTIONS) {
            emitAudit(workspaceId, orgId, resolved, invocation, correlationId, "blocked",
                    null, null, null, null, "provider_capability", structured, null);
            throw new AiProviderException("AI provider does not support native function tools");
        }
        AiReasoningMode reasoningMode = invocation.reasoningRequested()
                ? nativeTools == null
                        ? adapter.reasoningCapability(resolved.target())
                        : adapter.nativeToolReasoningCapability(resolved.target())
                : AiReasoningMode.NONE;

        String serializedPrompt;
        try {
            serializedPrompt = serializeProviderInput(
                    invocation.prompt(), responseSchema, reasoningMode, nativeTools);
        } catch (AiProviderException exception) {
            emitAudit(workspaceId, orgId, resolved, invocation, correlationId, "blocked",
                    null, null, null, null, "serialization", structured, null);
            throw exception;
        }

        if (serializedPrompt.getBytes(StandardCharsets.UTF_8).length > providerInputByteCeiling(
                adapter.contextWindowTokens(resolved.target()), invocation.maxTokens())) {
            emitAudit(workspaceId, orgId, resolved, invocation, correlationId, "blocked",
                    null, null, null, null, "context_window", structured, null);
            throw new AiProviderException("AI prompt exceeds the configured model context window");
        }

        try {
            OutboundLeakScan.assertNoLeak(serializedPrompt, invocation.context(), objectMapper);
        } catch (MaskingLeakException exception) {
            emitAudit(workspaceId, orgId, resolved, invocation, correlationId, "blocked",
                    null, null, null, null, "leak", structured, null);
            throw exception;
        }

        AiOrganizationBudgetCoordinator.Lease budgetLease;
        try {
            budgetLease = budgetCoordinator.reserve(orgId, invocation, serializedPrompt);
        } catch (AiBudgetExhaustedException exception) {
            emitAudit(workspaceId, orgId, resolved, invocation, correlationId, "blocked",
                    null, null, null, null, "budget_exhausted", structured, null);
            throw exception;
        }

        try {
            emitAudit(workspaceId, orgId, resolved, invocation, correlationId, "attempt",
                    null, null, null, null, null, structured, null);
        } catch (RuntimeException | Error exception) {
            budgetLease.close();
            throw exception;
        }

        MediaLeaseGuard mediaLease = MediaLeaseGuard.none();
        ProviderAttemptTracker attemptTracker = new ProviderAttemptTracker(
                workspaceId, orgId, userId, resolved, invocation, correlationId,
                structured, invocationCommitment, providerAttemptGuard,
                serializedPrompt, budgetLease);
        try {
            if (!invocation.images().isEmpty()) {
                mediaLease = MediaLeaseGuard.of(acquireMedia(
                        workspaceId, orgId, correlationId, invocation, structured));
            }
            AiCompletionResult result = withConservativeUsage(adapter.complete(request(
                    resolved, invocation, outputMode, responseSchema, nativeTools,
                    reasoningMode, attemptTracker)), invocation, serializedPrompt);
            attemptTracker.settleBudget(result.inputTokens(), result.outputTokens());
            return new RawInvocation(
                    workspaceId, orgId, resolved, correlationId, structured, result, mediaLease);
        } catch (AiProviderAttemptBlockedException exception) {
            mediaLease.close();
            attemptTracker.closeBudget();
            throw directAdmissionFailure(exception.reason());
        } catch (AiProviderException exception) {
            mediaLease.close();
            attemptTracker.closeBudget();
            if (!attemptTracker.failureAudited()) {
                emitAudit(workspaceId, orgId, resolved, invocation, correlationId, "failure",
                        null, null, null, null, "provider_exception", structured, null,
                        null, null, rejection(exception));
            }
            throw exception;
        } catch (TooManyRequestsException exception) {
            mediaLease.close();
            attemptTracker.closeBudget();
            throw exception;
        } catch (RuntimeException | Error exception) {
            mediaLease.close();
            attemptTracker.closeBudget();
            if (!attemptTracker.failureAudited()) {
                emitAudit(workspaceId, orgId, resolved, invocation, correlationId, "failure",
                        null, null, null, null, "invocation_exception", structured, null);
            }
            throw exception;
        }
    }

    private RuntimeException directAdmissionFailure(
            AiProviderAttemptBlockedException.Reason reason) {
        return switch (reason) {
            case ORGANIZATION_QUOTA ->
                    new AiInvocationAdmissionService.DirectAdmissionRejectedException(
                            AiInvocationAdmissionService.Rejection.ORGANIZATION_QUOTA);
            case CAPACITY -> new AiInvocationAdmissionService.DirectAdmissionRejectedException(
                    AiInvocationAdmissionService.Rejection.CAPACITY);
            case RESTRICTION_EPOCH ->
                    new IllegalStateException("AI restrictions changed before provider egress");
        };
    }

    private static AiCompletionResult withConservativeUsage(
            AiCompletionResult result,
            AiInvocation invocation,
            String serializedPrompt) {
        if (result.inputTokens() != 0 || result.outputTokens() != 0) {
            return result;
        }
        long inputCeiling = serializedPrompt.getBytes(StandardCharsets.UTF_8).length;
        for (AiInputImage image : invocation.images()) {
            inputCeiling = Math.min(Integer.MAX_VALUE, inputCeiling + image.size());
        }
        return new AiCompletionResult(
                result.text(),
                (int) Math.max(1, inputCeiling),
                invocation.maxTokens(),
                result.stopReason(),
                result.structuredOutputEnforcement(),
                result.reasoning(),
                result.reasoningMode(),
                result.toolCalls());
    }

    private static String truncationReason(String stopReason) {
        return TRUNCATION_STOP_REASONS.contains(stopReason)
                ? AiStructuredOutcome.REASON_TRUNCATED
                : AiStructuredOutcome.REASON_MALFORMED;
    }

    private static int providerInputByteCeiling(int contextTokens, int outputTokens) {
        return AiProviderCapabilities.conservativeInputByteCeiling(
                contextTokens, outputTokens);
    }

    private AiCompletionRequest request(
            ResolvedAiProvider resolved,
            AiInvocation invocation,
            AiOutputMode outputMode,
            AiResponseSchema responseSchema,
            AiNativeToolRequest nativeTools,
            AiReasoningMode reasoningMode,
            AiProviderAttemptExecutor providerAttemptExecutor) {
        List<AiMessage> messages = invocation.prompt().getMessages().stream()
                .map(message -> new AiMessage(message.getRole(), message.getContent()))
                .toList();
        return new AiCompletionRequest(
                resolved.target(), resolved.credentials(), systemPrompt(invocation.prompt(), reasoningMode),
                messages, invocation.images(), outputMode, responseSchema, nativeTools, reasoningMode,
                providerAttemptExecutor, invocation.maxTokens(), invocation.temperature());
    }

    private ReasoningNormalization normalizeReasoning(
            String maskedReasoning, AiInvocation invocation) {
        if (maskedReasoning == null || maskedReasoning.isBlank()) {
            return new ReasoningNormalization(Optional.empty(), null);
        }
        if (maskedReasoning.length() > MAX_REASONING_CHARS) {
            return new ReasoningNormalization(Optional.empty(), "reasoning_length");
        }
        if (AiGeneratedContentScreen.containsBarePlaceholder(maskedReasoning)) {
            return new ReasoningNormalization(Optional.empty(), "reasoning_placeholder");
        }
        String maskedRejection = AiGeneratedContentScreen.rejectionReason(maskedReasoning);
        if (maskedRejection != null) {
            return new ReasoningNormalization(Optional.empty(), maskedRejection);
        }
        try {
            OutboundLeakScan.assertNoLeak(maskedReasoning, invocation.context(), objectMapper);
        } catch (MaskingLeakException exception) {
            return new ReasoningNormalization(Optional.empty(), "reasoning_identifier_leak");
        }
        Demasker.DemaskResult demasked = Demasker.demask(
                maskedReasoning, invocation.context());
        if (demasked.warnings() != 0) {
            return new ReasoningNormalization(Optional.empty(), "reasoning_placeholder");
        }
        String demaskedRejection = AiGeneratedContentScreen.rejectionReason(demasked.text());
        return demaskedRejection == null
                ? new ReasoningNormalization(Optional.of(demasked.text()), null)
                : new ReasoningNormalization(Optional.empty(), demaskedRejection);
    }

    private String serializeProviderInput(
            MaskedPrompt prompt,
            AiResponseSchema responseSchema,
            AiReasoningMode reasoningMode,
            AiNativeToolRequest nativeTools) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("system", systemPrompt(prompt, reasoningMode));
        payload.put("messages", prompt.getMessages().stream()
                .map(AiInvocationService::messagePayload)
                .toList());
        if (responseSchema != null) {
            payload.put("responseSchema", responseSchema.schema());
        }
        if (nativeTools != null) {
            payload.put("tools", nativeTools.definitions().stream()
                    .map(AiInvocationService::toolDefinitionPayload)
                    .toList());
            payload.put("toolExchanges", nativeTools.exchanges().stream()
                    .map(AiInvocationService::toolExchangePayload)
                    .toList());
            if (nativeTools.repairMessage() != null) {
                payload.put("repairMessage", nativeTools.repairMessage());
            }
        }
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception exception) {
            throw new AiProviderException("AI prompt serialization failed");
        }
    }

    private static String systemPrompt(MaskedPrompt prompt, AiReasoningMode reasoningMode) {
        return reasoningMode == AiReasoningMode.TAGGED
                ? prompt.getSystemPrompt() + "\n\n" + TAGGED_REASONING_INSTRUCTION
                : prompt.getSystemPrompt();
    }

    private static Map<String, String> messagePayload(MaskedMessage message) {
        Map<String, String> payload = new LinkedHashMap<>();
        payload.put("role", message.getRole());
        payload.put("content", message.getContent());
        return payload;
    }

    private static Map<String, Object> toolDefinitionPayload(AiToolDefinition definition) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("name", definition.name());
        payload.put("description", definition.description());
        payload.put("parameters", definition.parametersSchema());
        return payload;
    }

    private static Map<String, Object> toolExchangePayload(AiToolExchange exchange) {
        Map<String, Object> call = new LinkedHashMap<>();
        call.put("id", exchange.call().id());
        call.put("name", exchange.call().name());
        call.put("arguments", exchange.call().arguments());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("call", call);
        payload.put("result", exchange.maskedResult());
        return payload;
    }

    private void emitAudit(RawInvocation raw, AiInvocation invocation, String outcome, Integer inputTokens,
            Integer outputTokens, String stopReason, Integer demaskWarnings, String reason, boolean structured,
            String parseOutcome) {
        emitAudit(raw.workspaceId(), raw.orgId(), raw.resolved(), invocation, raw.correlationId(), outcome,
                inputTokens, outputTokens, stopReason, demaskWarnings, reason, structured, parseOutcome,
                raw.result().structuredOutputEnforcement(), null);
    }

    private void emitAudit(RawInvocation raw, AiInvocation invocation, String outcome, Integer inputTokens,
            Integer outputTokens, String stopReason, Integer demaskWarnings, String reason, boolean structured,
            String parseOutcome, MalformedDiagnostic diagnostic) {
        emitAudit(raw.workspaceId(), raw.orgId(), raw.resolved(), invocation, raw.correlationId(), outcome,
                inputTokens, outputTokens, stopReason, demaskWarnings, reason, structured, parseOutcome,
                raw.result().structuredOutputEnforcement(), diagnostic);
    }

    private void emitAudit(int workspaceId, int orgId, ResolvedAiProvider resolved, AiInvocation invocation,
            String correlationId, String outcome, Integer inputTokens, Integer outputTokens, String stopReason,
            Integer demaskWarnings, String reason, boolean structured, String parseOutcome) {
        emitAudit(workspaceId, orgId, resolved, invocation, correlationId, outcome,
                inputTokens, outputTokens, stopReason, demaskWarnings, reason, structured, parseOutcome,
                null, null, null);
    }

    private static AiProviderRequestRejectedException rejection(AiProviderException exception) {
        return exception instanceof AiProviderRequestRejectedException rejected ? rejected : null;
    }

    private void emitAudit(int workspaceId, int orgId, ResolvedAiProvider resolved, AiInvocation invocation,
            String correlationId, String outcome, Integer inputTokens, Integer outputTokens, String stopReason,
            Integer demaskWarnings, String reason, boolean structured, String parseOutcome,
            AiStructuredOutputEnforcement enforcement, MalformedDiagnostic diagnostic) {
        emitAudit(workspaceId, orgId, resolved, invocation, correlationId, outcome,
                inputTokens, outputTokens, stopReason, demaskWarnings, reason, structured, parseOutcome,
                enforcement, diagnostic, null);
    }

    private void emitAudit(int workspaceId, int orgId, ResolvedAiProvider resolved, AiInvocation invocation,
            String correlationId, String outcome, Integer inputTokens, Integer outputTokens, String stopReason,
            Integer demaskWarnings, String reason, boolean structured, String parseOutcome,
            AiStructuredOutputEnforcement enforcement, MalformedDiagnostic diagnostic,
            AiProviderRequestRejectedException providerRejection) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("provider", provider(resolved));
        metadata.put("region", region(resolved));
        metadata.put("model", model(resolved));
        metadata.put("feature", invocation.feature().wireKey());
        metadata.put("outcome", outcome);
        metadata.put("correlationId", correlationId);
        metadata.put("messageCount", invocation.prompt().getMessages().size());
        metadata.put("mediaCount", invocation.images().size());
        if (!invocation.images().isEmpty()) {
            metadata.put("mediaBytes", invocation.images().stream()
                    .mapToInt(AiInputImage::size)
                    .sum());
            metadata.put("mediaTypes", invocation.images().stream()
                    .map(AiInputImage::contentType)
                    .distinct()
                    .toList());
        }
        metadata.put("structured", structured);
        metadata.put("reasoningRequested", invocation.reasoningRequested());
        metadata.put(
                "protocol",
                invocation.protocol().name().toLowerCase(java.util.Locale.ROOT));
        if (invocation.nativeToolsDegradedStatus() != null) {
            metadata.put("nativeToolsDegraded", true);
            metadata.put(
                    "nativeToolsDegradedStatus",
                    invocation.nativeToolsDegradedStatus());
        }
        if (structured && enforcement != null) {
            metadata.put("structuredEnforcement", enforcement.name().toLowerCase(java.util.Locale.ROOT));
        }
        if (inputTokens != null) {
            metadata.put("inputTokens", inputTokens);
        }
        if (outputTokens != null) {
            metadata.put("outputTokens", outputTokens);
        }
        if (stopReason != null) {
            metadata.put("stopReason", stopReason);
        }
        if (demaskWarnings != null) {
            metadata.put("demaskWarnings", demaskWarnings);
        }
        if (parseOutcome != null) {
            metadata.put("parseOutcome", parseOutcome);
        }
        if (reason != null) {
            metadata.put("reason", reason);
        }
        if (diagnostic != null) {
            metadata.put("schemaRule", diagnostic.schemaRule());
            metadata.put("outputLength", diagnostic.outputLength());
            metadata.put("objectExtracted", diagnostic.objectExtracted());
        }
        if (providerRejection != null) {
            metadata.put("providerStatus", providerRejection.statusCode());
            if (providerRejection.providerDetail() != null) {
                metadata.put("providerDetail", providerRejection.providerDetail());
            }
        }
        if (AUDIT_OUTCOME_ATTEMPT.equals(outcome)) {
            auditService.recordStrictIndependentScoped(AUDIT_ACTION, AUDIT_ENTITY_TYPE, null, workspaceId, orgId,
                    targetLabel(resolved), "AI call " + outcome, metadata);
            return;
        }
        auditService.recordIndependentScoped(AUDIT_ACTION, AUDIT_ENTITY_TYPE, null, workspaceId, orgId,
                targetLabel(resolved), "AI call " + outcome, metadata);
    }

    private static String targetLabel(ResolvedAiProvider resolved) {
        return provider(resolved) + "/" + region(resolved);
    }

    private static String provider(ResolvedAiProvider resolved) {
        return resolved == null ? UNKNOWN_TARGET : resolved.provider();
    }

    private static String region(ResolvedAiProvider resolved) {
        return resolved == null ? UNKNOWN_TARGET : resolved.region();
    }

    private static String model(ResolvedAiProvider resolved) {
        return resolved == null ? UNKNOWN_TARGET : resolved.modelId();
    }

    private final class ProviderAttemptTracker implements AiProviderAttemptExecutor {
        private final int workspaceId;
        private final int orgId;
        private final int userId;
        private final ResolvedAiProvider resolved;
        private final AiInvocation invocation;
        private final String correlationId;
        private final boolean structured;
        private final Runnable initialCommitment;
        private final Runnable providerAttemptGuard;
        private final String serializedPrompt;
        private AiOrganizationBudgetCoordinator.Lease budgetLease;
        private AiRequestDeadline providerDeadline;
        private boolean firstAttempt = true;
        private boolean failureAudited;

        private ProviderAttemptTracker(
                int workspaceId,
                int orgId,
                int userId,
                ResolvedAiProvider resolved,
                AiInvocation invocation,
                String correlationId,
                boolean structured,
                Runnable initialCommitment,
                Runnable providerAttemptGuard,
                String serializedPrompt,
                AiOrganizationBudgetCoordinator.Lease budgetLease) {
            this.workspaceId = workspaceId;
            this.orgId = orgId;
            this.userId = userId;
            this.resolved = resolved;
            this.invocation = invocation;
            this.correlationId = correlationId;
            this.structured = structured;
            this.initialCommitment = initialCommitment;
            this.providerAttemptGuard = providerAttemptGuard;
            this.serializedPrompt = Objects.requireNonNull(
                    serializedPrompt, "serializedPrompt");
            this.budgetLease = Objects.requireNonNull(budgetLease, "budgetLease");
        }

        @Override
        public synchronized AiRequestDeadline deadline(long requestTimeoutMillis) {
            if (providerDeadline != null) {
                return providerDeadline;
            }
            long timeoutNanos = TimeUnit.MILLISECONDS.toNanos(requestTimeoutMillis);
            if (timeoutNanos <= 0) {
                throw new IllegalStateException("AI request timeout must be positive");
            }
            Instant callerDeadline = invocation.callerDeadline();
            if (callerDeadline != null) {
                Duration remaining = Duration.between(clock.instant(), callerDeadline);
                if (remaining.isZero() || remaining.isNegative()) {
                    throw new AiProviderCallerDeadlineExceededException();
                }
                timeoutNanos = Math.min(timeoutNanos, remaining.toNanos());
            }
            providerDeadline = AiRequestDeadline.afterNanos(timeoutNanos);
            return providerDeadline;
        }

        @Override
        public synchronized String execute(Supplier<String> attempt) {
            Supplier<String> providerAttempt = Objects.requireNonNull(attempt, "attempt");
            failureAudited = false;
            AiInvocationAdmissionService.DirectAdmission fallbackAdmission = null;
            Runnable commitment = initialCommitment;
            boolean fallbackAttempt = !firstAttempt;
            if (firstAttempt) {
                firstAttempt = false;
            } else {
                try {
                    fallbackAdmission = aiInvocationAdmissionService.acquireDirect();
                    commitment = fallbackAdmission::commitInvocation;
                } catch (AiInvocationAdmissionService.DirectAdmissionRejectedException exception) {
                    auditAdmissionBlock(exception.rejection());
                    throw new AiProviderAttemptBlockedException(
                            exception.rejection() == AiInvocationAdmissionService.Rejection.ORGANIZATION_QUOTA
                                    ? AiProviderAttemptBlockedException.Reason.ORGANIZATION_QUOTA
                                    : AiProviderAttemptBlockedException.Reason.CAPACITY);
                }
            }
            Runnable attemptCommitment = commitment;
            try {
                if (fallbackAttempt) {
                    reserveFallbackBudget();
                    emitAudit(workspaceId, orgId, resolved, invocation, correlationId, "attempt",
                            null, null, null, null, null, structured, null);
                }
                return aiRestrictionEpoch.invokeAtEgress(workspaceId, () -> {
                    requireCurrentProviderSnapshot();
                    aiFeatureGate.requireAiUsable(invocation.feature());
                    providerAttemptGuard.run();
                    attemptCommitment.run();
                    return providerAttempt.get();
                });
            } catch (AiRestrictionEpoch.EgressRejectedException exception) {
                closeBudget();
                failureAudited = true;
                emitAudit(workspaceId, orgId, resolved, invocation, correlationId, "blocked",
                        null, null, null, null, "restriction_epoch", structured, null);
                throw new AiProviderAttemptBlockedException(
                        AiProviderAttemptBlockedException.Reason.RESTRICTION_EPOCH);
            } catch (AiBudgetExhaustedException exception) {
                throw exception;
            } catch (ForbiddenException exception) {
                closeBudget();
                failureAudited = true;
                emitAudit(workspaceId, orgId, resolved, invocation, correlationId, "blocked",
                        null, null, null, null, "gate", structured, null);
                throw exception;
            } catch (RuntimeException exception) {
                closeBudget();
                failureAudited = true;
                String reason = exception instanceof AiProviderException
                        ? "provider_exception"
                        : "invocation_exception";
                emitAudit(workspaceId, orgId, resolved, invocation, correlationId, "failure",
                        null, null, null, null, reason, structured, null, null, null,
                        exception instanceof AiProviderException providerException
                                ? rejection(providerException)
                                : null);
                if (callerDeadlineReached()) {
                    throw new AiProviderCallerDeadlineExceededException();
                }
                throw exception;
            } finally {
                if (fallbackAdmission != null) {
                    fallbackAdmission.close();
                }
            }
        }

        private void requireCurrentProviderSnapshot() {
            ResolvedAiProvider current = aiProviderConfigService.resolveForOrg(orgId, userId);
            if (!resolved.equals(current)) {
                throw new AiProviderException(
                        "AI provider configuration changed before egress");
            }
            if (invocation.protocol() == AiInvocationProtocol.NATIVE_TOOLS
                    && aiProviderRouter.adapterFor(current.provider())
                            .toolCallingCapability(current.target())
                            != AiToolCallingMode.NATIVE_FUNCTIONS) {
                throw new AiProviderException(
                        "AI provider native function capability changed before egress");
            }
        }

        private void reserveFallbackBudget() {
            closeBudget();
            try {
                budgetLease = budgetCoordinator.reserve(
                        orgId, invocation, serializedPrompt);
            } catch (AiBudgetExhaustedException exception) {
                failureAudited = true;
                emitAudit(workspaceId, orgId, resolved, invocation, correlationId, "blocked",
                        null, null, null, null, "budget_exhausted", structured, null);
                throw exception;
            }
        }

        private synchronized void settleBudget(int inputTokens, int outputTokens) {
            AiOrganizationBudgetCoordinator.Lease activeLease = budgetLease;
            budgetLease = null;
            if (activeLease == null) {
                throw new IllegalStateException("Provider attempt completed without a budget reservation");
            }
            activeLease.settle(inputTokens, outputTokens);
        }

        private synchronized void closeBudget() {
            AiOrganizationBudgetCoordinator.Lease activeLease = budgetLease;
            budgetLease = null;
            if (activeLease != null) {
                activeLease.close();
            }
        }

        private void auditAdmissionBlock(AiInvocationAdmissionService.Rejection rejection) {
            failureAudited = true;
            String reason = rejection == AiInvocationAdmissionService.Rejection.ORGANIZATION_QUOTA
                    ? "organization_quota"
                    : "invocation_capacity";
            emitAudit(workspaceId, orgId, resolved, invocation, correlationId, "blocked",
                    null, null, null, null, reason, structured, null);
        }

        private boolean failureAudited() {
            return failureAudited;
        }

        private boolean callerDeadlineReached() {
            Instant callerDeadline = invocation.callerDeadline();
            return callerDeadline != null && !clock.instant().isBefore(callerDeadline);
        }
    }

    private record MalformedDiagnostic(
            String schemaRule,
            int outputLength,
            boolean objectExtracted) {
    }

    private record ReasoningNormalization(
            Optional<String> content,
            String rejectionReason) {
    }

    private record RawInvocation(
            int workspaceId,
            int orgId,
            ResolvedAiProvider resolved,
            String correlationId,
            boolean structured,
            AiCompletionResult result,
            MediaLeaseGuard mediaLease) implements AutoCloseable {

        @Override
        public void close() {
            mediaLease.close();
        }
    }

    private static final class MediaLeaseGuard implements AutoCloseable {
        private final AiMediaAdmissionService.Lease delegate;
        private final AtomicBoolean closed = new AtomicBoolean();

        private MediaLeaseGuard(AiMediaAdmissionService.Lease delegate) {
            this.delegate = delegate;
        }

        private static MediaLeaseGuard none() {
            return new MediaLeaseGuard(null);
        }

        private static MediaLeaseGuard of(AiMediaAdmissionService.Lease delegate) {
            return new MediaLeaseGuard(Objects.requireNonNull(delegate, "delegate"));
        }

        @Override
        public void close() {
            if (delegate != null && closed.compareAndSet(false, true)) {
                delegate.close();
            }
        }
    }
}
