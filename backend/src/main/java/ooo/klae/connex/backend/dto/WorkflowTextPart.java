package ooo.klae.connex.backend.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;

/** One literal or referenced part of a schema-v2 text template. */
@JsonInclude(Include.NON_NULL)
public record WorkflowTextPart(String text, WorkflowValueRef ref) { }
