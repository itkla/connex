package ooo.klae.connex.backend.ai;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.ai.masking.CompletionNormalizer;
import ooo.klae.connex.backend.ai.masking.Demasker;
import ooo.klae.connex.backend.ai.masking.MaskedMessage;
import ooo.klae.connex.backend.ai.masking.MaskedPrompt;
import ooo.klae.connex.backend.ai.masking.MaskingLeakException;
import ooo.klae.connex.backend.ai.masking.OutboundLeakScan;
import ooo.klae.connex.backend.ai.provider.AiCompletionRequest;
import ooo.klae.connex.backend.ai.provider.AiCompletionResult;
import ooo.klae.connex.backend.ai.provider.AiMessage;
import ooo.klae.connex.backend.ai.provider.AiProviderException;
import ooo.klae.connex.backend.ai.provider.AiProviderRouter;
import ooo.klae.connex.backend.ai.provider.ResolvedAiProvider;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.services.AiProviderConfigService;
import ooo.klae.connex.backend.services.AuditService;
import ooo.klae.connex.backend.services.WorkspaceService;
import tools.jackson.databind.ObjectMapper;

/**
 * Single outbound LLM invocation path for AI features. This service enforces feature gating,
 * provider credential resolution, final leak scanning, provider invocation, demasking, and
 * metadata-only append-only audit for every attempted or blocked call.
 */
@Service
@RequiredArgsConstructor
public class AiInvocationService {
    private static final String AUDIT_ACTION = "ai.llm.call";
    private static final String AUDIT_ENTITY_TYPE = "ai_call";
    private static final String UNKNOWN_TARGET = "unresolved";

    private final AiFeatureGate aiFeatureGate;
    private final AiProviderConfigService aiProviderConfigService;
    private final AiProviderRouter aiProviderRouter;
    private final WorkspaceService workspaceService;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;

    /**
     * Completes a masked AI invocation through the configured organization provider.
     * @param invocation masked invocation request
     * @return demasked completion outcome
     */
    public AiCompletionOutcome complete(AiInvocation invocation) {
        Objects.requireNonNull(invocation, "invocation");
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        int orgId = workspaceService.getCurrentOrgId();
        int actorId = workspaceService.getCurrentUserId();
        String correlationId = UUID.randomUUID().toString();

        try {
            aiFeatureGate.requireAiUsable();
        } catch (ForbiddenException exception) {
            emitAudit(workspaceId, orgId, null, invocation, correlationId, "blocked",
                    null, null, null, null, "gate");
            throw exception;
        }

        ResolvedAiProvider resolved;
        try {
            resolved = aiProviderConfigService.resolveForOrg(orgId);
        } catch (ForbiddenException | AiProviderException exception) {
            emitAudit(workspaceId, orgId, null, invocation, correlationId, "blocked",
                    null, null, null, null, "provider");
            throw exception;
        }

        String serializedPrompt;
        try {
            serializedPrompt = serializePrompt(invocation.prompt());
        } catch (AiProviderException exception) {
            emitAudit(workspaceId, orgId, resolved, invocation, correlationId, "blocked",
                    null, null, null, null, "serialization");
            throw exception;
        }

        try {
            OutboundLeakScan.assertNoLeak(serializedPrompt, invocation.context());
        } catch (MaskingLeakException exception) {
            emitAudit(workspaceId, orgId, resolved, invocation, correlationId, "blocked",
                    null, null, null, null, "leak");
            throw exception;
        }

        emitAudit(workspaceId, orgId, resolved, invocation, correlationId, "attempt",
                null, null, null, null, null);

        try {
            AiCompletionResult result = aiProviderRouter.adapterFor(resolved.provider())
                    .complete(request(resolved, invocation));
            Demasker.DemaskResult demasked = Demasker.demask(
                    CompletionNormalizer.stripReasoning(result.text()), invocation.context());
            emitAudit(workspaceId, orgId, resolved, invocation, correlationId, "success",
                    result.inputTokens(), result.outputTokens(), result.stopReason(), demasked.warnings(), null);
            return new AiCompletionOutcome(demasked.text(), demasked.warnings(),
                    result.inputTokens(), result.outputTokens(), result.stopReason());
        } catch (AiProviderException exception) {
            emitAudit(workspaceId, orgId, resolved, invocation, correlationId, "failure",
                    null, null, null, null, "provider_exception");
            throw exception;
        }
    }

    private AiCompletionRequest request(ResolvedAiProvider resolved, AiInvocation invocation) {
        List<AiMessage> messages = invocation.prompt().getMessages().stream()
                .map(message -> new AiMessage(message.getRole(), message.getContent()))
                .toList();
        return new AiCompletionRequest(resolved.target(), resolved.credentials(), invocation.prompt().getSystemPrompt(),
                messages, invocation.maxTokens(), invocation.temperature());
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

    private void emitAudit(int workspaceId, int orgId, ResolvedAiProvider resolved, AiInvocation invocation,
            String correlationId, String outcome, Integer inputTokens, Integer outputTokens, String stopReason,
            Integer demaskWarnings, String reason) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("provider", provider(resolved));
        metadata.put("region", region(resolved));
        metadata.put("model", model(resolved));
        metadata.put("feature", invocation.feature());
        metadata.put("outcome", outcome);
        metadata.put("correlationId", correlationId);
        metadata.put("messageCount", invocation.prompt().getMessages().size());
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
        if (reason != null) {
            metadata.put("reason", reason);
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
}
