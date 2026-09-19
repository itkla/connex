package ooo.klae.connex.backend.ai.lease;

import java.util.Objects;

/**
 * The primary key of one run lease: the tenant, the kind of subject, and the subject's identifier.
 *
 * @param workspaceId tenant key; leads the table's primary key and both indexes
 * @param subject the leasable subject kind
 * @param subjectId the subject's identifier within the workspace
 */
public record AiRunLeaseKey(int workspaceId, AiRunLeaseSubject subject, long subjectId) {

    /** Validates that a key names a real tenant and a real subject. */
    public AiRunLeaseKey {
        Objects.requireNonNull(subject, "subject");
        if (workspaceId <= 0) {
            throw new IllegalArgumentException("AI run lease workspace id must be positive");
        }
        if (subjectId <= 0) {
            throw new IllegalArgumentException("AI run lease subject id must be positive");
        }
    }
}
