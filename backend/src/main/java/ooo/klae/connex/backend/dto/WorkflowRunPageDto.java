package ooo.klae.connex.backend.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;

/** A bounded frozen-as-of page of canonical and legacy workflow runs. */
@JsonInclude(Include.ALWAYS)
public record WorkflowRunPageDto(
    List<WorkflowRunSummaryDto> items,
    String nextCursor
) { }
