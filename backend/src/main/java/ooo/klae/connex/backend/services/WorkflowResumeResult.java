package ooo.klae.connex.backend.services;

/** Observable result of resuming one canonical run. */
public enum WorkflowResumeResult {
    RUNNING,
    SUCCEEDED,
    FAILED,
    NO_OP
}
