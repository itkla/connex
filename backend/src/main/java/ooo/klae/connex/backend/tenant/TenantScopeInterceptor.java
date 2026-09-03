package ooo.klae.connex.backend.tenant;

import java.util.Set;

import org.apache.ibatis.cache.CacheKey;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Signature;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.springframework.web.context.request.RequestContextHolder;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.exceptions.ForbiddenException;

/**
 * Fail-closed backstop for tenant isolation. Throws when a statement that reads
 * or writes workspace-scoped data runs on a request thread without a resolved
 * {@link TenantContext}, turning a forgotten workspace predicate into a hard
 * error instead of a silent cross-tenant leak. It never rewrites SQL; the
 * explicit {@code workspace_id} predicates in the mappers remain the primary
 * mechanism. Off the request thread (scheduled jobs that pass an explicit
 * workspaceId) it does nothing in {@code single-database} mode; when catalog
 * routing is enabled it additionally requires a resolved scope or a
 * {@code TenantWorkScope} catalog override, so a future async path that
 * forgets to route fails loudly instead of silently reading the default
 * catalog (#485).
 *
 * <p>The signatures below must cover <em>every</em> statement-executing method
 * on {@link Executor}, including {@code queryCursor} — the streaming reads
 * behind the whole-tenant export (#995). A method left out is not a weaker
 * check but no check at all, so {@code TenantScopeInterceptorTest} asserts the
 * coverage reflectively and fails the build when a MyBatis upgrade adds one.
 */
@Intercepts({
    @Signature(type = Executor.class, method = "update",
        args = { MappedStatement.class, Object.class }),
    @Signature(type = Executor.class, method = "query",
        args = { MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class }),
    @Signature(type = Executor.class, method = "query",
        args = { MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class, CacheKey.class, BoundSql.class }),
    @Signature(type = Executor.class, method = "queryCursor",
        args = { MappedStatement.class, Object.class, RowBounds.class })
})
@RequiredArgsConstructor
public class TenantScopeInterceptor implements Interceptor {

    private static final String MAPPERS = "ooo.klae.connex.backend.mappers.";

    /**
     * Mapper interfaces whose statements read or write workspace-scoped data. The
     * canonical registry of tenant-scoped namespaces — also consumed by the
     * read-path architecture test that asserts every {@code <select>} here binds
     * the workspace predicate.
     */
    public static final Set<String> SCOPED_NAMESPACES = Set.of(
        MAPPERS + "AiAssistantIdentifierMapper",
        MAPPERS + "AiBriefScheduleMapper",
        MAPPERS + "AiChatMapper",
        MAPPERS + "AiWatchMapper",
        MAPPERS + "AiWorkspaceGovernanceMapper",
        MAPPERS + "CompanyMapper",
        MAPPERS + "DuplicateReviewMapper",
        MAPPERS + "IdentityCollisionMapper",
        MAPPERS + "IdentityMapper",
        MAPPERS + "PersonMapper",
        MAPPERS + "PersonEmploymentMapper",
        MAPPERS + "PersonLifecycleHistoryMapper",
        MAPPERS + "PersonLifecyclePassMapper",
        MAPPERS + "PersonQualificationMapper",
        MAPPERS + "QualificationCriterionMapper",
        MAPPERS + "ProviderCaptureMapper",
        MAPPERS + "PipelineMapper",
        MAPPERS + "TagMapper",
        MAPPERS + "ProductMapper",
        MAPPERS + "DealLineItemMapper",
        MAPPERS + "DocumentTemplateMapper",
        MAPPERS + "DealDocumentMapper",
        MAPPERS + "ApprovalPolicyMapper",
        MAPPERS + "DocumentApprovalMapper",
        MAPPERS + "DocumentDeliveryMapper",
        MAPPERS + "CustomFieldDefinitionMapper",
        MAPPERS + "CustomFieldValueMapper",
        MAPPERS + "RecordCreationTemplateMapper",
        MAPPERS + "SavedViewMapper",
        MAPPERS + "SavedViewPreferenceMapper",
        MAPPERS + "UserDashboardMapper",
        MAPPERS + "SegmentMapper",
        MAPPERS + "RuleMapper",
        MAPPERS + "JobRunMapper",
        MAPPERS + "WorkflowMapper",
        MAPPERS + "WorkflowOperationsMapper",
        MAPPERS + "WorkflowRunMapper",
        MAPPERS + "WorkflowTriggerOutboxMapper",
        MAPPERS + "WorkflowVersionMapper",
        MAPPERS + "CampaignMapper",
        MAPPERS + "ConsentMapper",
        MAPPERS + "SuppressionMapper",
        MAPPERS + "CampaignMessageMapper",
        MAPPERS + "CampaignSendMapper",
        MAPPERS + "CampaignDeliveryMapper",
        MAPPERS + "CampaignEngagementMapper",
        MAPPERS + "DeliveryProviderConfigMapper",
        MAPPERS + "ConnectorConfigMapper",
        MAPPERS + "CampaignAudienceExportMapper",
        MAPPERS + "DataSubjectDisclosureMapper",
        MAPPERS + "ActivityMapper",
        MAPPERS + "NoteMapper",
        MAPPERS + "RecordCommentMapper",
        MAPPERS + "ObjectDeletionQueueMapper",
        MAPPERS + "ObjectStorageQuotaMapper",
        MAPPERS + "EntityReferenceMapper",
        MAPPERS + "AttachmentMapper",
        MAPPERS + "BusinessCardImportRequestMapper",
        MAPPERS + "LegacyTenantUploadMigrationMapper",
        MAPPERS + "DealMapper",
        MAPPERS + "DealDuplicateReviewProofMapper",
        MAPPERS + "DealStageHistoryMapper",
        MAPPERS + "TaskMapper",
        MAPPERS + "NotificationMapper",
        MAPPERS + "IntroductionMapper",
        MAPPERS + "AuditLogMapper",
        MAPPERS + "PersonEdgeMapper",
        MAPPERS + "RoleMapper",
        MAPPERS + "ShareMapper",
        MAPPERS + "AiOutputCacheMapper",
        MAPPERS + "ReportMapper",
        MAPPERS + "RelationshipSignalMapper",
        MAPPERS + "GoalMapper",
        MAPPERS + "ScheduleMapper",
        MAPPERS + "TenantLifecycleMapper"
    );

    /**
     * Mapper interfaces that are deliberately NOT workspace-scoped: identity and
     * auth-token planes ({@code app_user}, password/email/registration tokens,
     * WebAuthn, federated/SSO identity), membership and capability-token flows
     * that run before a tenant is resolvable (workspace, invites, allowed
     * domains), per-user preferences, per-workspace mail config used by
     * background senders, and the organization root itself. Every mapper XML
     * must appear either here or in {@link #SCOPED_NAMESPACES} — the
     * registry-completeness architecture test enforces the partition so a new
     * mapper cannot silently bypass the fail-closed backstop.
     */
    public static final Set<String> CONTROL_PLANE_NAMESPACES = Set.of(
        MAPPERS + "AllowedDomainMapper",
        MAPPERS + "AiOrganizationBudgetMapper",
        MAPPERS + "AiProviderConfigMapper",
        MAPPERS + "AppiIncidentMapper",
        MAPPERS + "AuditIntegrityMapper",
        MAPPERS + "ClientErrorMapper",
        MAPPERS + "ControlWorkspaceLifecycleMapper",
        MAPPERS + "DataSubjectRequestMapper",
        MAPPERS + "OrgAllowedDomainMapper",
        MAPPERS + "EmailChangeTokenMapper",
        MAPPERS + "FederatedIdentityMapper",
        MAPPERS + "InviteLinkMapper",
        MAPPERS + "InviteMapper",
        MAPPERS + "LegacyControlUploadMigrationMapper",
        MAPPERS + "LogoutAuditClaimMapper",
        MAPPERS + "MailConfigMapper",
        MAPPERS + "MigrationHistoryMapper",
        MAPPERS + "OneTimeLinkFlowMapper",
        MAPPERS + "OrganizationMapper",
        MAPPERS + "OrgMemberMapper",
        MAPPERS + "OrgPlacementMapper",
        MAPPERS + "ObjectStorageBackendIdentityMapper",
        MAPPERS + "PasskeyBootstrapConfirmationTokenMapper",
        MAPPERS + "PasswordResetTokenMapper",
        MAPPERS + "NotificationQuietHoursMapper",
        MAPPERS + "NativeConnectSessionMapper",
        MAPPERS + "PreferenceMapper",
        MAPPERS + "RegistrationVerificationTokenMapper",
        MAPPERS + "SecretValueMapper",
        MAPPERS + "SpringSessionMapper",
        MAPPERS + "ProviderConnectionMapper",
        MAPPERS + "SsoConnectionMapper",
        MAPPERS + "SsoDomainMapper",
        MAPPERS + "SsoLinkChallengeMapper",
        MAPPERS + "UserMapper",
        MAPPERS + "UserObjectDeletionQueueMapper",
        MAPPERS + "WebauthnCredentialMapper",
        MAPPERS + "WebauthnUserEntityMapper",
        MAPPERS + "WorkspaceMapper",
        MAPPERS + "TenantLifecycleControlMapper"
    );

    /**
     * Scoped statements that legitimately run with an unresolved context. Audit
     * writes happen during auth flows (before a workspace is pinned) and carry a
     * nullable {@code workspace_id} for system events. The role-permission read
     * backs {@code WorkspaceService.permissionsFor}, which any auth-plane request
     * ({@code /api/auth/**} is excluded from tenant resolution) may reach with an
     * explicit membership-validated workspace id; the statement itself anchors
     * {@code workspace_id} in SQL, so it is safe without a resolved context. The
     * org-scoped audit reads are org-filtered ({@code org_id}) and gated by org
     * membership (an org admin needn't have any active workspace), so they too may
     * run without a resolved workspace context.
     *
     * <p>The offboarding statements (#440 increment 3) replace the dropped
     * cross-plane foreign keys, including company, contact, and deal ownership.
     * The {@code *Anywhere} guards and erasures run
     * during self-serve account deletion, which is identity-scoped
     * ({@code requireSelf}) and deliberately spans every workspace — including
     * ones the user has left, where no tenant context could be resolved. The
     * recipient-scoped notification deletes back invitation decline, which a user
     * with no active workspace may reach. Fresh-membership cleanup instead installs
     * its target workspace scope before calling tenant mappers, so its dedicated
     * saved-view statements are not exempt. Its provider-capture purge, assistant-chat,
     * notification, and deal-collaborator statements remain exempt because member
     * detachment and invitation decline also reach them without installing a target
     * workspace scope. The recipient
     * membership lock, actor-recipient projection and per-recipient
     * state-version bump are identity-scoped coordination writes for those same
     * offboarding flows. Workflow discovery is likewise bound to the departing
     * user across workflow, immutable-version, and linked-rule creator/run-as
     * columns; subsequent locks and writes use exact workspace-scoped keys. The
     * AI-output-cache and generated-chat purges are org-scoped: restricting a contact removes
     * retained AI outputs across every workspace in the contact's organization, including same-org
     * grantee workspaces it was shared into. The generated-chat purge receives the bounded
     * workspace set resolved by the service from the current workspace's organization.
     */
    private static final Set<String> EXEMPT_STATEMENTS = Set.of(
        MAPPERS + "AuditLogMapper.insert",
        MAPPERS + "AuditLogMapper.findRecentByOrg",
        MAPPERS + "AuditLogMapper.findOrgExport",
        MAPPERS + "AuditLogMapper.findOrgSupportSlice",
        MAPPERS + "RelationshipSignalMapper.deleteActorState",
        MAPPERS + "RelationshipSignalMapper.deleteActorStateAnywhere",
        MAPPERS + "RoleMapper.findPermissions",
        MAPPERS + "NoteMapper.countAuthoredAnywhere",
        MAPPERS + "ActivityMapper.countCreatedAnywhere",
        MAPPERS + "ProviderCaptureMapper.clearWorkspacePolicyUpdaterAnywhere",
        MAPPERS + "ProviderCaptureMapper.deleteProviderActivitiesAnywhere",
        MAPPERS + "ProviderCaptureMapper.deleteInteractionsAnywhere",
        MAPPERS + "ProviderCaptureMapper.deleteSyncStatesAnywhere",
        MAPPERS + "ProviderCaptureMapper.deleteUserPolicyAnywhere",
        MAPPERS + "ProviderCaptureMapper.deleteDecisionsAnywhere",
        MAPPERS + "ProviderCaptureMapper.deleteProviderActivities",
        MAPPERS + "ProviderCaptureMapper.deleteInteractions",
        MAPPERS + "ProviderCaptureMapper.deleteSyncStates",
        MAPPERS + "ProviderCaptureMapper.deleteUserPolicy",
        MAPPERS + "ProviderCaptureMapper.deleteDecisions",
        MAPPERS + "ProviderCaptureMapper.countUserProviderResiduals",
        MAPPERS + "IntroductionMapper.countIntroducedAnywhere",
        MAPPERS + "NotificationMapper.lockRecipientMemberships",
        MAPPERS + "NotificationMapper.findRecipientIdsByActor",
        MAPPERS + "NotificationMapper.lockRecipientIdsByActor",
        MAPPERS + "NotificationMapper.deleteHistoricalNotificationBaselinesForRecipient",
        MAPPERS + "NotificationMapper.deleteHistoricalNotificationBaselinesForRecipientAnywhere",
        MAPPERS + "NotificationMapper.deleteAllForRecipient",
        MAPPERS + "NotificationMapper.deleteAllForRecipientAnywhere",
        MAPPERS + "NotificationMapper.clearActorAnywhere",
        MAPPERS + "AiBriefScheduleMapper.deleteForUser",
        MAPPERS + "AiBriefScheduleMapper.deleteForUserAnywhere",
        MAPPERS + "AiWatchMapper.deleteForUser",
        MAPPERS + "AiWatchMapper.deleteForUserAnywhere",
        MAPPERS + "AiChatMapper.deleteParticipantsForUser",
        MAPPERS + "AiChatMapper.deleteParticipantsForUserAnywhere",
        MAPPERS + "AiChatMapper.clearSessionCreatorsAnywhere",
        MAPPERS + "AiChatMapper.clearMessageAuthorsAnywhere",
        MAPPERS + "AiChatMapper.clearToolCallExecutorsAnywhere",
        MAPPERS + "AiChatMapper.clearTurnRequestersAnywhere",
        MAPPERS + "CompanyMapper.clearOwnershipAnywhere",
        MAPPERS + "PersonMapper.clearOwnershipAnywhere",
        MAPPERS + "PersonLifecyclePassMapper.clearOwnerAnywhere",
        MAPPERS + "DealMapper.clearOwnershipAnywhere",
        MAPPERS + "DealMapper.removeCollaboratorAnywhere",
        MAPPERS + "DealDuplicateReviewProofMapper.deleteForActorAnywhere",
        MAPPERS + "DuplicateReviewMapper.clearDismissedByAnywhere",
        MAPPERS + "DealMapper.removeCollaboratorFromWorkspace",
        MAPPERS + "TaskMapper.unassignAnywhere",
        MAPPERS + "AttachmentMapper.clearUploaderAnywhere",
        MAPPERS + "CampaignMapper.clearCampaignUserReferencesAnywhere",
        MAPPERS + "CampaignMapper.clearSnapshotCreatorsAnywhere",
        MAPPERS + "CampaignDeliveryMapper.getByToken",
        MAPPERS + "DeliveryProviderConfigMapper.findByWebhookTokenHash",
        MAPPERS + "ConsentMapper.clearEventCreatorsAnywhere",
        MAPPERS + "WorkflowMapper.findAffectedByUserAnywhere",
        MAPPERS + "WorkflowOperationsMapper.clearUserReferencesAnywhere",
        MAPPERS + "RecordCreationTemplateMapper.clearUserReferencesAnywhere",
        MAPPERS + "WorkflowVersionMapper.findLockCandidatesByUserAnywhere",
        MAPPERS + "RuleMapper.findLockCandidatesByUserAnywhere",
        MAPPERS + "ShareMapper.clearCompanyShareGrantedByAnywhere",
        MAPPERS + "ShareMapper.clearPersonShareGrantedByAnywhere",
        MAPPERS + "ShareMapper.clearPipelineShareGrantedByAnywhere",
        MAPPERS + "SavedViewMapper.deleteForUserAnywhere",
        MAPPERS + "SavedViewPreferenceMapper.deletePinsForUserAnywhere",
        MAPPERS + "SavedViewPreferenceMapper.deleteDefaultsForUserAnywhere",
        MAPPERS + "UserDashboardMapper.deleteForUserAnywhere",
        MAPPERS + "ReportMapper.clearDefinitionCreatorsAnywhere",
        MAPPERS + "ReportMapper.clearSnapshotGeneratorsAnywhere",
        MAPPERS + "SuppressionMapper.clearCreatorsAnywhere",
        MAPPERS + "NotificationMapper.bumpStateVersions",
        MAPPERS + "AiOutputCacheMapper.deleteForPerson"
    );

    private final TenantContext tenantContext;
    private final boolean enforce;
    private final boolean routingEnabled;

    @Override
    public Object intercept(Invocation invocation) throws Throwable {
        if (enforce) {
            MappedStatement statement = (MappedStatement) invocation.getArgs()[0];
            enforce(statement.getId());
        }
        return invocation.proceed();
    }

    /**
     * Throws when a workspace-scoped statement runs on a request thread without a
     * resolved tenant context.
     */
    void enforce(String statementId) {
        if (!requiresResolvedContext(statementId)) {
            return;
        }
        if (RequestContextHolder.getRequestAttributes() == null) {
            if (routingEnabled && !tenantContext.isResolved() && !tenantContext.hasCatalogOverride()) {
                throw new IllegalStateException("Tenant-scoped statement " + statementId
                    + " ran off the request thread with no catalog scope while catalog routing is enabled; "
                    + "wrap the work in TenantWorkScope so it routes to the workspace's catalog (#485)");
            }
            return;
        }
        if (!tenantContext.isResolved()) {
            throw new ForbiddenException("Tenant scope is unresolved for " + statementId);
        }
    }

    /** Whether a statement reads or writes workspace-scoped data and is not exempt. */
    boolean requiresResolvedContext(String statementId) {
        if (EXEMPT_STATEMENTS.contains(statementId)) {
            return false;
        }
        for (String namespace : SCOPED_NAMESPACES) {
            if (statementId.startsWith(namespace + ".")) {
                return true;
            }
        }
        return false;
    }
}
