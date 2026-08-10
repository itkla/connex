package ooo.klae.connex.backend.services;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.exceptions.ConflictException;
import ooo.klae.connex.backend.mappers.ActivityMapper;
import ooo.klae.connex.backend.mappers.AiChatMapper;
import ooo.klae.connex.backend.mappers.AttachmentMapper;
import ooo.klae.connex.backend.mappers.CampaignMapper;
import ooo.klae.connex.backend.mappers.CompanyMapper;
import ooo.klae.connex.backend.mappers.ConsentMapper;
import ooo.klae.connex.backend.mappers.DealMapper;
import ooo.klae.connex.backend.mappers.DealDuplicateReviewProofMapper;
import ooo.klae.connex.backend.mappers.IntroductionMapper;
import ooo.klae.connex.backend.mappers.NoteMapper;
import ooo.klae.connex.backend.mappers.NotificationMapper;
import ooo.klae.connex.backend.mappers.PersonMapper;
import ooo.klae.connex.backend.mappers.ReportMapper;
import ooo.klae.connex.backend.mappers.SavedViewMapper;
import ooo.klae.connex.backend.mappers.SavedViewPreferenceMapper;
import ooo.klae.connex.backend.mappers.ShareMapper;
import ooo.klae.connex.backend.mappers.SuppressionMapper;
import ooo.klae.connex.backend.mappers.TaskMapper;
import ooo.klae.connex.backend.mappers.UserDashboardMapper;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.notifications.NotificationStateVersionService;
import ooo.klae.connex.backend.services.WorkflowOffboardingService.OffboardingPlan;
import ooo.klae.connex.backend.connectedaccounts.ConnectedAccountProviders;
import ooo.klae.connex.backend.connectedaccounts.capture.ProviderCapturePurgeService;

/**
 * Service-layer replacement for the database-level fan-out that account
 * deletion used to get from cross-plane foreign keys (#440 increment 3). The
 * control plane must never depend on org-data constraints, so the guard that
 * mirrored ON DELETE RESTRICT and the cleanup that mirrored ON DELETE
 * CASCADE / SET NULL live here instead. Every statement is deliberately
 * cross-workspace within the currently routed tenant catalog: authored content
 * and references survive in workspaces the user has already left. Account
 * deletion routes this boundary once per distinct catalog and retains an
 * owner-bound control-plane reservation across those transactions.
 */
@Service
@RequiredArgsConstructor
public class UserOffboardingService {

    record AccountNotificationLocks(
        List<Integer> actorRecipientIds,
        OffboardingPlan workflowPlan) {

        AccountNotificationLocks(List<Integer> actorRecipientIds) {
            this(actorRecipientIds, new OffboardingPlan(List.of(), List.of(), List.of()));
        }

        AccountNotificationLocks {
            actorRecipientIds = List.copyOf(new TreeSet<>(actorRecipientIds));
        }
    }

    private final NoteMapper noteMapper;
    private final ActivityMapper activityMapper;
    private final AiChatMapper aiChatMapper;
    private final IntroductionMapper introductionMapper;
    private final NotificationMapper notificationMapper;
    private final CompanyMapper companyMapper;
    private final PersonMapper personMapper;
    private final DealMapper dealMapper;
    private final DealDuplicateReviewProofMapper dealDuplicateReviewProofMapper;
    private final TaskMapper taskMapper;
    private final AttachmentMapper attachmentMapper;
    private final CampaignMapper campaignMapper;
    private final ConsentMapper consentMapper;
    private final ReportMapper reportMapper;
    private final ShareMapper shareMapper;
    private final SuppressionMapper suppressionMapper;
    private final SavedViewPreferenceMapper savedViewPreferenceMapper;
    private final SavedViewMapper savedViewMapper;
    private final UserDashboardMapper userDashboardMapper;
    private final UserMapper userMapper;
    private final WorkspaceMapper workspaceMapper;
    private final NotificationStateVersionService notificationStateVersionService;
    private final WorkflowOffboardingService workflowOffboardingService;
    private final ProviderCapturePurgeService providerCapturePurgeService;

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
     * Clears personal workspace data for a user who is about to receive a
     * brand-new membership. This removes saved views and preferences, assistant-chat
     * participant grants, notifications inserted while an earlier removal was
     * committing, and stale deal-collaborator seats. Chat sessions the user authored
     * are workspace data and are retained with their provenance intact, so a
     * returning member regains their own prior sessions. Guarded by a current locking read that proves no
     * membership row exists, so a pending invitee's legitimate data is never
     * touched and a stale repeatable-read snapshot cannot skip cleanup. Called
     * by every fresh-membership path: invites, invite links, and SSO JIT
     * provisioning.
     *
     * @param workspaceId the workspace being joined
     * @param userId the joining user
     */
    public void prepareFreshMembership(int workspaceId, int userId) {
        if (workspaceMapper.lockAuthorizationMembership(workspaceId, userId) == null) {
            providerCapturePurgeService.purge(
                workspaceId, userId, ConnectedAccountProviders.GOOGLE);
            providerCapturePurgeService.purge(
                workspaceId, userId, ConnectedAccountProviders.MICROSOFT);
            savedViewPreferenceMapper.deletePinsForFreshMembership(workspaceId, userId);
            savedViewPreferenceMapper.deleteDefaultsForFreshMembership(workspaceId, userId);
            savedViewMapper.deleteForFreshMembership(workspaceId, userId);
            dealDuplicateReviewProofMapper.deleteForActor(workspaceId, userId);
            aiChatMapper.deleteParticipantsForUser(workspaceId, userId);
            notificationMapper.deleteHistoricalNotificationBaselinesForRecipient(
                workspaceId, userId);
            notificationMapper.deleteAllForRecipient(workspaceId, userId);
            dealMapper.removeCollaboratorFromWorkspace(workspaceId, userId);
        }
    }

    /**
     * Detaches a departing member's content within one workspace the way the
     * dropped cross-plane constraints used to: tasks are unassigned and company,
     * contact, and deal ownership is cleared (SET NULL) so authored history survives,
     * while the member's saved-view preferences, owned saved views, assistant-chat
     * participant grants, notifications, and deal-collaborator seats are deleted.
     * Authored chat sessions are retained with {@code created_by_user_id} intact;
     * only permanent account erasure nulls that provenance.
     * Per-workspace twin of {@link #eraseOrgDataReferences(int)}; called by the
     * membership removal flows inside their transaction.
     *
     * @param workspaceId the workspace the member is leaving
     * @param userId the departing member
     */
    public void detachMemberContent(int workspaceId, int userId) {
        providerCapturePurgeService.purge(
            workspaceId, userId, ConnectedAccountProviders.GOOGLE);
        providerCapturePurgeService.purge(
            workspaceId, userId, ConnectedAccountProviders.MICROSOFT);
        notificationMapper.lockRecipientMemberships(userId);
        savedViewPreferenceMapper.deletePinsForUser(workspaceId, userId);
        savedViewPreferenceMapper.deleteDefaultsForUser(workspaceId, userId);
        savedViewMapper.deleteForUser(workspaceId, userId);
        dealDuplicateReviewProofMapper.deleteForActor(workspaceId, userId);
        aiChatMapper.deleteParticipantsForUser(workspaceId, userId);
        taskMapper.unassignMemberTasks(workspaceId, userId);
        companyMapper.clearMemberOwnership(workspaceId, userId);
        personMapper.clearMemberOwnership(workspaceId, userId);
        dealMapper.clearMemberDealOwnership(workspaceId, userId);
        campaignMapper.clearMemberOwnership(workspaceId, userId);
        dealMapper.removeCollaboratorFromWorkspace(workspaceId, userId);
        notificationMapper.deleteHistoricalNotificationBaselinesForRecipient(
            workspaceId, userId);
        notificationMapper.deleteAllForRecipient(workspaceId, userId);
    }

    /**
     * Erases or detaches every org-data reference to the user, in the same
     * shape the dropped constraints had: personal artifacts are deleted
     * (CASCADE — saved-view preferences, saved views, dashboards, assistant-chat grants,
     * notifications, collaborator seats)
     * and shared-history references are nulled (SET NULL — company, contact, deal, and
     * campaign ownership, assistant-chat provenance, task assignment, uploader,
     * notification actor, report and campaign actors, rule principals,
     * consent/suppression actors, share grantors).
     * Statements are grouped deletes-then-nulls
     * for readability; no data dependency exists between them, so the order is otherwise
     * immaterial. Must run inside the caller's deletion transaction.
     * Recipient memberships are locked in user-id order before notification
     * rows so concurrent inbox mutations use the same membership-to-notification
     * lock order. Recipients whose actor reference is cleared receive a
     * notification-state invalidation after commit.
     *
     * @param userId the account being deleted
     */
    public void eraseOrgDataReferences(int userId) {
        userMapper.lockById(userId);
        AccountNotificationLocks locks = snapshotAccountNotificationRecipients(userId);
        workflowOffboardingService.lockWorkspaceRoots(locks.workflowPlan());
        lockAccountNotificationRecipientMemberships(userId, locks);
        eraseOrgDataReferences(userId, locks);
    }

    AccountNotificationLocks snapshotAccountNotificationRecipients(int userId) {
        return new AccountNotificationLocks(
            notificationMapper.findRecipientIdsByActor(userId),
            workflowOffboardingService.discover(userId));
    }

    void lockAccountNotificationRecipientMemberships(int userId, AccountNotificationLocks locks) {
        Set<Integer> recipientIdsToLock = new TreeSet<>(locks.actorRecipientIds());
        recipientIdsToLock.add(userId);
        recipientIdsToLock.forEach(notificationMapper::lockRecipientMemberships);
    }

    List<Integer> workflowWorkspaceIds(AccountNotificationLocks locks) {
        return locks.workflowPlan().workspaceIds();
    }

    void eraseOrgDataReferences(int userId, AccountNotificationLocks locks) {
        workflowOffboardingService.offboard(userId, locks.workflowPlan());
        Set<Integer> actorRecipientIds = new TreeSet<>(locks.actorRecipientIds());
        actorRecipientIds.addAll(notificationMapper.lockRecipientIdsByActor(userId));
        savedViewPreferenceMapper.deletePinsForUserAnywhere(userId);
        savedViewPreferenceMapper.deleteDefaultsForUserAnywhere(userId);
        savedViewMapper.deleteForUserAnywhere(userId);
        dealDuplicateReviewProofMapper.deleteForActorAnywhere(userId);
        userDashboardMapper.deleteForUserAnywhere(userId);
        aiChatMapper.deleteParticipantsForUserAnywhere(userId);
        notificationMapper.deleteHistoricalNotificationBaselinesForRecipientAnywhere(userId);
        notificationMapper.deleteAllForRecipientAnywhere(userId);
        dealMapper.removeCollaboratorAnywhere(userId);
        int clearedActorRows = notificationMapper.clearActorAnywhere(userId);
        if (clearedActorRows > 0) {
            actorRecipientIds.stream()
                .filter(recipientId -> recipientId != userId)
                .forEach(notificationStateVersionService::markChanged);
        }
        companyMapper.clearOwnershipAnywhere(userId);
        personMapper.clearOwnershipAnywhere(userId);
        dealMapper.clearOwnershipAnywhere(userId);
        aiChatMapper.clearSessionCreatorsAnywhere(userId);
        aiChatMapper.clearMessageAuthorsAnywhere(userId);
        aiChatMapper.clearToolCallExecutorsAnywhere(userId);
        aiChatMapper.clearTurnRequestersAnywhere(userId);
        taskMapper.unassignAnywhere(userId);
        attachmentMapper.clearUploaderAnywhere(userId);
        campaignMapper.clearCampaignUserReferencesAnywhere(userId);
        campaignMapper.clearSnapshotCreatorsAnywhere(userId);
        consentMapper.clearEventCreatorsAnywhere(userId);
        reportMapper.clearDefinitionCreatorsAnywhere(userId);
        reportMapper.clearSnapshotGeneratorsAnywhere(userId);
        shareMapper.clearCompanyShareGrantedByAnywhere(userId);
        shareMapper.clearPersonShareGrantedByAnywhere(userId);
        shareMapper.clearPipelineShareGrantedByAnywhere(userId);
        suppressionMapper.clearCreatorsAnywhere(userId);
    }
}
