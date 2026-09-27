package ooo.klae.connex.backend.ai.provider;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Static native definitions plus ephemeral completed exchanges for one provider request.
 *
 * @param definitions declared native tools; required so replayed exchanges keep resolving
 * @param exchanges completed call/result pairs replayed to the provider
 * @param repairMessage schema-repair instruction for the retry request, or null
 * @param finalOnly whether the provider must answer without calling any tool on this step —
 *     the closing step keeps its definitions for exchange pairing but forbids further calls
 */
public record AiNativeToolRequest(
        List<AiToolDefinition> definitions,
        List<AiToolExchange> exchanges,
        String repairMessage,
        boolean finalOnly) {

    public AiNativeToolRequest(
            List<AiToolDefinition> definitions,
            List<AiToolExchange> exchanges,
            String repairMessage) {
        this(definitions, exchanges, repairMessage, false);
    }

    public AiNativeToolRequest {
        definitions = List.copyOf(Objects.requireNonNull(definitions, "definitions"));
        exchanges = List.copyOf(Objects.requireNonNull(exchanges, "exchanges"));
        if (definitions.isEmpty()) {
            throw new IllegalArgumentException("AI native tool definitions are required");
        }
        Set<String> names = new HashSet<>();
        for (AiToolDefinition definition : definitions) {
            if (!names.add(definition.name())) {
                throw new IllegalArgumentException("AI native tool names must be unique");
            }
        }
        Set<String> ids = new HashSet<>();
        for (AiToolExchange exchange : exchanges) {
            if (!names.contains(exchange.call().name()) || !ids.add(exchange.call().id())) {
                throw new IllegalArgumentException("AI native tool exchange is invalid");
            }
        }
        requireGroupedSteps(exchanges);
        if (repairMessage != null && repairMessage.isBlank()) {
            throw new IllegalArgumentException("AI native repair message cannot be blank");
        }
    }

    /** Creates native definitions and exchanges without a structured-content repair message. */
    public AiNativeToolRequest(
            List<AiToolDefinition> definitions,
            List<AiToolExchange> exchanges) {
        this(definitions, exchanges, null);
    }

    /**
     * Refuses a replay that would rebuild an assistant message the model never sent.
     *
     * <p>An adapter emits one assistant message per run of exchanges sharing a {@code step}, so the
     * grouping has to be honest before it is serialized: steps never go backwards, a step's
     * exchanges are therefore one contiguous run, and within that run the ordinals are either the
     * single value 0 — the sole call of its step — or exactly {@code 1..n} ascending. A run of
     * {@code {1, 3}} is precisely the shape a dropped middle call leaves behind, and it is refused
     * here rather than silently replayed as an assistant message carrying fewer calls than the
     * model emitted.
     *
     * @param exchanges the completed exchanges replayed to the provider, in replay order
     */
    private static void requireGroupedSteps(List<AiToolExchange> exchanges) {
        int currentStep = 0;
        int previousOrdinal = 0;
        boolean soleCall = false;
        for (AiToolExchange exchange : exchanges) {
            if (exchange.step() < currentStep) {
                throw new IllegalArgumentException(
                        "AI native tool exchange grouping is invalid");
            }
            if (exchange.step() > currentStep) {
                currentStep = exchange.step();
                soleCall = exchange.callOrdinal() == 0;
                if (!soleCall && exchange.callOrdinal() != 1) {
                    throw new IllegalArgumentException(
                            "AI native tool exchange grouping is invalid");
                }
            } else if (soleCall || exchange.callOrdinal() != previousOrdinal + 1) {
                throw new IllegalArgumentException(
                        "AI native tool exchange grouping is invalid");
            }
            previousOrdinal = exchange.callOrdinal();
        }
    }

    @Override
    public String toString() {
        return "AiNativeToolRequest[definitions=" + definitions.size()
                + ", exchanges=<redacted>, repairMessage=<redacted>]";
    }
}
