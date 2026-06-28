package ooo.klae.connex.backend.beans;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * An automation rule: a trigger (an entity-change event or a time-based schedule) combined with an
 * optional WHEN condition and one or more THEN actions, scoped to a workspace. {@code triggerConfig},
 * {@code conditionJson}, and {@code actionsJson} are JSON blobs serialized from the typed DTO shapes;
 * {@code executionMode} is {@code user} (runs as {@code runAsUserId}) or {@code system}. Mapped via
 * {@code RuleMapper} / {@code RuleMapper.xml}.
 */
@Data
@NoArgsConstructor
public class Rule {
    private int id;
    private int workspaceId;
    private String name;
    private String description;
    private boolean enabled;
    private String recordType;
    private String triggerType;
    private String triggerConfig;
    private String conditionJson;
    private String actionsJson;
    private String executionMode;
    private Integer runAsUserId;
    private Integer createdById;
    private String createdAt;
    private String updatedAt;
}
