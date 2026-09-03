package ooo.klae.connex.backend.mappers;

import java.time.LocalDateTime;
import java.util.List;

import org.apache.ibatis.annotations.Param;

import ooo.klae.connex.backend.beans.Sequence;
import ooo.klae.connex.backend.beans.SequenceStep;
import ooo.klae.connex.backend.beans.SequenceStepContent;

/** Data access for workspace-scoped sequence templates and mutable draft steps. */
public interface SequenceMapper {
    /** Lists unarchived sequence roots visible to the actor in one workspace. */
    List<Sequence> getVisibleSequences(
            @Param("workspaceId") int workspaceId,
            @Param("userId") int userId);

    /** Returns one unarchived sequence root visible to the actor. */
    Sequence getVisibleSequence(
            @Param("workspaceId") int workspaceId,
            @Param("id") int id,
            @Param("userId") int userId);

    /** Locks and returns one sequence root visible to the actor, including archived roots. */
    Sequence getVisibleSequenceForUpdate(
            @Param("workspaceId") int workspaceId,
            @Param("id") int id,
            @Param("userId") int userId);

    /** Inserts a sequence root and populates its generated id. */
    int insertSequence(Sequence sequence);

    /** Updates mutable fields on an unarchived sequence root. */
    int updateSequence(Sequence sequence);

    /** Archives an unarchived sequence root. */
    int archiveSequence(
            @Param("workspaceId") int workspaceId,
            @Param("id") int id,
            @Param("updatedById") int updatedById,
            @Param("archivedAt") LocalDateTime archivedAt);

    /** Marks an unarchived sequence as having a published version. */
    int markPublished(
            @Param("workspaceId") int workspaceId,
            @Param("id") int id,
            @Param("updatedById") int updatedById);

    /** Lists ordered mutable draft steps for one sequence. */
    List<SequenceStep> getSteps(
            @Param("workspaceId") int workspaceId,
            @Param("sequenceId") int sequenceId);

    /** Retains shared locks on the current ordered draft while a version is published. */
    List<SequenceStep> getStepsForShare(
            @Param("workspaceId") int workspaceId,
            @Param("sequenceId") int sequenceId);

    /** Lists localized draft content for the supplied step ids. */
    List<SequenceStepContent> getStepContents(
            @Param("workspaceId") int workspaceId,
            @Param("stepIds") List<Long> stepIds);

    /** Retains shared locks on current localized draft content while a version is published. */
    List<SequenceStepContent> getStepContentsForShare(
            @Param("workspaceId") int workspaceId,
            @Param("stepIds") List<Long> stepIds);

    /** Deletes all mutable draft steps for one sequence. */
    int deleteDraftSteps(
            @Param("workspaceId") int workspaceId,
            @Param("sequenceId") int sequenceId);

    /** Inserts one mutable draft step and populates its generated id. */
    int insertStep(SequenceStep step);

    /** Inserts one localized content variant for a draft step. */
    int insertStepContent(SequenceStepContent content);

    /** Clears personal sequence ownership for a member leaving one workspace. */
    int clearMemberOwnership(
            @Param("workspaceId") int workspaceId,
            @Param("userId") int userId);

    /** Clears mutable sequence-root user references during all-catalog account erasure. */
    int clearSequenceUserReferencesAnywhere(@Param("userId") int userId);

    /** Clears mutable version-publisher attribution without updating immutable version rows. */
    int clearSequenceVersionPublishersAnywhere(@Param("userId") int userId);
}
