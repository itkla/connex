package ooo.klae.connex.backend.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;

/** Cursor page of canonical workflow operations runs. */
@JsonInclude(Include.ALWAYS)
public record WorkflowOperationsRunPageDto(
    List<WorkflowOperationsRunDto> items,
    String nextCursor
) { }
