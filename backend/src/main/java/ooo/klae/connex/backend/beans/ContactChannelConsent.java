package ooo.klae.connex.backend.beans;

import java.time.LocalDateTime;

import lombok.Data;
import lombok.NoArgsConstructor;

/** Current workspace-owned consent state for one person, channel, and purpose. */
@Data
@NoArgsConstructor
public class ContactChannelConsent {
    private int id;
    private int workspaceId;
    private int personId;
    private String channel;
    private String purpose;
    private String status;
    private String source;
    private String evidenceRef;
    private LocalDateTime capturedAt;
    private LocalDateTime updatedAt;
}
