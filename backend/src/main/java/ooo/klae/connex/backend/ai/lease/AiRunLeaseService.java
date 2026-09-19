package ooo.klae.connex.backend.ai.lease;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.ai.AiProperties;
import ooo.klae.connex.backend.beans.AiRunLeaseRow;
import ooo.klae.connex.backend.exceptions.ConflictException;
import ooo.klae.connex.backend.mappers.AiRunLeaseMapper;

/**
 * Transactional boundary over the subject-polymorphic AI run lease.
 *
 * <p>Two of these methods deliberately join the caller's transaction rather than opening their own.
 * {@link #acquireInCurrentTransaction(AiRunLeaseKey)} joins the claim that flips the subject to its
 * running state, so a claim that rolls back leaves no lease row; and
 * {@link #releaseHeldInCurrentTransaction(AiRunLeaseKey)} joins the durable terminal write, so a
 * claimed, non-terminal subject always has a lease row — held-live, held-expired, or tombstoned —
 * and never none. That coverage is what keeps the dead-owner detection bound honest.
 *
 * <p>No statement reached from here names a subject table; cross-subject questions travel through
 * {@link AiRunLeaseSubjectHandler}.
 */
@Service
@RequiredArgsConstructor
public class AiRunLeaseService {

    private final AiRunLeaseMapper leaseMapper;
    private final AiRunLeaseIdentity identity;
    private final AiRunLeaseRegistry registry;
    private final AiProperties properties;

    /**
     * Claims one subject's lease inside the caller's transaction, registering the resulting token.
     *
     * <p>A subject that has never been leased is inserted at epoch 1. A tombstoned or expired row
     * is taken over at {@code epoch + 1} under the row lock this method takes first, so the new
     * epoch is strictly greater than every epoch that key has ever carried. A live held lease is
     * refused: silently taking one over is exactly the failure the fencing scheme exists to
     * prevent.
     *
     * @param key the lease key
     * @return the fencing token this instance now holds
     * @throws ConflictException when a live lease already holds the subject
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public AiRunLease acquireInCurrentTransaction(AiRunLeaseKey key) {
        Objects.requireNonNull(key, "key");
        String owner = identity.owner();
        int ttlSeconds = seconds(properties.getRunLeaseTtl());
        AiRunLeaseRow existing = leaseMapper.lockForUpdate(
                key.workspaceId(), key.subject().wireKey(), key.subjectId());
        long epoch;
        if (existing == null) {
            if (leaseMapper.insert(
                    key.workspaceId(),
                    key.subject().wireKey(),
                    key.subjectId(),
                    owner,
                    ttlSeconds) != 1) {
                throw new IllegalStateException("AI run lease insert affected no row");
            }
            epoch = 1L;
        } else {
            if (leaseMapper.takeOver(
                    key.workspaceId(),
                    key.subject().wireKey(),
                    key.subjectId(),
                    owner,
                    ttlSeconds) != 1) {
                throw new ConflictException("AI run is already leased by another instance");
            }
            epoch = existing.getEpoch() + 1L;
        }
        AiRunLease lease = new AiRunLease(key, owner, epoch);
        registry.register(lease);
        return lease;
    }

    /**
     * Extends one lease in its own short transaction.
     *
     * @param lease the fencing token to extend
     * @return {@link AiRunLeaseOutcome#HELD} when the renewal matched, {@link
     *     AiRunLeaseOutcome#LOST} when it ran and matched nothing
     */
    @Transactional(isolation = Isolation.READ_COMMITTED, propagation = Propagation.REQUIRES_NEW)
    public AiRunLeaseOutcome renew(AiRunLease lease) {
        Objects.requireNonNull(lease, "lease");
        AiRunLeaseKey key = lease.key();
        int updated = leaseMapper.renew(
                key.workspaceId(),
                key.subject().wireKey(),
                key.subjectId(),
                lease.owner(),
                lease.epoch(),
                seconds(properties.getRunLeaseTtl()));
        return updated == 1 ? AiRunLeaseOutcome.HELD : AiRunLeaseOutcome.LOST;
    }

    /**
     * Tombstones the lease this instance holds for one subject, inside the caller's transaction.
     *
     * <p>Callers invoke this from the same transaction as the durable terminal write and only when
     * that write changed a row, so a stale owner whose terminal write lost to a takeover releases
     * nothing. A key this instance holds no token for is a correct no-op: it means a takeover has
     * already re-fenced the row.
     *
     * @param key the lease key
     * @return {@code true} when this instance's token matched and the row was tombstoned
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean releaseHeldInCurrentTransaction(AiRunLeaseKey key) {
        Objects.requireNonNull(key, "key");
        Optional<AiRunLease> held = registry.find(key);
        if (held.isEmpty()) {
            return false;
        }
        AiRunLease lease = held.get();
        int updated = leaseMapper.tombstone(
                key.workspaceId(),
                key.subject().wireKey(),
                key.subjectId(),
                lease.owner(),
                lease.epoch());
        registry.forget(key);
        return updated == 1;
    }

    /**
     * Claims an expired lease observed at {@code expectedEpoch} so this instance may settle it.
     *
     * @param key the lease key
     * @param expectedEpoch the epoch the settler observed
     * @return the settler's fencing token, or empty when the lease moved on before the takeover
     */
    @Transactional(isolation = Isolation.READ_COMMITTED, propagation = Propagation.REQUIRES_NEW)
    public Optional<AiRunLease> takeOverForSettlement(AiRunLeaseKey key, long expectedEpoch) {
        Objects.requireNonNull(key, "key");
        String owner = identity.owner();
        int updated = leaseMapper.takeOverForSettlement(
                key.workspaceId(),
                key.subject().wireKey(),
                key.subjectId(),
                owner,
                expectedEpoch,
                seconds(properties.getRunLeaseSettlementTtl()));
        if (updated != 1) {
            return Optional.empty();
        }
        return Optional.of(new AiRunLease(key, owner, expectedEpoch + 1L));
    }

    /**
     * Deletes tombstones in one workspace that are older than the retention window.
     *
     * <p>The configured retention is validated to exceed the maximum lifetime of any generation, so
     * no owner that could still write can outlive its own tombstone.
     *
     * @param workspaceId tenant key
     * @param retentionSeconds minimum tombstone age
     * @param limit maximum rows deleted
     * @return rows deleted
     */
    @Transactional(isolation = Isolation.READ_COMMITTED, propagation = Propagation.REQUIRES_NEW)
    public int reapTombstones(int workspaceId, int retentionSeconds, int limit) {
        return leaseMapper.deleteTombstones(workspaceId, retentionSeconds, limit);
    }

    private static int seconds(Duration duration) {
        return Math.toIntExact(duration.toSeconds());
    }
}
