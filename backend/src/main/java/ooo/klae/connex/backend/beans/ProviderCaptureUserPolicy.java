package ooo.klae.connex.backend.beans;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One user's capture preferences within a workspace policy ceiling.
 */
@Data
@NoArgsConstructor
public class ProviderCaptureUserPolicy {
    private int workspaceId;
    private int userId;
    private String provider;
    private boolean enabled;
    private boolean calendarEnabled;
    private boolean mailInboxEnabled;
    private boolean mailSentEnabled;
    private int backfillDays;
    private boolean includeBodies;
    private String admissionMode;
    private String excludedPeopleJson;
    private String excludedConversationsJson;
    private long version;
    private String createdAt;
    private String updatedAt;
}
