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
 * workspaceId) it does nothing.
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
        MAPPERS + "EntityReferenceMapper",
        MAPPERS + "AttachmentMapper",
        MAPPERS + "DealMapper",
        MAPPERS + "DealStageHistoryMapper",
        MAPPERS + "TaskMapper",
        MAPPERS + "NotificationMapper",
        MAPPERS + "IntroductionMapper",
        MAPPERS + "PersonEdgeMapper",
        MAPPERS + "ShareMapper",
        MAPPERS + "AiOutputCacheMapper"
    );

    /**
     * Mapper interfaces that are deliberately NOT workspace-scoped: identity and
     * auth-token planes ({@code app_user}, password/email/registration tokens,
     * WebAuthn, federated/SSO identity), membership and capability-token flows
     * that run before a tenant is resolvable (workspace, invites, allowed
     * domains), per-user preferences, per-workspace mail config used by
     * background senders, the organization root itself, and — per the plane
     * decisions on #440 increment 3 — the whole audit trail (hash-chained,
     * writable during auth flows and org-catalog outages) and the workspace
     * role catalog that {@code workspace_member.role_id} references. Every mapper XML
     * must appear either here or in {@link #SCOPED_NAMESPACES} — the
     * registry-completeness architecture test enforces the partition so a new
     * mapper cannot silently bypass the fail-closed backstop.
     */
    public static final Set<String> CONTROL_PLANE_NAMESPACES = Set.of(
        MAPPERS + "AllowedDomainMapper",
        MAPPERS + "AiProviderConfigMapper",
        MAPPERS + "AppiIncidentMapper",
        MAPPERS + "AuditIntegrityMapper",
        MAPPERS + "AuditLogMapper",
        MAPPERS + "OrgAllowedDomainMapper",
        MAPPERS + "EmailChangeTokenMapper",
        MAPPERS + "FederatedIdentityMapper",
        MAPPERS + "InviteLinkMapper",
        MAPPERS + "InviteMapper",
        MAPPERS + "MailConfigMapper",
        MAPPERS + "OrganizationMapper",
        MAPPERS + "OrgMemberMapper",
        MAPPERS + "OrgPlacementMapper",
        MAPPERS + "PasswordResetTokenMapper",
        MAPPERS + "PreferenceMapper",
        MAPPERS + "RegistrationVerificationTokenMapper",
        MAPPERS + "RoleMapper",
        MAPPERS + "SecretValueMapper",
        MAPPERS + "SsoConnectionMapper",
        MAPPERS + "SsoDomainMapper",
        MAPPERS + "SsoLinkChallengeMapper",
        MAPPERS + "UserMapper",
        MAPPERS + "WebauthnCredentialMapper",
        MAPPERS + "WebauthnUserEntityMapper",
        MAPPERS + "WorkspaceMapper"
    );

    /**
     * Scoped statements that legitimately run with an unresolved context. The
     * offboarding statements (#440 increment 3) replace the dropped
     * cross-plane foreign keys. The {@code *Anywhere} guards and erasures run
     * during self-serve account deletion, which is identity-scoped
     * ({@code requireSelf}) and deliberately spans every workspace — including
     * ones the user has left, where no tenant context could be resolved. The
     * recipient-scoped notification delete backs invitation decline (and the
     * stale-row clean at invite time), which a user with no active workspace
     * may perform; it anchors {@code workspace_id} and {@code recipient_id}
     * in SQL.
     */
    private static final Set<String> EXEMPT_STATEMENTS = Set.of(
        MAPPERS + "NoteMapper.countAuthoredAnywhere",
        MAPPERS + "ActivityMapper.countCreatedAnywhere",
        MAPPERS + "IntroductionMapper.countIntroducedAnywhere",
        MAPPERS + "NotificationMapper.deleteAllForRecipient",
        MAPPERS + "NotificationMapper.deleteAllForRecipientAnywhere",
        MAPPERS + "NotificationMapper.clearActorAnywhere",
        MAPPERS + "DealMapper.clearOwnershipAnywhere",
        MAPPERS + "DealMapper.removeCollaboratorAnywhere",
        MAPPERS + "TaskMapper.unassignAnywhere",
        MAPPERS + "AttachmentMapper.clearUploaderAnywhere",
        MAPPERS + "RuleMapper.clearRunAsAnywhere",
        MAPPERS + "RuleMapper.clearCreatedByAnywhere",
        MAPPERS + "ShareMapper.clearCompanyShareGrantedByAnywhere",
        MAPPERS + "ShareMapper.clearPersonShareGrantedByAnywhere",
        MAPPERS + "ShareMapper.clearPipelineShareGrantedByAnywhere",
        MAPPERS + "SavedViewMapper.deleteForUserAnywhere",
        MAPPERS + "UserDashboardMapper.deleteForUserAnywhere"
    );

    private final TenantContext tenantContext;
    private final boolean enforce;

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
