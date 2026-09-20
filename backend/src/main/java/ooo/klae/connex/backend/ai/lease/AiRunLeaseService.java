package ooo.klae.connex.backend.ai.lease;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.IntConsumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DeadlockLoserDataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

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

    private static final Logger log = LoggerFactory.getLogger(AiRunLeaseService.class);

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
     * <p>A subject that has never been leased has no row to lock, and an absent primary key takes
     * no lock at READ COMMITTED, so two first claimants can both reach the insert. The loser is
     * refused by the primary key (or by the deadlock its lock wait resolves into) and is reported
     * as the same {@link ConflictException} a live lease produces, rather than as a raw data-access
     * failure no caller's contract describes.
     *
     * <p>This method also anchors the caller's self-fence, and the instant it anchors on is the
     * whole point: the guard's clock is read after the row lock has been granted and immediately
     * before the write that makes MySQL stamp {@code expires_at}. Anchoring earlier — when the
     * guard was constructed, or at the start of a claim that then waits behind the membership and
     * subject row locks — would spend that wait out of a lifetime the database has not started
     * counting yet, and could stop a healthy run before its first heartbeat. Anchoring later —
     * after the write, or when the heartbeat schedule starts — would put the local deadline after
     * the database's, so a worker paused between the two would answer healthy while a settler was
     * already entitled to take the run over. Only the instant just before the write is at or
     * before every deadline the database can assign.
     *
     * @param key the lease key
     * @param guard the claimant's ownership flag, anchored here on the instant the lease write is
     *     issued
     * @return the fencing token this instance now holds
     * @throws ConflictException when a live lease already holds the subject, or when a concurrent
     *     first claimant won the race to insert it
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public AiRunLease acquireInCurrentTransaction(AiRunLeaseKey key, AiRunLeaseGuard guard) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(guard, "guard");
        String owner = identity.owner();
        int ttlSeconds = seconds(properties.getRunLeaseTtl());
        AiRunLeaseRow existing = leaseMapper.lockForUpdate(
                key.workspaceId(), key.subject().wireKey(), key.subjectId());
        long epoch;
        guard.recordRenewal(guard.clockNanos());
        if (existing == null) {
            insertFirstClaim(key, owner, ttlSeconds);
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
        registerUntilRollback(lease);
        return lease;
    }

    private void insertFirstClaim(AiRunLeaseKey key, String owner, int ttlSeconds) {
        try {
            if (leaseMapper.insert(
                    key.workspaceId(),
                    key.subject().wireKey(),
                    key.subjectId(),
                    owner,
                    ttlSeconds) != 1) {
                throw new IllegalStateException("AI run lease insert affected no row");
            }
        } catch (DuplicateKeyException | DeadlockLoserDataAccessException race) {
            log.debug(
                    "Lost the race to insert the first AI run lease for workspace {} subject {} {}",
                    key.workspaceId(),
                    key.subject().wireKey(),
                    key.subjectId(),
                    race);
            throw new ConflictException("AI run is already leased by another instance");
        }
    }

    /**
     * Extends one lease in its own short transaction.
     *
     * <p>A renewal whose row has already passed its deadline is refused rather than resurrected,
     * and that refusal is in SQL because the database is the only clock every instance shares. An
     * expired row is one a settler is already entitled to take over, so letting a returning owner
     * push the deadline out would make "expired" mean nothing for as long as the owner kept
     * missing beats and winning the race to renew. With the claim's conservative anchor a
     * well-behaved owner has self-fenced long before this predicate can fire; it exists for the
     * one case the local anchor cannot cover, a monotonic clock that did not advance across a
     * suspended host.
     *
     * <p>It does not weaken the failure discipline this heartbeat rests on. A renewal that
     * <em>throws</em> is still unknown and still retried; only a renewal that ran and matched no
     * row is reported {@link AiRunLeaseOutcome#LOST}, and an expired row is exactly that — a row
     * this owner may no longer act on. The structural guard in {@code AiRunLeaseIntegrationTest}
     * is unaffected: the predicate reads {@code expires_at}, it does not assign it, and the
     * deadline this statement writes is still {@code GREATEST(DATE_ADD(CURRENT_TIMESTAMP(6), …))}.
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
     * Releases one subject's lease inside the caller's transaction, whichever instance holds it.
     *
     * <p>Callers invoke this from the same transaction as the durable terminal write and only when
     * that write changed a row. That row change, not the token, is the licence to release: the
     * caller has proved the subject reached a state no owner may act on any further.
     *
     * <p>When this instance holds the token the release is fenced on {@code (owner, epoch)}, so a
     * stale owner whose terminal write lost a race releases nothing. When it holds none the
     * release falls back to an unfenced retire by key. That fallback is not a convenience: the
     * token is JVM-local, so a cancel or a lazy expiry load-balanced to any other instance would
     * otherwise leave the row {@code owner IS NOT NULL} and {@code released_at IS NULL} forever —
     * the reap deletes only tombstones, so no pass in this design would ever remove it, and every
     * later sweep would rediscover an already-terminal run ahead of genuinely dead owners.
     *
     * <p>The token is forgotten only once the enclosing transaction commits. A terminal transaction
     * that rolls back leaves the row held and this instance still holding its token, so the retry
     * can tombstone it rather than abandoning a lease the database still shows as held.
     *
     * @param key the lease key
     * @return {@code true} when a held row was released
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean releaseHeldInCurrentTransaction(AiRunLeaseKey key) {
        Objects.requireNonNull(key, "key");
        Optional<AiRunLease> held = registry.find(key);
        if (held.isEmpty()) {
            return leaseMapper.retire(
                    key.workspaceId(), key.subject().wireKey(), key.subjectId()) == 1;
        }
        AiRunLease lease = held.get();
        int updated = leaseMapper.tombstone(
                key.workspaceId(),
                key.subject().wireKey(),
                key.subjectId(),
                lease.owner(),
                lease.epoch());
        forgetOnCommit(lease);
        return updated == 1;
    }

    /**
     * Drops this instance's local token for one subject without touching the database.
     *
     * <p>A worker calls this when it stops working on a run, so the token cannot outlive the work
     * it fences. Every other route out of the registry runs from a terminal write, and a run whose
     * terminal write never lands — a rejected terminal callback, a coordinator that threw, a
     * terminal update that changed no row because another settler got there first — would
     * otherwise leave its entry in the map for the life of the process.
     *
     * <p>Forgetting a token never releases the lease. The row stays held and expiring, which is
     * exactly the state the coverage invariant wants a killed or abandoned run to leave behind.
     *
     * @param lease the token to drop; ignored when {@code null} so callers need no branch
     */
    public void forgetLocalToken(AiRunLease lease) {
        if (lease == null) {
            return;
        }
        registry.forget(lease);
    }

    /**
     * Claims an expired lease observed at {@code expectedEpoch} so this instance may settle it.
     *
     * <p>The takeover token is registered exactly as a claim's is, because the settler releases it
     * through {@link #releaseHeldInCurrentTransaction(AiRunLeaseKey)} once it has written the
     * subject's terminal state. Without the registration that release would find no token, skip the
     * tombstone, and leave a held settlement lease for the next sweep pass to rediscover.
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
        AiRunLease takeover = new AiRunLease(key, owner, expectedEpoch + 1L);
        registerUntilRollback(takeover);
        return Optional.of(takeover);
    }

    /**
     * Deletes tombstones in one workspace that are older than the retention window, for the subject
     * kinds that declare themselves reapable.
     *
     * <p>A delete restarts that key's fencing epoch at 1, so it is sound only while the retention
     * provably exceeds the longest run of that kind. {@link AiRunLeaseSubject#CHAT_TURN} is bounded
     * by {@code generation-max-lifetime}, which the property validation refuses to let the retention
     * fall below. A kind with no declared bound keeps its tombstone instead: the reap fails closed
     * rather than resetting a fence under an owner that may still act.
     *
     * @param workspaceId tenant key
     * @param retentionSeconds minimum tombstone age
     * @param limit maximum rows deleted
     * @return rows deleted
     */
    @Transactional(isolation = Isolation.READ_COMMITTED, propagation = Propagation.REQUIRES_NEW)
    public int reapTombstones(int workspaceId, int retentionSeconds, int limit) {
        List<String> reapable = Arrays.stream(AiRunLeaseSubject.values())
                .filter(AiRunLeaseSubject::isTombstoneReapable)
                .map(AiRunLeaseSubject::wireKey)
                .toList();
        if (reapable.isEmpty()) {
            return 0;
        }
        return leaseMapper.deleteTombstones(workspaceId, reapable, retentionSeconds, limit);
    }

    private void registerUntilRollback(AiRunLease lease) {
        Runnable undoRegistration = registry.register(lease);
        afterCompletion(status -> {
            if (status != TransactionSynchronization.STATUS_COMMITTED) {
                undoRegistration.run();
            }
        });
    }

    private void forgetOnCommit(AiRunLease lease) {
        afterCompletion(status -> {
            if (status == TransactionSynchronization.STATUS_COMMITTED) {
                registry.forget(lease);
            }
        });
    }

    private static void afterCompletion(IntConsumer completion) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                completion.accept(status);
            }
        });
    }

    private static int seconds(Duration duration) {
        if (duration.toSeconds() < 1L || duration.getNano() != 0) {
            throw new IllegalArgumentException(
                    "AI run lease durations must be a whole number of seconds, at least one,"
                            + " because the database computes deadlines as INTERVAL n SECOND; got "
                            + duration);
        }
        return Math.toIntExact(duration.toSeconds());
    }
}
