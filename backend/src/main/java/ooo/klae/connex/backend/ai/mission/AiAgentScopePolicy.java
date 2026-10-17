package ooo.klae.connex.backend.ai.mission;

import java.util.Set;
import java.util.function.Supplier;

import ooo.klae.connex.backend.ai.assistant.AiAssistantLoopException;
import ooo.klae.connex.backend.dto.AiAgentRunCreateRequest;

/** Closed primary-record allowlist and read-tool ceiling for record_review_v1. */
public record AiAgentScopePolicy(String kind, int id) {
    private static final ThreadLocal<AiAgentScopePolicy> ADMISSION_SCOPE = new ThreadLocal<>();
    public static final Set<String> TOOLS = Set.of(
            "get_record", "get_records", "list_activities", "list_tasks", "find_tools");

    public AiAgentScopePolicy {
        if (!Set.of("person", "company", "deal").contains(kind) || id <= 0) {
            throw new IllegalArgumentException("Invalid mission scope");
        }
    }

    public AiAgentScopePolicy(AiAgentRunCreateRequest.Scope scope) {
        this(scope.recordKind(), scope.recordId());
    }

    /** Returns the immutable execution or preclaim scope at the retrieval boundary. */
    public static AiAgentScopePolicy current() {
        var execution = AiAgentExecutionPolicy.current();
        return execution == null ? ADMISSION_SCOPE.get() : execution.scope();
    }

    /** Applies the same retrieval ceiling while validating a scope before an execution exists. */
    public <T> T evaluate(Supplier<T> action) {
        AiAgentScopePolicy previous = ADMISSION_SCOPE.get();
        ADMISSION_SCOPE.set(this);
        try {
            return action.get();
        } finally {
            if (previous == null) {
                ADMISSION_SCOPE.remove();
            } else {
                ADMISSION_SCOPE.set(previous);
            }
        }
    }

    /** Refuses before retrieval or resource registration, even for another accessible record. */
    public void requireRecord(String recordKind, int recordId) {
        if (!kind.equals(recordKind) || id != recordId) {
            throw new AiAssistantLoopException("mission_scope_refused", "mission_scope_refused");
        }
    }

    /** Refuses writes, questions, plans, search, and aggregates independently of prompt hiding. */
    public void requireTool(String name) {
        if (!TOOLS.contains(name)) {
            throw new AiAssistantLoopException("mission_tool_refused", "mission_tool_refused");
        }
    }
}
