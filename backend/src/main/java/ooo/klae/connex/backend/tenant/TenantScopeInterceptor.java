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

    /** Mapper interfaces whose statements read or write workspace-scoped data. */
    private static final Set<String> SCOPED_NAMESPACES = Set.of(
        MAPPERS + "CompanyMapper",
        MAPPERS + "PersonMapper",
        MAPPERS + "PipelineMapper",
        MAPPERS + "TagMapper",
        MAPPERS + "CustomFieldDefinitionMapper",
        MAPPERS + "CustomFieldValueMapper",
        MAPPERS + "ActivityMapper",
        MAPPERS + "NoteMapper",
        MAPPERS + "AttachmentMapper",
        MAPPERS + "DealMapper",
        MAPPERS + "TaskMapper",
        MAPPERS + "NotificationMapper",
        MAPPERS + "AuditLogMapper"
    );

    /**
     * Scoped statements that legitimately run with an unresolved context. Audit
     * writes happen during auth flows (before a workspace is pinned) and carry a
     * nullable {@code workspace_id} for system events.
     */
    private static final Set<String> EXEMPT_STATEMENTS = Set.of(
        MAPPERS + "AuditLogMapper.insert"
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
