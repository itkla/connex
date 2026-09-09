package ooo.klae.connex.backend.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;

/** A typed workflow value binding from launch, record, or earlier step state. */
@JsonInclude(Include.NON_NULL)
public record WorkflowValueRef(
    String source,
    String key,
    String field,
    String nodeId,
    String output
) { }
