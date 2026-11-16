package ooo.klae.connex.backend.ai.mission;

import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.ai.AiInvocationService;
import ooo.klae.connex.backend.ai.assistant.AiAssistantPromptAssembler;
import ooo.klae.connex.backend.ai.assistant.AiAssistantStepSchema;
import ooo.klae.connex.backend.ai.assistant.AiAssistantToolCatalog;
import ooo.klae.connex.backend.ai.assistant.AiAssistantToolCatalog.Toolset;
import ooo.klae.connex.backend.ai.provider.AiNativeToolRequest;
import ooo.klae.connex.backend.ai.provider.AiReasoningMode;
import ooo.klae.connex.backend.exceptions.AiAgentBudgetInfeasibleException;

/** Measures every reachable runtime-three fixed provider envelope through dispatch serialization. */
@Service
@RequiredArgsConstructor
public class AiAgentProposalBudgetService {
    private final AiAssistantPromptAssembler assembler;
    private final AiAssistantStepSchema schema;
    private final AiInvocationService invocations;
    private volatile EnvelopeMaxima cached;

    /** Largest actual working/checkpoint-closing and final-only fixed envelopes. */
    public record EnvelopeMaxima(int working, int closing) {}

    /** Applies actual envelope admission without changing runtime-one/two creation constants. */
    public void requireFeasibleCreation(int serializedGoalBytes, long maxTokens) {
        EnvelopeMaxima maxima = maxima();
        long working = (long) maxima.working() + serializedGoalBytes + 4096 + 256;
        long closing = (long) maxima.closing() + serializedGoalBytes + 4096 + 256;
        if (serializedGoalBytes > AiAgentBudgetService.MAX_SERIALIZED_GOAL_BYTES
                || maxTokens < 81920 || working > 65536 || closing > 65536 || working + 65536 > maxTokens) {
            throw new AiAgentBudgetInfeasibleException();
        }
    }

    /** Enumerates primary kinds, reasoning modes, both protocols and the two-set loading ceiling. */
    public EnvelopeMaxima maxima() {
        EnvelopeMaxima result = cached;
        if (result != null) {
            return result;
        }
        synchronized (this) {
            if (cached != null) {
                return cached;
            }
            int working = 0;
            int closing = 0;
            for (String kind : List.of("person", "company", "deal")) {
                var policy = new AiAgentExecutionPolicy(1, 1, 1, null, null,
                        new AiAgentScopePolicy(kind, 1), 4, Instant.MAX, null, null, null, null,
                        3, 1, 1, null, true, 65536);
                EnvelopeMaxima measured = policy.execute(this::measureLoadedStates);
                working = Math.max(working, measured.working());
                closing = Math.max(closing, measured.closing());
            }
            cached = new EnvelopeMaxima(working, closing);
            return cached;
        }
    }

    private EnvelopeMaxima measureLoadedStates() {
        int working = 0;
        int closing = 0;
        int count = AiAssistantToolCatalog.LOADABLE.size();
        for (int first = -1; first < count; first++) {
            for (int second = first; second < count; second++) {
                if (first < 0 && second >= 0 || first >= 0 && second == first) {
                    continue;
                }
                Set<Toolset> loaded = EnumSet.of(Toolset.CORE);
                if (first >= 0) {
                    loaded.add(AiAssistantToolCatalog.LOADABLE.get(first));
                }
                if (second >= 0) {
                    loaded.add(AiAssistantToolCatalog.LOADABLE.get(second));
                }
                EnvelopeMaxima state = measure(loaded);
                working = Math.max(working, state.working());
                closing = Math.max(closing, state.closing());
            }
            if (first >= 0) {
                EnvelopeMaxima state = measure(EnumSet.of(Toolset.CORE, AiAssistantToolCatalog.LOADABLE.get(first)));
                working = Math.max(working, state.working());
                closing = Math.max(closing, state.closing());
            }
        }
        return new EnvelopeMaxima(working, closing);
    }

    private EnvelopeMaxima measure(Set<Toolset> loaded) {
        int working = 0;
        int closing = 0;
        var definitions = assembler.nativeToolDefinitions(loaded);
        var checkpointDefinitions = definitions.stream().filter(tool -> "checkpoint_segment".equals(tool.name())).toList();
        for (AiReasoningMode reasoning : AiReasoningMode.values()) {
            working = Math.max(working, invocations.serializedPromptBytes(assembler.fixedPrompt(loaded),
                    schema.responseSchema(loaded), reasoning));
            working = Math.max(working, invocations.serializedPromptBytes(assembler.fixedPrompt(loaded),
                    schema.missionClosingResponseSchema(), reasoning));
            working = Math.max(working, invocations.serializedPromptBytes(assembler.fixedNativePrompt(loaded),
                    schema.finalResponseSchema(), reasoning, new AiNativeToolRequest(definitions, List.of())));
            working = Math.max(working, invocations.serializedPromptBytes(assembler.fixedNativePrompt(loaded),
                    schema.finalResponseSchema(), reasoning, new AiNativeToolRequest(checkpointDefinitions, List.of())));
            closing = Math.max(closing, invocations.serializedPromptBytes(assembler.fixedPrompt(loaded),
                    schema.closingResponseSchema(), reasoning));
            closing = Math.max(closing, invocations.serializedPromptBytes(assembler.fixedNativePrompt(loaded),
                    schema.finalResponseSchema(), reasoning, new AiNativeToolRequest(definitions, List.of(), null, true)));
        }
        return new EnvelopeMaxima(working, closing);
    }
}
