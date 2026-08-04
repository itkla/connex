package ooo.klae.connex.backend.dto;

/** Result of an idempotent workflow run cancellation or manual retry request. */
public record WorkflowRunOperationDto(
    String runKey,
    String status,
    boolean cancellationRequested
) { }
