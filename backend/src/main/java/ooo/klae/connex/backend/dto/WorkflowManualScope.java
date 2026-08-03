package ooo.klae.connex.backend.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/** Closed exact-scope vocabulary for manual workflow invocations. */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "kind")
@JsonSubTypes({
    @JsonSubTypes.Type(value = WorkflowManualScope.SingleRecord.class, name = "single_record"),
    @JsonSubTypes.Type(value = WorkflowManualScope.PageSelection.class, name = "page_selection"),
    @JsonSubTypes.Type(value = WorkflowManualScope.ExplicitSelection.class, name = "explicit_selection"),
    @JsonSubTypes.Type(value = WorkflowManualScope.FilterMatch.class, name = "filter_match"),
    @JsonSubTypes.Type(value = WorkflowManualScope.SmartSegment.class, name = "smart_segment"),
    @JsonSubTypes.Type(value = WorkflowManualScope.SavedView.class, name = "saved_view"),
    @JsonSubTypes.Type(value = WorkflowManualScope.SearchSnapshot.class, name = "search_snapshot"),
    @JsonSubTypes.Type(value = WorkflowManualScope.CommandPalette.class, name = "command_palette")
})
public sealed interface WorkflowManualScope {

    /** One explicit record. */
    record SingleRecord(int recordId) implements WorkflowManualScope { }

    /** The exact ids selected from one already-loaded page. */
    record PageSelection(List<Integer> recordIds) implements WorkflowManualScope { }

    /** An exact explicit id selection. */
    record ExplicitSelection(List<Integer> recordIds) implements WorkflowManualScope { }

    /** A server-resolved native record filter. */
    record FilterMatch(WorkflowManualFilter filter) implements WorkflowManualScope { }

    /** A server-evaluated smart segment. */
    record SmartSegment(SegmentDefinition definition) implements WorkflowManualScope { }

    /** An accessible saved view resolved at preparation time. */
    record SavedView(int savedViewId) implements WorkflowManualScope { }

    /** A server-resolved search query snapshot. */
    record SearchSnapshot(String query) implements WorkflowManualScope { }

    /** Command-palette provenance around one concrete non-command scope. */
    record CommandPalette(WorkflowManualScope resolvedScope) implements WorkflowManualScope { }
}
