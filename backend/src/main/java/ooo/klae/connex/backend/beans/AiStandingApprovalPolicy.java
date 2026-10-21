package ooo.klae.connex.backend.beans;

import lombok.Data;
import lombok.NoArgsConstructor;

/** Organization-owned configuration; contains no member-authored approval or execution content. */
@Data
@NoArgsConstructor
public class AiStandingApprovalPolicy {
    private int orgId;
    private boolean enabled;
    private int allowedToolMask;
    private int maximumDailyCap;
    private int maximumPerRunCap;
    private long stateVersion;
    private long killEpoch;
}
