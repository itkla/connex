package ooo.klae.connex.backend.beans;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One participant identity and its exact-match review state.
 */
@Data
@NoArgsConstructor
public class ProviderCapturedParticipant {
    private long id;
    private int workspaceId;
    private long interactionId;
    private String participantRole;
    private String displayName;
    private String email;
    private String normalizedEmail;
    private Integer personId;
    private String matchState;
    private String heldReason;
    private long version;
    private String provider;
    private String stream;
    private String interactionType;
    private String subject;
    private String occurredAt;
    private long interactionVersion;
    private String createdAt;
    private String updatedAt;
}
