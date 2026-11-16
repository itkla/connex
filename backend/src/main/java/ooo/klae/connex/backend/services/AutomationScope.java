package ooo.klae.connex.backend.services;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import org.springframework.stereotype.Component;

/**
 * Thread-scoped trigger suppression and execution provenance. While active,
 * {@link RuleTriggerPublisher} suppresses new trigger events, so automation mutations cannot
 * re-trigger rules — the guard against self-referential and cascading automation loops (e.g. a
 * {@code change_stage} action on a {@code deal.stage_changed} rule). Automation actions therefore
 * never chain into further rules; this is deliberate. Other background work retains its own
 * source attribution, and mutating observers may be attributed without suppressing triggers.
 */
@Component
public class AutomationScope {

    private final ThreadLocal<Boolean> active = ThreadLocal.withInitial(() -> Boolean.FALSE);
    private final ThreadLocal<Origin> origin = new ThreadLocal<>();

    /** Returns the current execution attribution, or null outside an attributed execution. */
    public Origin origin() {
        return origin.get();
    }

    /** Captures the current marker and attribution for nested execution restoration. */
    public State snapshot() {
        return new State(isActive(), origin());
    }

    /** Installs an execution origin and suppresses recursive rule triggers. */
    public State enter(Origin executionOrigin) {
        State previous = attribute(executionOrigin);
        enter();
        return previous;
    }

    /** Attributes a mutating observer without suppressing the rule triggers it is allowed to publish. */
    public State attribute(Origin executionOrigin) {
        Objects.requireNonNull(executionOrigin, "executionOrigin");
        State previous = snapshot();
        origin.set(executionOrigin);
        return previous;
    }

    /** Restores both trigger suppression and attribution after an execution. */
    public void restore(State previous) {
        restore(previous.active());
        if (previous.origin() == null) {
            origin.remove();
        } else {
            origin.set(previous.origin());
        }
    }

    /** Whether the current thread is inside a rule action execution. */
    public boolean isActive() {
        return active.get();
    }

    /** Marks the current thread as executing a rule action; returns the previous state to restore. */
    public boolean enter() {
        boolean previous = active.get();
        active.set(Boolean.TRUE);
        return previous;
    }

    /** Restores the marker to a state previously returned by {@link #enter()}. */
    public void restore(boolean previous) {
        if (previous) {
            active.set(Boolean.TRUE);
        } else {
            active.remove();
        }
    }

    /** Thread state captured before entering a nested execution. */
    public record State(boolean active, Origin origin) {
    }

    /** Closed vocabulary for automation and other explicitly attributed background work. */
    public enum Source {
        RULE, WORKFLOW, CAMPAIGN_DISPATCH, DELIVERY_WEBHOOK, DELIVERY_UNSUBSCRIBE,
        DOCUMENT_ACCEPTANCE, DOCUMENT_SIGNATURE_WEBHOOK, DOCUMENT_DELIVERY_LIFECYCLE,
        DOCUMENT_DELIVERY_EXPIRY, APPROVAL_RECONCILIATION, REPORT_DELIVERY, AI_GENERATION,
        LEAD_RESPONSE_SLA, BACKGROUND_TASK
    }

    /** Immutable, content-free execution attribution captured when an audit entry is built. */
    public record Origin(Source source, String executionMode, Long definitionId, Long runId) {
        public Origin {
            Objects.requireNonNull(source, "source");
            if (!"user".equals(executionMode) && !"system".equals(executionMode)) {
                throw new IllegalArgumentException("Unknown execution mode");
            }
            if (source == Source.RULE || source == Source.WORKFLOW) {
                if (definitionId == null || definitionId <= 0 || runId == null || runId <= 0) {
                    throw new IllegalArgumentException("Automation requires definition and run ids");
                }
            } else if (definitionId != null || runId != null) {
                throw new IllegalArgumentException("Background work cannot claim automation ids");
            }
        }

        /** Identifies one execution of a legacy rule. */
        public static Origin rule(int ruleId, int executionId, String executionMode) {
            return new Origin(Source.RULE, executionMode, (long) ruleId, (long) executionId);
        }

        /** Identifies one canonical workflow run. */
        public static Origin workflow(int workflowId, long runId, String executionMode) {
            return new Origin(Source.WORKFLOW, executionMode, (long) workflowId, runId);
        }

        /** Identifies background or system work without inventing a rule or workflow run. */
        public static Origin task(Source source, String executionMode) {
            return new Origin(source, executionMode, null, null);
        }

        /** Returns the bounded metadata envelope stored under audit context's executionOrigin key. */
        public Map<String, Object> context() {
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("source", source.name().toLowerCase(Locale.ROOT));
            metadata.put("automation", source == Source.RULE || source == Source.WORKFLOW);
            metadata.put("executionMode", executionMode);
            if (source == Source.RULE) {
                metadata.put("ruleId", definitionId);
                metadata.put("ruleExecutionId", runId);
            } else if (source == Source.WORKFLOW) {
                metadata.put("workflowId", definitionId);
                metadata.put("workflowRunId", runId);
            }
            return Collections.unmodifiableMap(metadata);
        }
    }
}
