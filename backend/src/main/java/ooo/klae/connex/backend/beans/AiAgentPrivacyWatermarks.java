package ooo.klae.connex.backend.beans;

import lombok.Data;
import lombok.NoArgsConstructor;

/** Fixed committed identifier bounds captured once during privacy invalidation. */
@Data
@NoArgsConstructor
public class AiAgentPrivacyWatermarks {
    private int maxRunId;
    private int maxSessionId;
    private int maxTurnId;
    private int maxMessageId;
    private int maxToolCallId;
}
