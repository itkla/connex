package ooo.klae.connex.backend.beans;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Durable cursor, lease, retry, and progress state for one capture stream.
 */
@Data
@NoArgsConstructor
public class ProviderCaptureSyncState {
    private long id;
    private int workspaceId;
    private int userId;
    private String provider;
    private String stream;
    private long credentialGeneration;
    private String status;
    private boolean initialSyncCompleted;
    private String stableCursor;
    private String pageCursor;
    private String leaseOwner;
    private String leaseUntil;
    private String reconciliationMarker;
    private String backfillStartedAt;
    private String lastAttemptAt;
    private String lastSuccessAt;
    private String nextAttemptAt;
    private long processedItems;
    private Long estimatedItems;
    private int consecutiveFailures;
    private String errorCode;
    private String createdAt;
    private String updatedAt;
}
