package ooo.klae.connex.backend.services;

import java.sql.Connection;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationInfoService;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.tenant.TenantWorkScope;

/**
 * Probes instance database and migration readiness without exposing diagnostic details.
 */
@Service
@RequiredArgsConstructor
public class HealthService {
    private static final long CACHE_NANOS = TimeUnit.SECONDS.toNanos(3);

    private final DataSource dataSource;
    private final Flyway flyway;
    private final TenantWorkScope tenantWorkScope;

    private volatile Snapshot snapshot;

    /**
     * Returns a briefly cached readiness snapshot.
     *
     * @return the database and migration readiness statuses
     */
    public Readiness readiness() {
        long now = System.nanoTime();
        Snapshot current = snapshot;
        if (isCurrent(current, now)) {
            return current.readiness();
        }
        synchronized (this) {
            current = snapshot;
            if (isCurrent(current, System.nanoTime())) {
                return current.readiness();
            }
            Readiness readiness = tenantWorkScope.unrouted(
                    () -> new Readiness(status(databaseReady()), status(migrationsReady())));
            snapshot = new Snapshot(System.nanoTime(), readiness);
            return readiness;
        }
    }

    private static boolean isCurrent(Snapshot snapshot, long now) {
        return snapshot != null && now - snapshot.probedAtNanos() < CACHE_NANOS;
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
