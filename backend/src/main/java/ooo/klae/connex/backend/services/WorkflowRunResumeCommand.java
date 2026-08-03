package ooo.klae.connex.backend.services;

/** Optimistic resume command used by the traversal runtime and future wait workers. */
public record WorkflowRunResumeCommand(
    int workspaceId,
    long runId,
    String expectedNodeId
) { }
