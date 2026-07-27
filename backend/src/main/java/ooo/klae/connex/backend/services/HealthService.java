package ooo.klae.connex.backend.services;

import java.sql.Connection;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationInfoService;
import org.springframework.stereotype.Service;

import ooo.klae.connex.backend.tenant.TenantWorkScope;

/**
 * Probes instance database and migration readiness without exposing diagnostic details.
 */
@Service
public class HealthService {
    private static final long CACHE_NANOS = TimeUnit.SECONDS.toNanos(3);

    private final DataSource dataSource;
    private final Flyway flyway;
    private final TenantWorkScope tenantWorkScope;
    private final long cacheNanos;

    private final ReentrantLock probeLock = new ReentrantLock();

    private volatile Snapshot snapshot;

    public HealthService(DataSource dataSource, Flyway flyway, TenantWorkScope tenantWorkScope) {
        this(dataSource, flyway, tenantWorkScope, CACHE_NANOS);
    }

    HealthService(DataSource dataSource, Flyway flyway, TenantWorkScope tenantWorkScope, long cacheNanos) {
        this.dataSource = dataSource;
        this.flyway = flyway;
        this.tenantWorkScope = tenantWorkScope;
        this.cacheNanos = cacheNanos;
    }

    /**
     * Returns a briefly cached readiness snapshot.
     *
     * <p>Only one caller probes at a time; while a probe is in flight, concurrent callers are
     * served the previous snapshot (even a just-expired one) instead of queueing request threads
     * behind a potentially slow database probe.
     *
     * @return the database and migration readiness statuses
     */
    public Readiness readiness() {
        Snapshot current = snapshot;
        if (isCurrent(current, System.nanoTime())) {
            return current.readiness();
        }
        if (current != null && !probeLock.tryLock()) {
            return current.readiness();
        }
        if (current == null) {
            probeLock.lock();
        }
        try {
            current = snapshot;
            if (isCurrent(current, System.nanoTime())) {
                return current.readiness();
            }
            Readiness readiness = tenantWorkScope.unrouted(
                    () -> new Readiness(status(databaseReady()), status(migrationsReady())));
            snapshot = new Snapshot(System.nanoTime(), readiness);
            return readiness;
        } finally {
            probeLock.unlock();
        }
    }

    private boolean isCurrent(Snapshot snapshot, long now) {
        return snapshot != null && now - snapshot.probedAtNanos() < cacheNanos;
    }

    private boolean databaseReady() {
        try (Connection connection = dataSource.getConnection()) {
            return connection.isValid(2);
        } catch (Throwable exception) {
            return false;
        }
    }

    private boolean migrationsReady() {
        try {
            MigrationInfoService info = flyway.info();
            if (info == null || info.pending().length != 0) {
                return false;
            }
            MigrationInfo[] migrations = info.all();
            return migrations != null
                    && Arrays.stream(migrations)
                        .allMatch(migration -> migration != null
                                && migration.getState() != null
                                && !migration.getState().isFailed());
        } catch (Throwable exception) {
            return false;
        }
    }

    private static Status status(boolean ready) {
        return ready ? Status.UP : Status.DOWN;
    }

    /**
     * Status words exposed by the readiness API.
     */
    public enum Status {
        UP,
        DOWN
    }

    /**
     * Independent readiness check results.
     *
     * @param db database connectivity status
     * @param migrations migration status
     */
    public record Readiness(Status db, Status migrations) {
        /**
         * Returns whether every readiness check is up.
         *
         * @return whether the instance is ready
         */
        public boolean isUp() {
            return db == Status.UP && migrations == Status.UP;
        }
    }

    private record Snapshot(long probedAtNanos, Readiness readiness) {
    }
}
