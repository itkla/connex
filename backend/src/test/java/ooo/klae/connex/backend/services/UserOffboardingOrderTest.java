package ooo.klae.connex.backend.services;

import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.eq;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.connectedaccounts.ProviderAccountOffboardingService;
import ooo.klae.connex.backend.connectedaccounts.capture.ProviderCapturePurgeService;
import ooo.klae.connex.backend.mappers.ActivityMapper;
import ooo.klae.connex.backend.mappers.AttachmentMapper;
import ooo.klae.connex.backend.mappers.CampaignMapper;
import ooo.klae.connex.backend.mappers.CompanyMapper;
import ooo.klae.connex.backend.mappers.ConsentMapper;
import ooo.klae.connex.backend.mappers.DealMapper;
import ooo.klae.connex.backend.mappers.IntroductionMapper;
import ooo.klae.connex.backend.mappers.NoteMapper;
import ooo.klae.connex.backend.mappers.NotificationMapper;
import ooo.klae.connex.backend.mappers.PersonMapper;
import ooo.klae.connex.backend.mappers.ReportMapper;
import ooo.klae.connex.backend.mappers.RuleMapper;
import ooo.klae.connex.backend.mappers.SavedViewMapper;
import ooo.klae.connex.backend.mappers.SavedViewPreferenceMapper;
import ooo.klae.connex.backend.mappers.ShareMapper;
import ooo.klae.connex.backend.mappers.SuppressionMapper;
import ooo.klae.connex.backend.mappers.TaskMapper;
import ooo.klae.connex.backend.mappers.UserDashboardMapper;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.notifications.NotificationChangePublisher;
import ooo.klae.connex.backend.notifications.NotificationStateVersionService;
import ooo.klae.connex.backend.services.WorkflowOffboardingService.OffboardingPlan;
import ooo.klae.connex.backend.tenant.TenantWorkScope;

@ExtendWith(MockitoExtension.class)
class UserOffboardingOrderTest {
    @Mock private NoteMapper noteMapper;
    @Mock private ActivityMapper activityMapper;
    @Mock private IntroductionMapper introductionMapper;
    @Mock private NotificationMapper notificationMapper;
    @Mock private CompanyMapper companyMapper;
    @Mock private PersonMapper personMapper;
    @Mock private DealMapper dealMapper;
    @Mock private ReportMapper reportMapper;
    @Mock private TaskMapper taskMapper;
    @Mock private AttachmentMapper attachmentMapper;
    @Mock private CampaignMapper campaignMapper;
    @Mock private ConsentMapper consentMapper;
    @Mock private RuleMapper ruleMapper;
    @Mock private ShareMapper shareMapper;
    @Mock private SuppressionMapper suppressionMapper;
    @Mock private SavedViewPreferenceMapper savedViewPreferenceMapper;
    @Mock private SavedViewMapper savedViewMapper;
    @Mock private UserDashboardMapper userDashboardMapper;
    @Mock private UserMapper userMapper;
    @Mock private WorkspaceMapper workspaceMapper;
    @Mock private NotificationStateVersionService stateVersionService;
    @Mock private WorkflowOffboardingService workflowOffboardingService;
    @Mock private ProviderCapturePurgeService providerCapturePurgeService;

    @InjectMocks private UserOffboardingService service;

    @Test
    void freshMembershipPurgesSavedViewDataWhenNoMembershipRemains() {
        when(workspaceMapper.lockAuthorizationMembership(7, 9)).thenReturn(null);

        service.prepareFreshMembership(7, 9);

        InOrder order = inOrder(
            workspaceMapper, providerCapturePurgeService,
            savedViewPreferenceMapper, savedViewMapper,
            notificationMapper, dealMapper);
        order.verify(workspaceMapper).lockAuthorizationMembership(7, 9);
        order.verify(providerCapturePurgeService).purge(7, 9, "google");
        order.verify(providerCapturePurgeService).purge(7, 9, "microsoft");
        order.verify(savedViewPreferenceMapper).deletePinsForFreshMembership(7, 9);
        order.verify(savedViewPreferenceMapper).deleteDefaultsForFreshMembership(7, 9);
        order.verify(savedViewMapper).deleteForFreshMembership(7, 9);
        order.verify(notificationMapper)
            .deleteHistoricalNotificationBaselinesForRecipient(7, 9);
        order.verify(notificationMapper).deleteAllForRecipient(7, 9);
        order.verify(dealMapper).removeCollaboratorFromWorkspace(7, 9);
    }

    @Test
    void removeAndLeaveDetachmentLocksMembershipBeforeNotificationDeletion() {
        service.detachMemberContent(7, 9);

        InOrder order = inOrder(
            providerCapturePurgeService, notificationMapper,
            savedViewPreferenceMapper, savedViewMapper,
            taskMapper, companyMapper, personMapper, dealMapper, campaignMapper);
        order.verify(providerCapturePurgeService).purge(7, 9, "google");
        order.verify(providerCapturePurgeService).purge(7, 9, "microsoft");
        order.verify(notificationMapper).lockRecipientMemberships(9);
        order.verify(savedViewPreferenceMapper).deletePinsForUser(7, 9);
        order.verify(savedViewPreferenceMapper).deleteDefaultsForUser(7, 9);
        order.verify(savedViewMapper).deleteForUser(7, 9);
        order.verify(taskMapper).unassignMemberTasks(7, 9);
        order.verify(companyMapper).clearMemberOwnership(7, 9);
        order.verify(personMapper).clearMemberOwnership(7, 9);
        order.verify(dealMapper).clearMemberDealOwnership(7, 9);
        order.verify(campaignMapper).clearMemberOwnership(7, 9);
        order.verify(dealMapper).removeCollaboratorFromWorkspace(7, 9);
        order.verify(notificationMapper)
            .deleteHistoricalNotificationBaselinesForRecipient(7, 9);
        order.verify(notificationMapper).deleteAllForRecipient(7, 9);
        verifyNoInteractions(stateVersionService);
    }

    @Test
    void accountErasureLocksAllAffectedMembershipsBeforeNotificationsAndInvalidatesExactRecipients() {
        when(userMapper.lockById(9)).thenReturn(9);
        when(notificationMapper.findRecipientIdsByActor(9)).thenReturn(List.of(11, 3));
        when(notificationMapper.lockRecipientIdsByActor(9)).thenReturn(List.of(11, 5, 3));
        when(notificationMapper.clearActorAnywhere(9)).thenReturn(3);
        OffboardingPlan plan = new OffboardingPlan(List.of(), List.of(), List.of());
        when(workflowOffboardingService.discover(9)).thenReturn(plan);

        service.eraseOrgDataReferences(9);

        InOrder order = inOrder(
            userMapper, notificationMapper, savedViewPreferenceMapper, savedViewMapper,
            stateVersionService, companyMapper, personMapper, dealMapper,
            workflowOffboardingService);
        order.verify(userMapper).lockById(9);
        order.verify(notificationMapper).findRecipientIdsByActor(9);
        order.verify(workflowOffboardingService).discover(9);
        order.verify(workflowOffboardingService).lockWorkspaceRoots(plan);
        order.verify(notificationMapper).lockRecipientMemberships(3);
        order.verify(notificationMapper).lockRecipientMemberships(9);
        order.verify(notificationMapper).lockRecipientMemberships(11);
        order.verify(workflowOffboardingService).offboard(9, plan);
        order.verify(notificationMapper).lockRecipientIdsByActor(9);
        order.verify(savedViewPreferenceMapper).deletePinsForUserAnywhere(9);
        order.verify(savedViewPreferenceMapper).deleteDefaultsForUserAnywhere(9);
        order.verify(savedViewMapper).deleteForUserAnywhere(9);
        order.verify(notificationMapper)
            .deleteHistoricalNotificationBaselinesForRecipientAnywhere(9);
        order.verify(notificationMapper).deleteAllForRecipientAnywhere(9);
        order.verify(notificationMapper).clearActorAnywhere(9);
        order.verify(stateVersionService).markChanged(3);
        order.verify(stateVersionService).markChanged(5);
        order.verify(stateVersionService).markChanged(11);
        order.verify(companyMapper).clearOwnershipAnywhere(9);
        order.verify(personMapper).clearOwnershipAnywhere(9);
        order.verify(dealMapper).clearOwnershipAnywhere(9);
    }

    @Test
    void accountDeletionLocksRootsAndAllNotificationRecipientsBeforeOwnerRowsAndErasure() {
        UserMapper userMapper = mock(UserMapper.class);
        AuditService auditService = mock(AuditService.class);
        WorkspaceService workspaceService = mock(WorkspaceService.class);
        OrgMemberService orgMemberService = mock(OrgMemberService.class);
        NotificationChangePublisher notificationChanges = mock(NotificationChangePublisher.class);
        ReferenceService referenceService = mock(ReferenceService.class);
        ProviderAccountOffboardingService providerOffboardingService =
            mock(ProviderAccountOffboardingService.class);
        UserAccountCatalogOffboardingService catalogOffboardingService =
            mock(UserAccountCatalogOffboardingService.class);
        TenantWorkScope tenantWorkScope = mock(TenantWorkScope.class);
        User user = new User();
        user.setId(9);
        user.setUsername("target");
        when(workspaceService.discoverOwnedWorkspaceIds(9)).thenReturn(List.of(7));
        when(userMapper.lockById(9)).thenReturn(9);
        when(userMapper.reserveAccountDeletion(
                eq(9), org.mockito.ArgumentMatchers.anyString()))
            .thenReturn(1);
        when(userMapper.renewAccountDeletionReservation(
                eq(9), org.mockito.ArgumentMatchers.anyString()))
            .thenReturn(1);
        when(userMapper.isAccountDeletionReservationOwner(
                eq(9), org.mockito.ArgumentMatchers.anyString()))
            .thenReturn(true);
        when(userMapper.getUserById(9)).thenReturn(user);
        UserDeletionTransaction deletionTransaction = new UserDeletionTransaction(
            userMapper,
            workspaceService,
            orgMemberService,
            mock(ooo.klae.connex.backend.storage.ManagedObjectService.class),
            auditService
        );
        when(tenantWorkScope.unrouted(
                org.mockito.ArgumentMatchers.<java.util.function.Supplier<Object>>any()))
            .thenAnswer(invocation -> invocation
                .<java.util.function.Supplier<Object>>getArgument(0).get());
        UserService userService = new UserService(
            userMapper,
            activityMapper,
            noteMapper,
            taskMapper,
            auditService,
            workspaceService,
            notificationChanges,
            referenceService,
            mock(ooo.klae.connex.backend.storage.ManagedObjectService.class),
            mock(UserProfilePictureTransaction.class),
            tenantWorkScope,
            providerOffboardingService,
            catalogOffboardingService,
            deletionTransaction
        );

        userService.delete(9);

        InOrder order = inOrder(
            workspaceService, providerOffboardingService,
            catalogOffboardingService, orgMemberService, userMapper);
        order.verify(workspaceService).requireSelf(9);
        order.verify(userMapper).lockById(9);
        order.verify(workspaceService).discoverOwnedWorkspaceIds(9);
        order.verify(workspaceService).lockAccountWorkspaceRoots(List.of(7), List.of());
        order.verify(workspaceService).assertNotSoleOwnerOfWorkspaces(List.of(7));
        order.verify(orgMemberService).assertNotSoleOwnerOfAnyOrg(9);
        order.verify(catalogOffboardingService).assertNoAuthoredContent(9);
        order.verify(providerOffboardingService).purgeBeforeAccountDeletion(9);
        order.verify(catalogOffboardingService).eraseReferences(9);
        order.verify(userMapper).lockById(9);
        order.verify(workspaceService).discoverOwnedWorkspaceIds(9);
        order.verify(workspaceService).lockAccountWorkspaceRoots(List.of(7), List.of());
        order.verify(workspaceService).assertNotSoleOwnerOfWorkspaces(List.of(7));
        order.verify(orgMemberService).assertNotSoleOwnerOfAnyOrg(9);
        order.verify(userMapper).delete(9);
    }
}
