package ooo.klae.connex.backend.dto;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonValue;

/** Read-only traversal prediction for one saved workflow draft and selected record. */
public record WorkflowSimulationDto(
    Result result,
    List<PathStep> path,
    List<Blocker> blockers
) {

    public WorkflowSimulationDto {
        path = List.copyOf(path);
        blockers = List.copyOf(blockers);
    }

    /** Terminal simulation classifications. */
    public enum Result {
        WOULD_COMPLETE,
        NOT_ENROLLED,
        WOULD_WAIT,
        BLOCKED;

        @JsonValue
        public String value() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** One speculative, side-effect-free node decision. */
    @JsonInclude(Include.ALWAYS)
    public record PathStep(
        String nodeId,
        String nodeType,
        String status,
        String outcome,
        String actionType,
        WorkflowDiagnosticCode code
    ) { }

    /** One bounded reason simulation cannot safely predict completion. */
    @JsonInclude(Include.ALWAYS)
    public record Blocker(
        WorkflowDiagnosticCode code,
        String nodeId,
        String fieldPath,
        Map<String, String> params
    ) {

        public Blocker {
            params = Map.copyOf(params);
        }

        /** Converts an authoritative validation or action diagnostic into a simulation blocker. */
        public static Blocker from(WorkflowDiagnosticDto diagnostic) {
            return new Blocker(
                diagnostic.code(),
                diagnostic.nodeId(),
                diagnostic.fieldPath(),
                diagnostic.params());
        }
    }
}
