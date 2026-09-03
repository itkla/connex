package ooo.klae.connex.backend.beans;

import java.time.LocalDateTime;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A record of one push of a frozen audience snapshot's eligible included members to a third-party
 * marketing connector. Bound to an immutable snapshot; the frozen member ids retain the exact set
 * admitted by preparation and the pushed member ids retain the exact identities placed in the
 * provider request. Null member-id fields identify historical rows whose exact identities were not
 * recorded. The request's idempotency key is persisted with its attempt before provider egress so
 * transaction-C replay can be distinguished from a genuinely different outcome. Exact replay
 * requires equal attempts, persisted/supplied/attempted idempotency keys, status, total/pushed/failed
 * counts, and bounded outcome classification. A late provider result that exactly agrees with an
 * operator-classified terminal state is audited as an agreement without creating a contradiction
 * marker. The classification is persisted separately from diagnostic failure metadata. Null means
 * either that a draft/running row has no outcome yet or that a migrated legacy row lacks the member-
 * identity evidence introduced with classification. New running rows are leased so an interrupted
 * request is classified as ambiguous and flagged as reconciliation-required rather than being
 * retried silently. A running row with a null lease and no reconciliation timestamp is a legacy in-
 * flight write; it remains active and is never classified as stale automatically.
 */
@Data
@NoArgsConstructor
public class CampaignAudienceExport {
    private int id;
    private int workspaceId;
    private int campaignId;
    private int snapshotId;
    private String connector;
    private String externalListId;
    private String idempotencyKey;
    private String frozenMemberIdsJson;
    private String pushedMemberIdsJson;
    private String status;
    private int attempt;
    private LocalDateTime leaseUntil;
    private LocalDateTime reconciliationRequiredAt;
    private String failureReason;
    private String outcomeClassification;
    private String lateOutcome;
    private int totalMembers;
    private Integer pushedCount;
    private Integer failedCount;
    private Integer createdById;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
