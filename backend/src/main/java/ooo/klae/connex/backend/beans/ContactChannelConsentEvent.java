package ooo.klae.connex.backend.beans;

import java.time.LocalDateTime;

import lombok.Data;
import lombok.NoArgsConstructor;

/** Append-only history event for a contact-channel consent change. */
@Data
@NoArgsConstructor
public class ContactChannelConsentEvent {
    private int id;
    private int workspaceId;
    private int consentId;
    private int personId;
    private String channel;
    private String purpose;
    private String status;
    private String source;
    private String evidenceRef;
    private Integer createdById;
    private LocalDateTime createdAt;
}
