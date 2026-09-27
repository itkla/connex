package ooo.klae.connex.backend.beans;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One persisted {@code ai_run_lease} row.
 *
 * <p>Named {@code …Row} rather than {@code AiRunLease} because it is the raw table projection, not
 * the fencing token an owner carries: the token is
 * {@link ooo.klae.connex.backend.ai.lease.AiRunLease}, which holds only the key, the owner, and the
 * epoch that the database fences on.
 *
 * <p>Timestamps are {@link String} to match the sibling AI beans and because no lease deadline is
 * ever computed in the JVM — every {@code acquired_at}, {@code heartbeat_at}, {@code expires_at},
 * and {@code released_at} value is written by MySQL and read back only for diagnostics.
 */
@Data
@NoArgsConstructor
public class AiRunLeaseRow {
    private int workspaceId;
    private String subjectKind;
    private long subjectId;
    private String owner;
    private long epoch;
    private String acquiredAt;
    private String heartbeatAt;
    private String expiresAt;
    private String releasedAt;
}
