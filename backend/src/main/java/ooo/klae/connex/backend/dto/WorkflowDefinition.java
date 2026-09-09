package ooo.klae.connex.backend.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/** A versioned typed workflow graph persisted in draft and immutable versions. */
public record WorkflowDefinition(
    int schemaVersion,
    String entryNodeId,
    List<WorkflowNode> nodes,
    List<WorkflowEdge> edges,
    @JsonInclude(JsonInclude.Include.NON_EMPTY) List<WorkflowInputDefinition> inputs,
    @JsonInclude(JsonInclude.Include.NON_NULL) WorkflowEnrollment enrollment,
    @JsonInclude(JsonInclude.Include.NON_NULL) SegmentDefinition stopConditions
) {

    /** Preserves the exact schema-v1 constructor and serialized representation. */
    public WorkflowDefinition(
            int schemaVersion,
            String entryNodeId,
            List<WorkflowNode> nodes,
            List<WorkflowEdge> edges) {
        this(schemaVersion, entryNodeId, nodes, edges, null, null, null);
    }
}
