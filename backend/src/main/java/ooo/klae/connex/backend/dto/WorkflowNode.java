package ooo.klae.connex.backend.dto;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/** A typed node in a schema-v1 workflow graph. */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = WorkflowNode.Trigger.class, name = "TRIGGER"),
    @JsonSubTypes.Type(value = WorkflowNode.Condition.class, name = "CONDITION"),
    @JsonSubTypes.Type(value = WorkflowNode.Action.class, name = "ACTION"),
    @JsonSubTypes.Type(value = WorkflowNode.End.class, name = "END")
})
public sealed interface WorkflowNode {

    String id();

    /** A workflow entry trigger with the legacy-compatible trigger configuration. */
    record Trigger(String id, RuleTrigger config) implements WorkflowNode { }

    /** A conditional branch using the shared segment definition. */
    record Condition(String id, SegmentDefinition config) implements WorkflowNode { }

    /** One ordered workflow action using the current rule action vocabulary. */
    record Action(String id, RuleAction config) implements WorkflowNode { }

    /** A terminal workflow node. */
    record End(String id) implements WorkflowNode { }
}
