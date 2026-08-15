package ooo.klae.connex.backend.beans;

import java.time.LocalDateTime;

import lombok.Data;
import lombok.NoArgsConstructor;

/** One immutable document-delivery transition or provider callback ledger entry. */
@Data
@NoArgsConstructor
public class DocumentDeliveryEvent {
    private int id;
    private int workspaceId;
    private int deliveryId;
    private Integer recipientId;
    private String eventType;
    private String source;
    private String externalEventId;
    private String detail;
    private LocalDateTime occurredAt;
    private LocalDateTime createdAt;
}
