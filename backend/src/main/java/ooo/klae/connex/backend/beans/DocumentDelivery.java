package ooo.klae.connex.backend.beans;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import lombok.Data;
import lombok.NoArgsConstructor;

/** One provider envelope bound to an immutable commercial-document version. */
@Data
@NoArgsConstructor
public class DocumentDelivery {
    private int id;
    private int workspaceId;
    private int dealId;
    private int documentId;
    private String provider;
    private String providerEnvelopeId;
    private String status;
    private String message;
    private LocalDateTime expiresAt;
    private Integer sentBy;
    private LocalDateTime sentAt;
    private LocalDateTime completedAt;
    private LocalDateTime terminatedAt;
    private String terminationReason;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private List<DocumentDeliveryRecipient> recipients = new ArrayList<>();
    private List<DocumentDeliveryEvent> events = new ArrayList<>();
    private List<DocumentDeliveryArtifact> artifacts = new ArrayList<>();
}
