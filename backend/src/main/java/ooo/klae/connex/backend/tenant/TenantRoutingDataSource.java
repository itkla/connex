package ooo.klae.connex.backend.tenant;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.datasource.DelegatingDataSource;

/**
 * Catalog-switching decorator over the shared Hikari pool (#313 Phase 3 /
 * #440 increment 2). The catalog is decided when the {@link TenantContext} is
 * installed and only read here, so checkout does no registry work and the
 * catalog cannot change mid-transaction. A {@code null} context catalog — the
 * shared tier, unresolved threads, startup, Flyway, schedulers — passes the
 * pooled connection through untouched. For a routed catalog the connection is
 * switched at checkout and reset to the default catalog on {@code close()};
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
        return wrap(connection);
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

    private Connection wrap(Connection target) {
        AtomicBoolean closed = new AtomicBoolean();
        return (Connection) Proxy.newProxyInstance(TenantRoutingDataSource.class.getClassLoader(),
            new Class<?>[] { Connection.class },
            (proxy, method, args) -> {
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
