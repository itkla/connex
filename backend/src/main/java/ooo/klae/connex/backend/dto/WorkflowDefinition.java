package ooo.klae.connex.backend.dto;

import java.util.List;

/** The schema-v1 typed workflow graph persisted in draft and version definitions. */
public record WorkflowDefinition(
    int schemaVersion,
    String entryNodeId,
    List<WorkflowNode> nodes,
    List<WorkflowEdge> edges
) { }
