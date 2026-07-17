package ooo.klae.connex.backend.beans;

import java.time.LocalDateTime;

import lombok.Data;
import lombok.NoArgsConstructor;

/** An append-only campaign delivery lifecycle event. */
@Data
@NoArgsConstructor
public class CampaignDeliveryEvent {
    private int id;
    private int workspaceId;
    private int deliveryId;
    private String eventType;
    private String detail;
    private LocalDateTime createdAt;
}
