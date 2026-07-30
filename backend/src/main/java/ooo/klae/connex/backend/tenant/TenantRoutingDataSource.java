package ooo.klae.connex.backend.tenant;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.datasource.DelegatingDataSource;

/**
 * Catalog-switching decorator over the shared Hikari pool (#313 Phase 3 /
 * #440 increment 2). The catalog is decided when the {@link TenantContext} is
 * installed and only read here, so checkout does no registry work and the
 * transaction's tenant catalog cannot drift with later context changes.
 * A {@code null} context catalog — the shared tier, unresolved threads, startup,
 * Flyway, schedulers — passes the pooled connection through untouched. For a
 * routed catalog the connection is switched at checkout, exposes a guarded
 * temporary control-catalog scope to the MyBatis plane interceptor, and resets
 * to the default catalog on {@code close()};
 * if the reset fails the connection is evicted from the pool so a dirtied
 * connection can never be recycled to another tenant. HikariCP's own
 * dirty-bit reset (armed by configuring the pool's default catalog) remains
 * as a second, independent layer behind this one.
 */
public class TenantRoutingDataSource extends DelegatingDataSource {

    private static final Logger log = LoggerFactory.getLogger(TenantRoutingDataSource.class);

    private final TenantContext tenantContext;
    private final String defaultCatalog;
    private final Consumer<Connection> evictor;

    public TenantRoutingDataSource(DataSource target, TenantContext tenantContext,
            String defaultCatalog, Consumer<Connection> evictor) {
        super(target);
        this.tenantContext = tenantContext;
        this.defaultCatalog = defaultCatalog;
        this.evictor = evictor;
    }

    @Override
    public Connection getConnection() throws SQLException {
        return route(super.getConnection());
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return route(super.getConnection(username, password));
    }

    private Connection route(Connection connection) throws SQLException {
        String catalog = tenantContext.getCatalog();
        if (catalog == null) {
            return connection;
        }
        try {
            connection.setCatalog(catalog);
        } catch (SQLException e) {
            evictAndClose(connection, e);
            throw e;
        }
        return wrap(connection, catalog);
    }

    /**
     * Evicts a connection whose catalog switch failed, then closes it. The
     * driver may have applied the switch server-side before failing, and
     * HikariCP only arms its dirty-bit reset once {@code setCatalog} returns, so
     * closing alone could recycle a connection still pointing at the tenant
     * catalog. Eviction guarantees the physical connection is destroyed instead.
     */
    private void evictAndClose(Connection connection, SQLException cause) {
        try {
            evictor.accept(connection);
        } catch (RuntimeException evictFailure) {
            cause.addSuppressed(evictFailure);
        }
        try {
            connection.close();
        } catch (SQLException closeFailure) {
            cause.addSuppressed(closeFailure);
        }
    }

    private Connection wrap(Connection target, String tenantCatalog) {
        AtomicBoolean closed = new AtomicBoolean();
        AtomicInteger controlDepth = new AtomicInteger();
        return (ControlCatalogConnection) Proxy.newProxyInstance(
            TenantRoutingDataSource.class.getClassLoader(),
            new Class<?>[] { ControlCatalogConnection.class },
            (proxy, method, args) -> {
                if (method.getDeclaringClass() == ControlCatalogConnection.class) {
                    return switch (method.getName()) {
                        case "enterControlCatalog" ->
                            enterControlCatalog(
                                target, tenantCatalog, closed, controlDepth);
                        case "restoreCatalog" -> {
                            restoreCatalog(
                                target,
                                tenantCatalog,
                                (String) args[0],
                                closed,
                                controlDepth);
                            yield null;
                        }
                        default -> throw new IllegalStateException(
                            "Unsupported control catalog operation " + method.getName());
                    };
                }
                switch (method.getName()) {
                    case "close" -> {
                        if (closed.compareAndSet(false, true)) {
                            resetAndClose(target);
                        }
                        return null;
                    }
                    case "equals" -> {
                        return proxy == args[0];
                    }
                    case "hashCode" -> {
                        return System.identityHashCode(proxy);
                    }
                    default -> {
                        try {
                            return method.invoke(target, args);
                        } catch (InvocationTargetException e) {
                            throw e.getCause();
                        }
                    }
                }
            });
    }

    private String enterControlCatalog(
            Connection target,
            String tenantCatalog,
            AtomicBoolean closed,
            AtomicInteger controlDepth) throws SQLException {
        String current = requireExpectedCatalog(target, tenantCatalog, closed);
        int depth = controlDepth.get();
        if ((depth == 0 && !tenantCatalog.equals(current))
                || (depth > 0 && !defaultCatalog.equals(current))) {
            SQLException failure = new SQLException(
                "Control-catalog routing entered from an inconsistent catalog state");
            invalidate(target, closed, failure);
            throw failure;
        }
        if (!defaultCatalog.equals(current)) {
            switchCatalog(target, defaultCatalog, closed);
        }
        controlDepth.incrementAndGet();
        return current;
    }

    private void restoreCatalog(
            Connection target,
            String tenantCatalog,
            String catalog,
            AtomicBoolean closed,
            AtomicInteger controlDepth) throws SQLException {
        int depth = controlDepth.get();
        boolean expectedOuterRestore =
            depth == 1 && tenantCatalog.equals(catalog);
        boolean expectedNestedRestore =
            depth > 1 && defaultCatalog.equals(catalog);
        if (!expectedOuterRestore && !expectedNestedRestore) {
            SQLException failure = new SQLException(
                "Refusing an unbalanced control-catalog restore");
            invalidate(target, closed, failure);
            throw failure;
        }
        String current = requireExpectedCatalog(target, tenantCatalog, closed);
        if (!defaultCatalog.equals(current)) {
            SQLException failure = new SQLException(
                "Control-plane statement left the routed connection on an unexpected catalog");
            invalidate(target, closed, failure);
            throw failure;
        }
        if (!catalog.equals(current)) {
            switchCatalog(target, catalog, closed);
        }
        controlDepth.decrementAndGet();
    }

    private String requireExpectedCatalog(
            Connection target,
            String tenantCatalog,
            AtomicBoolean closed) throws SQLException {
        String current;
        try {
            current = target.getCatalog();
        } catch (SQLException failure) {
            invalidate(target, closed, failure);
            throw failure;
        }
        if (!tenantCatalog.equals(current) && !defaultCatalog.equals(current)) {
            SQLException failure = new SQLException(
                "Tenant-routed connection is on an unexpected catalog");
            invalidate(target, closed, failure);
            throw failure;
        }
        return current;
    }

    private void switchCatalog(
            Connection target,
            String catalog,
            AtomicBoolean closed) throws SQLException {
        try {
            target.setCatalog(catalog);
        } catch (SQLException failure) {
            invalidate(target, closed, failure);
            throw failure;
        }
    }

    private void invalidate(
            Connection target,
            AtomicBoolean closed,
            SQLException failure) {
        closed.set(true);
        evictAndClose(target, failure);
    }

    /**
     * Resets the connection to the default catalog before returning it to the
     * pool; on reset failure the connection is evicted so it can never be
     * recycled while pointing at a tenant catalog.
     */
    private void resetAndClose(Connection target) throws SQLException {
        try {
            target.setCatalog(defaultCatalog);
        } catch (SQLException e) {
            log.warn("Catalog reset failed; evicting connection from the pool", e);
            evictor.accept(target);
        } finally {
            target.close();
        }
    }
}
