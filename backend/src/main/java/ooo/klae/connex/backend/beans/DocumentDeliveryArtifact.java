package ooo.klae.connex.backend.beans;

import java.time.LocalDateTime;

import lombok.Data;
import lombok.NoArgsConstructor;

/** Metadata for one immutable managed document-delivery artifact. */
@Data
@NoArgsConstructor
public class DocumentDeliveryArtifact {
    private int id;
    private int workspaceId;
    private int deliveryId;
    private String kind;
    private String objectKey;
    private String contentType;
    private long byteLength;
    private String sha256;
    private LocalDateTime createdAt;
}
