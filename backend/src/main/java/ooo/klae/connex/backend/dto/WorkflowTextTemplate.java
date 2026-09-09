package ooo.klae.connex.backend.dto;

import java.util.List;

/** A bounded schema-v2 text template and its optional-value behavior. */
public record WorkflowTextTemplate(List<WorkflowTextPart> parts, String missingValue) { }
