package ooo.klae.connex.backend.dto;

import java.util.List;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * API representation of a {@link ooo.klae.connex.backend.beans.Rule} with its trigger, optional WHEN
 * condition, and THEN actions deserialized into their typed shapes.
 */
@Data
@NoArgsConstructor
public class RuleDto {
    private int id;
    private String name;
    private String description;
    private boolean enabled;
    private String recordType;
    private RuleTrigger trigger;
    private SegmentDefinition condition;
    private List<RuleAction> actions;
    private String executionMode;
    private Integer runAsUserId;
    private Integer createdById;
    private String createdAt;
    private String updatedAt;
}
