package ooo.klae.connex.backend.beans;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Workspace ceiling for one provider's connected capture.
 */
@Data
@NoArgsConstructor
public class ProviderCaptureWorkspacePolicy {
    private int workspaceId;
    private String provider;
    private boolean allowed;
    private boolean calendarAllowed;
    private boolean mailInboxAllowed;
    private boolean mailSentAllowed;
    private int maxBackfillDays;
    private boolean bodyCaptureAllowed;
    private boolean reviewRequired;
    private boolean excludePrivateEvents;
    private boolean excludeInternalOnly;
    private String excludedDomainsJson;
    private long version;
    private Integer updatedByUserId;
    private String createdAt;
    private String updatedAt;
}
