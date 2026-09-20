package ooo.klae.connex.backend.mappers;

import java.util.Collection;
import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import ooo.klae.connex.backend.beans.AiRunLeaseRow;

/**
 * Tenant-local persistence for the subject-polymorphic AI run lease.
 *
 * <p>No statement here references any subject table. That is load-bearing twice over: it keeps the
 * documented lock order {@code ai_chat_session → ai_chat_turn → ai_run_lease} true with the lease
 * always last, and it keeps the mechanism reusable by a future agent-run subject without a second
 * mapper. Cross-subject questions travel through
 * {@link ooo.klae.connex.backend.ai.lease.AiRunLeaseSubjectHandler} instead.
 *
 * <p>Every deadline is computed by MySQL as {@code DATE_ADD(CURRENT_TIMESTAMP(6), …)}; no JVM
 * instant is ever bound into a timestamp column, so instance clock skew cannot move a lease
 * deadline.
 */
@Mapper
public interface AiRunLeaseMapper {

    /**
     * Locks one lease row for the remainder of the caller's transaction, or returns {@code null}
     * when the subject has never been leased.
     *
     * @param workspaceId tenant key
     * @param subjectKind stable wire key of the leasable subject kind
     * @param subjectId the subject's identifier within the workspace
     * @return the locked row, or {@code null} when no row exists
     */
    AiRunLeaseRow lockForUpdate(
            @Param("workspaceId") int workspaceId,
            @Param("subjectKind") String subjectKind,
            @Param("subjectId") long subjectId);

    /**
     * Inserts the first lease for one subject at epoch 1.
     *
     * @param workspaceId tenant key
     * @param subjectKind stable wire key of the leasable subject kind
     * @param subjectId the subject's identifier within the workspace
     * @param owner the claiming instance's owner id
     * @param ttlSeconds lease lifetime, applied by MySQL
     * @return rows inserted
     */
    int insert(
            @Param("workspaceId") int workspaceId,
            @Param("subjectKind") String subjectKind,
            @Param("subjectId") long subjectId,
            @Param("owner") String owner,
            @Param("ttlSeconds") int ttlSeconds);

    /**
     * Claims a tombstoned or expired lease at {@code epoch + 1}. A live held lease matches nothing.
     *
     * <p>Callers must already hold the row lock from {@link #lockForUpdate}, which is what makes
     * the resulting epoch predictable as the locked row's epoch plus one.
     *
     * @param workspaceId tenant key
     * @param subjectKind stable wire key of the leasable subject kind
     * @param subjectId the subject's identifier within the workspace
     * @param owner the claiming instance's owner id
     * @param ttlSeconds lease lifetime, applied by MySQL
     * @return rows updated
     */
    int takeOver(
            @Param("workspaceId") int workspaceId,
            @Param("subjectKind") String subjectKind,
            @Param("subjectId") long subjectId,
            @Param("owner") String owner,
            @Param("ttlSeconds") int ttlSeconds);

    /**
     * Claims an expired lease held at exactly {@code expectedEpoch}, at {@code epoch + 1}.
     *
     * <p>The epoch predicate is the fence: a settler that lost the race to another instance matches
     * zero rows rather than stealing a lease that has already moved on.
     *
     * @param workspaceId tenant key
     * @param subjectKind stable wire key of the leasable subject kind
     * @param subjectId the subject's identifier within the workspace
     * @param owner the settling instance's owner id
     * @param expectedEpoch the epoch the settler observed
     * @param ttlSeconds settlement lease lifetime, applied by MySQL
     * @return rows updated
     */
    int takeOverForSettlement(
            @Param("workspaceId") int workspaceId,
            @Param("subjectKind") String subjectKind,
            @Param("subjectId") long subjectId,
            @Param("owner") String owner,
            @Param("expectedEpoch") long expectedEpoch,
            @Param("ttlSeconds") int ttlSeconds);

    /**
     * Extends one lease, fenced on the holder's owner id and epoch.
     *
     * @param workspaceId tenant key
     * @param subjectKind stable wire key of the leasable subject kind
     * @param subjectId the subject's identifier within the workspace
     * @param owner the holder's owner id
     * @param epoch the holder's fencing epoch
     * @param ttlSeconds lease lifetime, applied by MySQL
     * @return rows updated; {@code 0} means the holder lost the lease
     */
    int renew(
            @Param("workspaceId") int workspaceId,
            @Param("subjectKind") String subjectKind,
            @Param("subjectId") long subjectId,
            @Param("owner") String owner,
            @Param("epoch") long epoch,
            @Param("ttlSeconds") int ttlSeconds);

    /**
     * Releases one lease by tombstoning it — owner cleared, {@code released_at} set, row retained —
     * fenced on the holder's owner id and epoch.
     *
     * <p>The row is deliberately not deleted: retaining it is what keeps {@code epoch} strictly
     * increasing per key, which is what makes the {@code (owner, epoch)} pair a real fence across
     * repeated claims of the same subject by the same instance.
     *
     * @param workspaceId tenant key
     * @param subjectKind stable wire key of the leasable subject kind
     * @param subjectId the subject's identifier within the workspace
     * @param owner the holder's owner id
     * @param epoch the holder's fencing epoch
     * @return rows updated; {@code 0} means the holder had already lost the lease
     */
    int tombstone(
            @Param("workspaceId") int workspaceId,
            @Param("subjectKind") String subjectKind,
            @Param("subjectId") long subjectId,
            @Param("owner") String owner,
            @Param("epoch") long epoch);

    /**
     * Retires one subject's held lease by key alone, without the holder's fencing token.
     *
     * <p>Unlike {@link #tombstone}, this matches whichever instance holds the row. It exists for
     * the terminal write that lands on an instance holding no token — a cancel or a lazy expiry
     * routed to a different instance from the one that claimed the run — where the durable
     * terminal state the caller has just committed, rather than a token, is the proof that no
     * owner may act on the subject any more. Callers must have changed the subject's terminal row
     * in the same transaction first.
     *
     * <p>{@code epoch} is retained exactly as the fenced tombstone retains it, so the key's
     * fencing epoch stays strictly increasing across repeated claims.
     *
     * @param workspaceId tenant key
     * @param subjectKind stable wire key of the leasable subject kind
     * @param subjectId the subject's identifier within the workspace
     * @return rows updated; {@code 0} means the row was already released or absent
     */
    int retire(
            @Param("workspaceId") int workspaceId,
            @Param("subjectKind") String subjectKind,
            @Param("subjectId") long subjectId);

    /**
     * Lists held leases in one workspace whose deadline has passed, oldest first, restricted to
     * the subject kinds the caller can actually settle.
     *
     * <p>The subject-kind predicate bounds the discovery window rather than filtering it after the
     * fact. A lease whose kind no handler on this binary owns can neither be settled nor retired —
     * an owner of that kind may still be alive on another binary — so leaving it in the page would
     * let it hold the head of an oldest-first ordering permanently and spend the settlement budget
     * on every pass.
     *
     * @param workspaceId tenant key
     * @param subjectKinds wire keys the caller can settle; never empty
     * @param limit maximum rows returned
     * @return expired held leases of the requested kinds
     */
    List<AiRunLeaseRow> findExpiredLeases(
            @Param("workspaceId") int workspaceId,
            @Param("subjectKinds") Collection<String> subjectKinds,
            @Param("limit") int limit);

    /**
     * Enumerates the next page of workspaces holding an expired lease, for catalog-pinned
     * background fan-out. Returns workspace references only, never tenant content.
     *
     * @param afterWorkspaceId exclusive cursor; {@code 0} starts a pass
     * @param limit maximum workspace ids returned
     * @return ascending workspace ids
     */
    List<Integer> workspaceIdsWithExpiredLeases(
            @Param("afterWorkspaceId") int afterWorkspaceId, @Param("limit") int limit);

    /**
     * Enumerates the next page of workspaces holding a tombstone older than the retention window,
     * for catalog-pinned background fan-out. Returns workspace references only, never tenant
     * content.
     *
     * <p>Deliberately separate from {@link #workspaceIdsWithExpiredLeases}. A workspace whose runs
     * all settle normally never holds an expired lease and would therefore never be visited by that
     * page, while it still accumulates one tombstone per run it completes. Reaping off its own
     * enumeration is what keeps the table's growth bounded by the retention window rather than by
     * lifetime run volume.
     *
     * <p>It takes the same subject kinds {@link #deleteTombstones} does, and that agreement is
     * load-bearing rather than tidy: a discovery that returned a workspace whose only aged tombstone
     * belongs to a kind the delete refuses to touch would return that workspace on every cursor
     * cycle, for the life of the tenant, and delete nothing each time.
     *
     * @param afterWorkspaceId exclusive cursor; {@code 0} starts a pass
     * @param subjectKinds wire keys of the subject kinds that may be reaped; never empty
     * @param retentionSeconds minimum tombstone age, applied by MySQL
     * @param limit maximum workspace ids returned
     * @return ascending workspace ids
     */
    List<Integer> workspaceIdsWithReapableTombstones(
            @Param("afterWorkspaceId") int afterWorkspaceId,
            @Param("subjectKinds") Collection<String> subjectKinds,
            @Param("retentionSeconds") int retentionSeconds,
            @Param("limit") int limit);

    /**
     * Deletes tombstones older than the retention window in one workspace, for the subject kinds
     * whose runs are provably shorter than that window.
     *
     * <p>The subject-kind predicate is load-bearing: deleting a tombstone restarts that key's
     * fencing epoch at 1, so a kind whose runs may outlive the retention must keep its tombstone
     * rather than have its fence reset under a still-live owner.
     *
     * @param workspaceId tenant key
     * @param subjectKinds wire keys of the subject kinds that may be reaped; never empty
     * @param retentionSeconds minimum tombstone age, applied by MySQL
     * @param limit maximum rows deleted
     * @return rows deleted
     */
    int deleteTombstones(
            @Param("workspaceId") int workspaceId,
            @Param("subjectKinds") Collection<String> subjectKinds,
            @Param("retentionSeconds") int retentionSeconds,
            @Param("limit") int limit);
}
