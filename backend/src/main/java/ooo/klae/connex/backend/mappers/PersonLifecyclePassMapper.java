package ooo.klae.connex.backend.mappers;

import java.time.LocalDateTime;

import org.apache.ibatis.annotations.Param;

import ooo.klae.connex.backend.beans.PersonLifecyclePass;

/**
 * Mapper interface for lead-lifecycle passes (#559).
 * SQL is defined in {@code resources/mappers/PersonLifecyclePassMapper.xml}.
 * Used by {@code PersonLifecyclePassService}.
 */
public interface PersonLifecyclePassMapper {

    /** The contact's open pass, or {@code null} when it is not currently in the lifecycle. */
    PersonLifecyclePass getOpenPass(
        @Param("workspaceId") int workspaceId, @Param("personId") int personId);

    /** Opens a pass; generated id is written back to the bean. */
    int insert(PersonLifecyclePass pass);

    /**
     * Stamps a stage milestone on the contact's open pass, keeping the first occurrence.
     *
     * <p>Only the first is kept because a pass that reaches {@code QUALIFIED}, drops back to
     * {@code WORKING}, and qualifies again did so once as a cohort event; overwriting would move
     * the pass's latency to whichever attempt happened to be last.
     *
     * @param workspaceId owning workspace
     * @param personId contact whose open pass is stamped
     * @param stage milestone column to stamp
     * @param at when the milestone happened
     * @return rows updated
     */
    int stampMilestone(
        @Param("workspaceId") int workspaceId,
        @Param("personId") int personId,
        @Param("stage") String stage,
        @Param("at") LocalDateTime at);

    /** Closes the contact's open pass; a closed pass keeps everything recorded against it. */
    int closeOpenPass(
        @Param("workspaceId") int workspaceId,
        @Param("personId") int personId,
        @Param("endedAt") LocalDateTime endedAt);

    /**
     * Clears every historical owner reference to one account across all workspaces, for permanent
     * erasure. The pass keeps its outcome; only the person it was credited to is removed.
     *
     * @param userId account being erased
     * @return rows updated
     */
    int clearOwnerAnywhere(@Param("userId") int userId);

    /**
     * Copies the contact's live first-response clock onto its open pass, so the outcome survives the
     * clock being cleared when the pass ends.
     *
     * @param workspaceId owning workspace
     * @param personId contact whose open pass is updated
     * @return rows updated
     */
    int syncFirstResponse(
        @Param("workspaceId") int workspaceId, @Param("personId") int personId);
}
