package ooo.klae.connex.backend.beans;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A single fire of a {@link Rule}: its outcome ({@code matched}, {@code skipped}, or {@code failed}),
 * an idempotency {@code dedupeKey} (unique per rule), and a JSON {@code detail} of what each action
 * did. Provides the rule engine's idempotency guard and audit trail. Mapped via {@code RuleMapper}.
 */
@Data
@NoArgsConstructor
public class RuleExecution {
    private int id;
    private int workspaceId;
    private int ruleId;
    private String triggerEntityType;
    private Integer triggerEntityId;
    private String status;
    private String dedupeKey;
    private String detail;
    private String executedAt;
}
