package ooo.klae.connex.backend.ai.mission;

import java.util.Locale;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.ai.AiGenerationContextRunner;
import ooo.klae.connex.backend.ai.AiGenerationTaskResult;
import ooo.klae.connex.backend.ai.AiProperties;
import ooo.klae.connex.backend.ai.AiRestrictionEpoch;
import ooo.klae.connex.backend.ai.assistant.AiChatAgentLoopService;
import ooo.klae.connex.backend.ai.assistant.AiAssistantLoopException;
import ooo.klae.connex.backend.ai.lease.AiRunLeaseGuard;
import ooo.klae.connex.backend.beans.AiAgentRun;
import ooo.klae.connex.backend.dto.AiAgentRunCreateRequest;
import ooo.klae.connex.backend.mappers.AiAgentRunMapper;
import tools.jackson.databind.ObjectMapper;

/** Rebuilds the stored requester and executes a single read-only segment outside transactions. */
@Service
@RequiredArgsConstructor
public class AiAgentSegmentRunner {
    private final AiGenerationContextRunner context;
    private final AiAgentCapabilityGate gate;
    private final AiAgentRunService service;
    private final AiAgentRunPersistenceService persistence;
    private final AiChatAgentLoopService loop;
    private final AiProperties properties;
    private final AiRestrictionEpoch restriction;
    private final AiAgentRunMapper runs;
    private final AiAgentRealtimePublisher publisher;
    private final ObjectMapper json;

    /** Reauthorizes interrupted READ work before a separate stopped-to-queued transaction. */
    public void recoverInterrupted(AiAgentRun candidate) {
        if (candidate.getOwnerUserId() == null || candidate.getSessionId() == null) {
            return;
        }
        context.run(candidate.getWorkspaceId(), candidate.getOwnerUserId(),
                Locale.forLanguageTag(candidate.getLocale()), () -> {
                    gate.requireReady();
                    service.requirePrivacyReady();
                    new AiAgentContinuationCodec().decode(candidate);
                    service.requireScope(json.readValue(candidate.getScopeJson(), AiAgentRunCreateRequest.Scope.class));
                    boolean queued = persistence.requeueInterrupted(candidate);
                    if (queued) {
                        publisher.publish(runs.getRun(candidate.getWorkspaceId(), candidate.getId()), "mission_state");
                    }
                });
    }

    /** A preclaim refusal does not consume an attempt or any reserved allowance. */
    public void run(AiAgentRun candidate) {
        if (candidate.getOwnerUserId() == null || candidate.getSessionId() == null) {
            return;
        }
        context.run(candidate.getWorkspaceId(), candidate.getOwnerUserId(), Locale.forLanguageTag(candidate.getLocale()), () -> {
            new AiAgentContinuationCodec().decode(candidate);
            byte[] declaration = gate.requireReady();
            service.requirePrivacyReady();
            service.requireScope(json.readValue(candidate.getScopeJson(), AiAgentRunCreateRequest.Scope.class));
            AiAgentExecutionPolicy policy = persistence.claim(candidate, declaration,
                    new AiRunLeaseGuard(properties.getRunLeaseTtl()), gate);
            publisher.publish(runs.getRun(candidate.getWorkspaceId(), candidate.getId()), "mission_state");
            try {
                restriction.runWithExpectedEgressEpoch(candidate.getWorkspaceId(), policy.turn().restrictionEpoch(), () ->
                        policy.execute(() -> {
                            var result = loop.run(policy.turn());
                            if (result.outcome() != AiGenerationTaskResult.Outcome.RESOLVED) {
                                AiAgentRun current = runs.getRun(candidate.getWorkspaceId(), candidate.getId());
                                if (current != null && current.getExecutionGeneration() == policy.executionGeneration()) {
                                    persistence.interrupt(current, result.reason());
                                }
                            }
                            return result;
                        }));
            } catch (RuntimeException exception) {
                AiAgentRun current = runs.getRun(candidate.getWorkspaceId(), candidate.getId());
                if (current != null && current.getExecutionGeneration() == policy.executionGeneration()) {
                    persistence.interrupt(current, exception instanceof AiAssistantLoopException refusal
                            ? refusal.terminalReason() : Thread.currentThread().isInterrupted() ? "interrupted" : "provider_failed");
                }
            }
            AiAgentRun current = runs.getRun(candidate.getWorkspaceId(), candidate.getId());
            if (current != null) {
                publisher.publish(current, current.getReportMessageId() == null ? "mission_state" : "mission_output");
            }
        });
    }
}
