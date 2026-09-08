package ooo.klae.connex.backend.dto;

/** A bounded correlated event wait. */
public record WorkflowWaitConfig(
    String kind,
    String event,
    Source source,
    Integer timeoutSeconds
) {

    /** The dominating step output that supplies the correlated record id. */
    public record Source(String nodeId, String output) { }
}
