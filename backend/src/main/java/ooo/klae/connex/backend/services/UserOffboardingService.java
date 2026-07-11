package ooo.klae.connex.backend.services;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.exceptions.ConflictException;
import ooo.klae.connex.backend.mappers.ActivityMapper;
import ooo.klae.connex.backend.mappers.AttachmentMapper;
import ooo.klae.connex.backend.mappers.DealMapper;
import ooo.klae.connex.backend.mappers.IntroductionMapper;
import ooo.klae.connex.backend.mappers.NoteMapper;
import ooo.klae.connex.backend.mappers.NotificationMapper;
import ooo.klae.connex.backend.mappers.RuleMapper;
import ooo.klae.connex.backend.mappers.SavedViewMapper;
import ooo.klae.connex.backend.mappers.ShareMapper;
import ooo.klae.connex.backend.mappers.TaskMapper;
import ooo.klae.connex.backend.mappers.UserDashboardMapper;

/**
 * Service-layer replacement for the database-level fan-out that account
 * deletion used to get from cross-plane foreign keys (#440 increment 3). The
 * control plane must never depend on org-data constraints, so the guard that
 * mirrored ON DELETE RESTRICT and the cleanup that mirrored ON DELETE
 * CASCADE / SET NULL live here instead. Every statement is deliberately
 * cross-workspace: authored content and references survive in workspaces the
 * user has already left. This class is the seam where Phase 4 (#313) will
 * iterate per-org catalogs instead of one shared schema — note that the
 * caller's single-transaction atomicity (and the guard's gap-lock
 * serialization) hold only while everything shares one database; the Phase 4
 * rewrite must redesign the caller's atomicity model, not just this class.
 */
@Service
@RequiredArgsConstructor
public class UserOffboardingService {

    private final NoteMapper noteMapper;
    private final ActivityMapper activityMapper;
    private final IntroductionMapper introductionMapper;
    private final NotificationMapper notificationMapper;
    private final DealMapper dealMapper;
    private final TaskMapper taskMapper;
    private final AttachmentMapper attachmentMapper;
    private final RuleMapper ruleMapper;
    private final ShareMapper shareMapper;
    private final SavedViewMapper savedViewMapper;
    private final UserDashboardMapper userDashboardMapper;

    /**
     * Refuses deletion while the user still owns authored content, mirroring
     * the {@code note.author_id} / {@code activity.created_by_id} /
     * {@code introduction.introducer_user_id} RESTRICT constraints: authored
     * history must be reassigned or removed before the account can go.
     *
     * <p>The counts are locking reads ({@code FOR UPDATE}): under InnoDB
     * repeatable-read, the equality scan on each author index takes gap locks
     * that block a concurrent insert of authored content for this user until
     * the deletion transaction commits — the serialization the RESTRICT
     * constraints provided at the parent row (same idiom as
     * {@code lockOwnerIds} in the sole-owner guard). Runs FIRST in the
     * deletion flow so a refused deletion does no doomed erasure work; the gap
     * locks are held until commit, so the race stays closed through the
     * erasure and the {@code app_user} delete. One asymmetry with RESTRICT
     * remains: a concurrent insert the gap lock blocks will succeed after this
     * transaction commits (RESTRICT would have failed it), so the follow-up
     * increment must decide between insert-time author validation and
     * tolerated-dangling semantics. The user-reference indexes pinned by
     * {@code OffboardingIndexArchTest} must survive the FK drop.
     *
     * @param userId the account being deleted
     * @throws ConflictException when authored content still exists anywhere
     */
    public void assertNoAuthoredContent(int userId) {
        int notes = noteMapper.countAuthoredAnywhere(userId);
        int activities = activityMapper.countCreatedAnywhere(userId);
        int introductions = introductionMapper.countIntroducedAnywhere(userId);
        if (notes > 0 || activities > 0 || introductions > 0) {
            throw new ConflictException(
                "Account still owns authored content (" + notes + " notes, " + activities
                    + " activities, " + introductions + " introductions); reassign or delete it first");
        }
    }

    /**
     * Detaches a departing member's content within one workspace the way the
     * dropped cross-plane constraints used to: tasks are unassigned and deal
     * ownership cleared (SET NULL) so authored history survives, while the
     * member's notifications and deal-collaborator seats are deleted (CASCADE).
     * Per-workspace twin of {@link #eraseOrgDataReferences(int)}; called by the
     * membership removal flows inside their transaction.
     *
     * @param workspaceId the workspace the member is leaving
     * @param userId the departing member
     */
    public void detachMemberContent(int workspaceId, int userId) {
        taskMapper.unassignMemberTasks(workspaceId, userId);
        dealMapper.clearMemberDealOwnership(workspaceId, userId);
        dealMapper.removeCollaboratorFromWorkspace(workspaceId, userId);
        notificationMapper.deleteAllForRecipient(workspaceId, userId);
    }

    /**
     * Erases or detaches every org-data reference to the user, in the same
     * shape the dropped constraints had: personal artifacts are deleted
     * (CASCADE — saved views, dashboards, notifications, collaborator seats)
     * and shared-history references are nulled (SET NULL — deal ownership,
     * task assignment, uploader, notification actor, rule principals, share
     * grantors). Statements are grouped deletes-then-nulls for readability;
     * no data dependency exists between them, so the order is otherwise
     * immaterial. Must run inside the caller's deletion transaction.
     *
     * @param userId the account being deleted
     */
    public void eraseOrgDataReferences(int userId) {
        savedViewMapper.deleteForUserAnywhere(userId);
        userDashboardMapper.deleteForUserAnywhere(userId);
        notificationMapper.deleteAllForRecipientAnywhere(userId);
        dealMapper.removeCollaboratorAnywhere(userId);
        notificationMapper.clearActorAnywhere(userId);
        dealMapper.clearOwnershipAnywhere(userId);
        taskMapper.unassignAnywhere(userId);
        attachmentMapper.clearUploaderAnywhere(userId);
        ruleMapper.clearRunAsAnywhere(userId);
        ruleMapper.clearCreatedByAnywhere(userId);
        shareMapper.clearCompanyShareGrantedByAnywhere(userId);
        shareMapper.clearPersonShareGrantedByAnywhere(userId);
        shareMapper.clearPipelineShareGrantedByAnywhere(userId);
    }
}
