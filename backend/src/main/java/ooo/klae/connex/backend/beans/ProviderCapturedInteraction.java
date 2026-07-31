package ooo.klae.connex.backend.beans;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Provider-neutral immutable source envelope admitted into a workspace.
 */
@Data
@NoArgsConstructor
public class ProviderCapturedInteraction {
    private long id;
    private int workspaceId;
    private int userId;
    private String provider;
    private String stream;
    private String providerSourceId;
    private String providerConversationId;
    private byte[] sourceKeyHash;
    private String sourceVersion;
    private byte[] payloadHash;
    private String interactionType;
    private String subject;
    private String body;
    private String occurredAt;
    private String endedAt;
    private String visibility;
    private String admissionStatus;
    private String admittedFieldsJson;
    private String materialExclusionsJson;
    private long policyVersion;
    private long version;
    private String lastSeenReconciliationMarker;
    private String tombstonedAt;
    private String capturedAt;
    private String updatedAt;
}
