package ooo.klae.connex.backend.tenant;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.ibatis.cache.CacheKey;
import org.apache.ibatis.executor.BatchExecutor;
import org.apache.ibatis.executor.CachingExecutor;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Plugin;
import org.apache.ibatis.plugin.Signature;
import org.apache.ibatis.reflection.ExceptionUtil;
import org.apache.ibatis.reflection.SystemMetaObject;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;

import lombok.RequiredArgsConstructor;

/**
 * Routes physically control-plane mapper statements back to the default catalog
 * while a dedicated tenant transaction remains bound to one database session.
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
public class ControlCatalogRoutingInterceptor implements Interceptor {

    private static final String MAPPERS = "ooo.klae.connex.backend.mappers.";

    /**
     * Mapper namespaces whose SQL is physically control-plane-only. This registry
     * is deliberately separate from workspace-scope enforcement because
     * {@code AuditLogMapper} and {@code RoleMapper} are workspace-scoped while
     * their tables remain in the control catalog.
     */
    public static final Set<String> CONTROL_CATALOG_NAMESPACES = Stream.concat(
        TenantScopeInterceptor.CONTROL_PLANE_NAMESPACES.stream(),
        Stream.of(MAPPERS + "AuditLogMapper", MAPPERS + "RoleMapper"))
        .collect(Collectors.toUnmodifiableSet());

    private final TenantContext tenantContext;
    private final boolean routingEnabled;

    @Override
    public Object intercept(Invocation invocation) throws Throwable {
        MappedStatement statement = (MappedStatement) invocation.getArgs()[0];
        if (!routingEnabled || !routesToControlCatalog(statement.getId())) {
            return invocation.proceed();
        }
        if (!(invocation.getTarget() instanceof Executor executor)) {
            throw new IllegalStateException(
                "Control-catalog routing requires a MyBatis Executor target");
        }
        Connection connection = executor.getTransaction().getConnection();
        if (!(connection instanceof ControlCatalogConnection routed)) {
            if (tenantContext.getCatalog() != null) {
                throw new IllegalStateException(
                    "A dedicated tenant scope acquired a connection that cannot route control-plane statements");
            }
            return invocation.proceed();
        }
        if (isBatchExecutor(executor)) {
            throw new IllegalStateException(
                "Control-plane statements cannot use a MyBatis BATCH executor in a dedicated tenant transaction");
        }
        if ("queryCursor".equals(invocation.getMethod().getName())) {
            throw new IllegalStateException(
                "Control-plane cursors cannot outlive catalog routing in a dedicated tenant transaction");
        }
        String previousCatalog = routed.enterControlCatalog();
        Object result;
        try {
            result = invocation.proceed();
        } catch (Throwable invocationFailure) {
            Throwable primaryFailure = ExceptionUtil.unwrapThrowable(invocationFailure);
            try {
                routed.restoreCatalog(previousCatalog);
            } catch (Throwable restoreFailure) {
                primaryFailure.addSuppressed(ExceptionUtil.unwrapThrowable(restoreFailure));
            }
            throw primaryFailure;
        }
        routed.restoreCatalog(previousCatalog);
        return result;
    }

    /** Whether the mapped statement belongs to a physically control-only mapper. */
    boolean routesToControlCatalog(String statementId) {
        for (String namespace : CONTROL_CATALOG_NAMESPACES) {
            if (statementId.startsWith(namespace + ".")) {
                return true;
            }
        }
        return false;
    }

    private boolean isBatchExecutor(Executor executor) {
        Set<Object> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        Object candidate = executor;
        while (candidate != null && visited.add(candidate)) {
            if (candidate instanceof BatchExecutor) {
                return true;
            }
            if (candidate instanceof CachingExecutor) {
                candidate = SystemMetaObject.forObject(candidate).getValue("delegate");
                continue;
            }
            if (Proxy.isProxyClass(candidate.getClass())) {
                InvocationHandler handler = Proxy.getInvocationHandler(candidate);
                if (handler instanceof Plugin) {
                    candidate = SystemMetaObject.forObject(handler).getValue("target");
                    continue;
                }
            }
            return false;
        }
        return false;
    }
}
