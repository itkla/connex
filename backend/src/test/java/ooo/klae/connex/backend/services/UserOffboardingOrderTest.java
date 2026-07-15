package ooo.klae.connex.backend.services;

import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.mappers.ActivityMapper;
import ooo.klae.connex.backend.mappers.AttachmentMapper;
import ooo.klae.connex.backend.mappers.DealMapper;
import ooo.klae.connex.backend.mappers.IntroductionMapper;
import ooo.klae.connex.backend.mappers.NoteMapper;
import ooo.klae.connex.backend.mappers.NotificationMapper;
import ooo.klae.connex.backend.mappers.ReportMapper;
import ooo.klae.connex.backend.mappers.RuleMapper;
import ooo.klae.connex.backend.mappers.SavedViewMapper;
import ooo.klae.connex.backend.mappers.ShareMapper;
import ooo.klae.connex.backend.mappers.TaskMapper;
import ooo.klae.connex.backend.mappers.UserDashboardMapper;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.notifications.NotificationChangePublisher;
import ooo.klae.connex.backend.notifications.NotificationStateVersionService;

@ExtendWith(MockitoExtension.class)
class UserOffboardingOrderTest {
    @Mock private NoteMapper noteMapper;
    @Mock private ActivityMapper activityMapper;
    @Mock private IntroductionMapper introductionMapper;
    @Mock private NotificationMapper notificationMapper;
    @Mock private DealMapper dealMapper;
    @Mock private ReportMapper reportMapper;
    @Mock private TaskMapper taskMapper;
    @Mock private AttachmentMapper attachmentMapper;
    @Mock private RuleMapper ruleMapper;
    @Mock private ShareMapper shareMapper;
    @Mock private SavedViewMapper savedViewMapper;
    @Mock private UserDashboardMapper userDashboardMapper;
    @Mock private UserMapper userMapper;
    @Mock private NotificationStateVersionService stateVersionService;

    @InjectMocks private UserOffboardingService service;

    @Test
    void removeAndLeaveDetachmentLocksMembershipBeforeNotificationDeletion() {
        service.detachMemberContent(7, 9);

        InOrder order = inOrder(notificationMapper, taskMapper, dealMapper);
        order.verify(notificationMapper).lockRecipientMemberships(9);
        order.verify(taskMapper).unassignMemberTasks(7, 9);
        order.verify(dealMapper).clearMemberDealOwnership(7, 9);
        order.verify(dealMapper).removeCollaboratorFromWorkspace(7, 9);
        order.verify(notificationMapper).deleteAllForRecipient(7, 9);
        verifyNoInteractions(stateVersionService);
    }

    @Test
    void accountErasureLocksAllAffectedMembershipsBeforeNotificationsAndInvalidatesExactRecipients() {
        when(userMapper.lockById(9)).thenReturn(9);
        when(notificationMapper.findRecipientIdsByActor(9)).thenReturn(List.of(11, 3));
        when(notificationMapper.lockRecipientIdsByActor(9)).thenReturn(List.of(11, 5, 3));
        when(notificationMapper.clearActorAnywhere(9)).thenReturn(3);

        service.eraseOrgDataReferences(9);

        InOrder order = inOrder(userMapper, notificationMapper, stateVersionService);
        order.verify(userMapper).lockById(9);
        order.verify(notificationMapper).findRecipientIdsByActor(9);
        order.verify(notificationMapper).lockRecipientMemberships(3);
        order.verify(notificationMapper).lockRecipientMemberships(9);
        order.verify(notificationMapper).lockRecipientMemberships(11);
        order.verify(notificationMapper).lockRecipientIdsByActor(9);
        order.verify(notificationMapper).deleteAllForRecipientAnywhere(9);
        order.verify(notificationMapper).clearActorAnywhere(9);
        order.verify(stateVersionService).markChanged(3);
        order.verify(stateVersionService).markChanged(5);
        order.verify(stateVersionService).markChanged(11);
    }

    @Test
    void accountDeletionLocksRootsAndAllNotificationRecipientsBeforeOwnerRowsAndErasure() {
        UserMapper userMapper = mock(UserMapper.class);
        AuditService auditService = mock(AuditService.class);
        WorkspaceService workspaceService = mock(WorkspaceService.class);
        OrgMemberService orgMemberService = mock(OrgMemberService.class);
        NotificationChangePublisher notificationChanges = mock(NotificationChangePublisher.class);
        ReferenceService referenceService = mock(ReferenceService.class);
        UserOffboardingService offboardingService = mock(UserOffboardingService.class);
        UserOffboardingService.AccountNotificationLocks locks =
            new UserOffboardingService.AccountNotificationLocks(List.of(3, 11));
        User user = new User();
        user.setId(9);
        user.setUsername("target");
        when(workspaceService.lockOwnedWorkspaceRoots(9)).thenReturn(List.of(7));
        when(userMapper.lockById(9)).thenReturn(9);
        when(offboardingService.snapshotAccountNotificationRecipients(9)).thenReturn(locks);
        when(workspaceService.getCurrentWorkspaceId()).thenReturn(7);
        when(workspaceService.isMember(7, 9)).thenReturn(true);
        when(userMapper.getUserById(9)).thenReturn(user);
        UserService userService = new UserService(
            userMapper,
            activityMapper,
            noteMapper,
            taskMapper,
            auditService,
            workspaceService,
            orgMemberService,
            notificationChanges,
            referenceService,
            offboardingService,
            mock(ooo.klae.connex.backend.storage.ManagedObjectService.class)
        );

        userService.delete(9);

        InOrder order = inOrder(workspaceService, offboardingService, orgMemberService, userMapper);
        order.verify(workspaceService).requireSelf(9);
        order.verify(userMapper).lockById(9);
        order.verify(workspaceService).lockOwnedWorkspaceRoots(9);
        order.verify(offboardingService).snapshotAccountNotificationRecipients(9);
        order.verify(offboardingService).lockAccountNotificationRecipientMemberships(9, locks);
        order.verify(workspaceService).assertNotSoleOwnerOfWorkspaces(List.of(7));
        order.verify(orgMemberService).assertNotSoleOwnerOfAnyOrg(9);
        order.verify(offboardingService).assertNoAuthoredContent(9);
        order.verify(offboardingService).eraseOrgDataReferences(9, locks);
        order.verify(userMapper).delete(9);
    }
}
