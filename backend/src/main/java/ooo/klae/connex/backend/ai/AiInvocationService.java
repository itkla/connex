package ooo.klae.connex.backend.ai;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.ai.masking.AiJson;
import ooo.klae.connex.backend.ai.masking.CompletionNormalizer;
import ooo.klae.connex.backend.ai.masking.Demasker;
import ooo.klae.connex.backend.ai.masking.MaskedMessage;
import ooo.klae.connex.backend.ai.masking.MaskedPrompt;
import ooo.klae.connex.backend.ai.masking.MaskingLeakException;
import ooo.klae.connex.backend.ai.masking.OutboundLeakScan;
import ooo.klae.connex.backend.ai.provider.AiCompletionRequest;
import ooo.klae.connex.backend.ai.provider.AiCompletionResult;
import ooo.klae.connex.backend.ai.provider.AiInputImage;
import ooo.klae.connex.backend.ai.provider.AiImageInputUnsupportedException;
import ooo.klae.connex.backend.ai.provider.AiMessage;
import ooo.klae.connex.backend.ai.provider.AiOutputMode;
import ooo.klae.connex.backend.ai.provider.AiProviderAttemptBlockedException;
import ooo.klae.connex.backend.ai.provider.AiProviderAttemptExecutor;
import ooo.klae.connex.backend.ai.provider.AiProviderException;
import ooo.klae.connex.backend.ai.provider.AiProviderRouter;
import ooo.klae.connex.backend.ai.provider.AiResponseSchema;
import ooo.klae.connex.backend.ai.provider.AiStructuredOutputEnforcement;
import ooo.klae.connex.backend.ai.provider.ResolvedAiProvider;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.exceptions.AiBudgetExhaustedException;
import ooo.klae.connex.backend.exceptions.TooManyRequestsException;
import ooo.klae.connex.backend.services.AiProviderConfigService;
import ooo.klae.connex.backend.services.AuditService;
import ooo.klae.connex.backend.services.WorkspaceService;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Single outbound LLM invocation path for AI features. This service enforces feature gating,
 * provider credential resolution, final leak scanning, provider invocation, demasking, and
 * metadata-only append-only audit for every attempted or blocked call. Features may request either
 * a demasked text completion ({@link #complete}) or a demasked, type-bound structured completion
 * ({@link #completeStructured}); both route through the same audited core.
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

    /**
     * Completes a masked AI invocation and returns demasked text.
     * @param invocation masked invocation request
     * @return demasked completion outcome
     */
    public AiCompletionOutcome complete(AiInvocation invocation) {
        try (RawInvocation raw = invokeRaw(
                invocation, AiOutputMode.TEXT, null, NO_INVOCATION_COMMITMENT)) {
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
        try (RawInvocation raw = invokeRaw(
                invocation, AiOutputMode.TEXT, null, admission::commitInvocation)) {
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
        AtomicReference<AiCompletionOutcome> outcomeReference = new AtomicReference<>();
        aiRestrictionEpoch.runWithExpectedEgressEpoch(
                workspaceService.getCurrentWorkspaceId(),
                expectedRestrictionEpoch,
                () -> outcomeReference.set(complete(invocation, admission)));
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
                NO_INVOCATION_COMMITMENT, false).outcome();
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
                admission::commitLeaderInvocation, false).outcome();
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
                NO_INVOCATION_COMMITMENT, false).outcome();
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
                admission::commitLeaderInvocation, false).outcome();
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
                NO_INVOCATION_COMMITMENT, true);
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
                admission::commitInvocation, true);
    }

    private <T> AiStructuredRepairAttempt<T> completeStructuredAttemptWithCommitment(
            AiInvocation invocation,
            Class<T> type,
            AiRawOutputGuard guard,
            AiResponseSchema responseSchema,
            Runnable invocationCommitment,
            boolean captureRepair) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(guard, "guard");
        Objects.requireNonNull(invocationCommitment, "invocationCommitment");
        try (RawInvocation raw = invokeRaw(
                invocation, AiOutputMode.JSON, responseSchema, invocationCommitment)) {
            AiCompletionResult result = raw.result();
            String stripped = CompletionNormalizer.stripReasoning(result.text());
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
                    Optional.empty());
        }
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
                repair);
    }

    private RawInvocation invokeRaw(
            AiInvocation invocation,
            AiOutputMode outputMode,
            AiResponseSchema responseSchema,
            Runnable invocationCommitment) {
        Objects.requireNonNull(invocation, "invocation");
        Objects.requireNonNull(outputMode, "outputMode");
        Objects.requireNonNull(invocationCommitment, "invocationCommitment");
        boolean structured = outputMode == AiOutputMode.JSON;
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        int orgId = workspaceService.getCurrentOrgId();
        String correlationId = UUID.randomUUID().toString();

        try {
            aiFeatureGate.requireAiUsable(invocation.feature());
        } catch (ForbiddenException exception) {
            emitAudit(workspaceId, orgId, null, invocation, correlationId, "blocked",
                    null, null, null, null, "gate", structured, null);
            throw exception;
        }

        return invokeAdmitted(
                invocation, outputMode, responseSchema,
                workspaceId, orgId, correlationId, invocationCommitment);
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
            int workspaceId,
            int orgId,
            String correlationId,
            Runnable invocationCommitment) {
        boolean structured = outputMode == AiOutputMode.JSON;

        ResolvedAiProvider resolved;
        try {
            resolved = aiProviderConfigService.resolveForOrg(orgId, workspaceService.getCurrentUserId());
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

        String serializedPrompt;
        try {
            serializedPrompt = serializePrompt(invocation.prompt());
        } catch (AiProviderException exception) {
            emitAudit(workspaceId, orgId, resolved, invocation, correlationId, "blocked",
                    null, null, null, null, "serialization", structured, null);
            throw exception;
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
            budgetLease = budgetCoordinator.reserve(orgId, invocation);
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
                workspaceId, orgId, resolved, invocation, correlationId,
                structured, invocationCommitment, budgetLease);
        try {
            if (!invocation.images().isEmpty()) {
                mediaLease = MediaLeaseGuard.of(acquireMedia(
                        workspaceId, orgId, correlationId, invocation, structured));
            }
            AiCompletionResult result = aiProviderRouter.adapterFor(resolved.provider())
                    .complete(request(
                            resolved, invocation, outputMode, responseSchema, attemptTracker));
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
                        null, null, null, null, "provider_exception", structured, null);
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

    private static String truncationReason(String stopReason) {
        return TRUNCATION_STOP_REASONS.contains(stopReason)
                ? AiStructuredOutcome.REASON_TRUNCATED
                : AiStructuredOutcome.REASON_MALFORMED;
    }

    private AiCompletionRequest request(
            ResolvedAiProvider resolved,
            AiInvocation invocation,
            AiOutputMode outputMode,
            AiResponseSchema responseSchema,
            AiProviderAttemptExecutor providerAttemptExecutor) {
        List<AiMessage> messages = invocation.prompt().getMessages().stream()
                .map(message -> new AiMessage(message.getRole(), message.getContent()))
                .toList();
        return new AiCompletionRequest(resolved.target(), resolved.credentials(), invocation.prompt().getSystemPrompt(),
                messages, invocation.images(), outputMode, responseSchema,
                providerAttemptExecutor, invocation.maxTokens(), invocation.temperature());
    }

    private String serializePrompt(MaskedPrompt prompt) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("system", prompt.getSystemPrompt());
        payload.put("messages", prompt.getMessages().stream()
                .map(AiInvocationService::messagePayload)
                .toList());
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception exception) {
            throw new AiProviderException("AI prompt serialization failed");
        }
    }

    private static Map<String, String> messagePayload(MaskedMessage message) {
        Map<String, String> payload = new LinkedHashMap<>();
        payload.put("role", message.getRole());
        payload.put("content", message.getContent());
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
                null, null);
    }

    private void emitAudit(int workspaceId, int orgId, ResolvedAiProvider resolved, AiInvocation invocation,
            String correlationId, String outcome, Integer inputTokens, Integer outputTokens, String stopReason,
            Integer demaskWarnings, String reason, boolean structured, String parseOutcome,
            AiStructuredOutputEnforcement enforcement, MalformedDiagnostic diagnostic) {
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
        private final ResolvedAiProvider resolved;
        private final AiInvocation invocation;
        private final String correlationId;
        private final boolean structured;
        private final Runnable initialCommitment;
        private AiOrganizationBudgetCoordinator.Lease budgetLease;
        private boolean firstAttempt = true;
        private boolean failureAudited;

        private ProviderAttemptTracker(
                int workspaceId,
                int orgId,
                ResolvedAiProvider resolved,
                AiInvocation invocation,
                String correlationId,
                boolean structured,
                Runnable initialCommitment,
                AiOrganizationBudgetCoordinator.Lease budgetLease) {
            this.workspaceId = workspaceId;
            this.orgId = orgId;
            this.resolved = resolved;
            this.invocation = invocation;
            this.correlationId = correlationId;
            this.structured = structured;
            this.initialCommitment = initialCommitment;
            this.budgetLease = Objects.requireNonNull(budgetLease, "budgetLease");
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
                    aiFeatureGate.requireAiUsable(invocation.feature());
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
                        null, null, null, null, reason, structured, null);
                throw exception;
            } finally {
                if (fallbackAdmission != null) {
                    fallbackAdmission.close();
                }
            }
        }

        private void reserveFallbackBudget() {
            closeBudget();
            try {
                budgetLease = budgetCoordinator.reserve(orgId, invocation);
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
    }

    private record MalformedDiagnostic(
            String schemaRule,
            int outputLength,
            boolean objectExtracted) {
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
