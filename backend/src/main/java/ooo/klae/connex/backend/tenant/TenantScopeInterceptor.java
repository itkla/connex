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
 */
@Intercepts({
    @Signature(type = Executor.class, method = "update",
        args = { MappedStatement.class, Object.class }),
    @Signature(type = Executor.class, method = "query",
        args = { MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class }),
    @Signature(type = Executor.class, method = "query",
        args = { MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class, CacheKey.class, BoundSql.class })
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
        MAPPERS + "CompanyMapper",
        MAPPERS + "PersonMapper",
        MAPPERS + "PersonEmploymentMapper",
        MAPPERS + "PipelineMapper",
        MAPPERS + "TagMapper",
        MAPPERS + "CustomFieldDefinitionMapper",
        MAPPERS + "CustomFieldValueMapper",
        MAPPERS + "SavedViewMapper",
        MAPPERS + "UserDashboardMapper",
        MAPPERS + "SegmentMapper",
        MAPPERS + "RuleMapper",
        MAPPERS + "ActivityMapper",
        MAPPERS + "NoteMapper",
        MAPPERS + "ObjectDeletionQueueMapper",
        MAPPERS + "ObjectStorageQuotaMapper",
        MAPPERS + "EntityReferenceMapper",
        MAPPERS + "AttachmentMapper",
        MAPPERS + "BusinessCardImportRequestMapper",
        MAPPERS + "LegacyTenantUploadMigrationMapper",
        MAPPERS + "DealMapper",
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
        MAPPERS + "GoalMapper",
        MAPPERS + "ScheduleMapper"
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
        MAPPERS + "AiProviderConfigMapper",
        MAPPERS + "AppiIncidentMapper",
        MAPPERS + "AuditIntegrityMapper",
        MAPPERS + "DataSubjectRequestMapper",
        MAPPERS + "OrgAllowedDomainMapper",
        MAPPERS + "EmailChangeTokenMapper",
        MAPPERS + "FederatedIdentityMapper",
        MAPPERS + "InviteLinkMapper",
        MAPPERS + "InviteMapper",
        MAPPERS + "LegacyControlUploadMigrationMapper",
        MAPPERS + "MailConfigMapper",
        MAPPERS + "OrganizationMapper",
        MAPPERS + "OrgMemberMapper",
        MAPPERS + "OrgPlacementMapper",
        MAPPERS + "ObjectStorageBackendIdentityMapper",
        MAPPERS + "PasswordResetTokenMapper",
        MAPPERS + "PreferenceMapper",
        MAPPERS + "RegistrationVerificationTokenMapper",
        MAPPERS + "SecretValueMapper",
        MAPPERS + "SsoConnectionMapper",
        MAPPERS + "SsoDomainMapper",
        MAPPERS + "SsoLinkChallengeMapper",
        MAPPERS + "UserMapper",
        MAPPERS + "UserObjectDeletionQueueMapper",
        MAPPERS + "WebauthnCredentialMapper",
        MAPPERS + "WebauthnUserEntityMapper",
        MAPPERS + "WorkspaceMapper"
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
     * cross-plane foreign keys. The {@code *Anywhere} guards and erasures run
     * during self-serve account deletion, which is identity-scoped
     * ({@code requireSelf}) and deliberately spans every workspace — including
     * ones the user has left, where no tenant context could be resolved. The
     * recipient-scoped notification delete and the deal-collaborator ghost clean
     * back invitation decline and the fresh-membership ghost clean (registration,
     * invites, invite links, SSO JIT provisioning — see
     * {@code UserOffboardingService.prepareFreshMembership}), all of which a user
     * with no active workspace may reach; both anchor {@code workspace_id} and
     * the user id in SQL. The recipient
     * membership lock, actor-recipient projection and per-recipient
     * state-version bump are identity-scoped coordination writes for those same
     * offboarding flows.
     */
    private static final Set<String> EXEMPT_STATEMENTS = Set.of(
        MAPPERS + "AuditLogMapper.insert",
        MAPPERS + "AuditLogMapper.findRecentByOrg",
        MAPPERS + "AuditLogMapper.findOrgExport",
        MAPPERS + "RoleMapper.findPermissions",
        MAPPERS + "NoteMapper.countAuthoredAnywhere",
        MAPPERS + "ActivityMapper.countCreatedAnywhere",
        MAPPERS + "IntroductionMapper.countIntroducedAnywhere",
        MAPPERS + "NotificationMapper.lockRecipientMemberships",
        MAPPERS + "NotificationMapper.findRecipientIdsByActor",
        MAPPERS + "NotificationMapper.lockRecipientIdsByActor",
        MAPPERS + "NotificationMapper.deleteAllForRecipient",
        MAPPERS + "NotificationMapper.deleteAllForRecipientAnywhere",
        MAPPERS + "NotificationMapper.clearActorAnywhere",
        MAPPERS + "DealMapper.clearOwnershipAnywhere",
        MAPPERS + "CompanyMapper.clearOwnershipAnywhere",
        MAPPERS + "PersonMapper.clearOwnershipAnywhere",
        MAPPERS + "DealMapper.removeCollaboratorAnywhere",
        MAPPERS + "DealMapper.removeCollaboratorFromWorkspace",
        MAPPERS + "TaskMapper.unassignAnywhere",
        MAPPERS + "AttachmentMapper.clearUploaderAnywhere",
        MAPPERS + "RuleMapper.clearRunAsAnywhere",
        MAPPERS + "RuleMapper.clearCreatedByAnywhere",
        MAPPERS + "ShareMapper.clearCompanyShareGrantedByAnywhere",
        MAPPERS + "ShareMapper.clearPersonShareGrantedByAnywhere",
        MAPPERS + "ShareMapper.clearPipelineShareGrantedByAnywhere",
        MAPPERS + "SavedViewMapper.deleteForUserAnywhere",
        MAPPERS + "UserDashboardMapper.deleteForUserAnywhere",
        MAPPERS + "ReportMapper.clearDefinitionCreatorsAnywhere",
        MAPPERS + "ReportMapper.clearSnapshotGeneratorsAnywhere",
        MAPPERS + "NotificationMapper.bumpStateVersions"
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
