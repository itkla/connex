package ooo.klae.connex.backend.services;

import java.sql.Connection;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationInfoService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.availability.ApplicationAvailability;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.stereotype.Service;

import ooo.klae.connex.backend.tenant.TenantWorkScope;

/**
 * Probes instance database, migration and startup readiness without exposing diagnostic details.
 */
@Service
public class HealthService {
    private static final long CACHE_NANOS = TimeUnit.SECONDS.toNanos(3);

    private final DataSource dataSource;
    private final Flyway flyway;
    private final TenantWorkScope tenantWorkScope;
    private final ApplicationAvailability applicationAvailability;
    private final long cacheNanos;

    private final ReentrantLock probeLock = new ReentrantLock();

    private volatile Snapshot snapshot;

    @Autowired
    public HealthService(DataSource dataSource,
            Flyway flyway,
            TenantWorkScope tenantWorkScope,
            ApplicationAvailability applicationAvailability) {
        this(dataSource, flyway, tenantWorkScope, applicationAvailability, CACHE_NANOS);
    }

    HealthService(DataSource dataSource,
            Flyway flyway,
            TenantWorkScope tenantWorkScope,
            ApplicationAvailability applicationAvailability,
            long cacheNanos) {
        this.dataSource = dataSource;
        this.flyway = flyway;
        this.tenantWorkScope = tenantWorkScope;
        this.applicationAvailability = applicationAvailability;
        this.cacheNanos = cacheNanos;
    }

    /**
     * Returns the dependency readiness snapshot composed with the current startup status.
     *
     * <p>Only the database and migration probes are cached, and only one caller probes at a time;
     * while a probe is in flight, concurrent callers are served the previous probe result (even a
     * just-expired one) instead of queueing request threads behind a potentially slow database
     * probe. Startup readiness is read on every call so the flip to accepting traffic — published
     * by Spring Boot only after every {@code ApplicationRunner} completes — is observed
     * immediately rather than up to one cache window late.
     *
     * @return the database, migration and startup readiness statuses
     */
    public Readiness readiness() {
        Snapshot current = snapshot;
        if (isCurrent(current, System.nanoTime())) {
            return compose(current);
        }
        if (current != null && !probeLock.tryLock()) {
            return compose(current);
        }
        if (current == null) {
            probeLock.lock();
        }
        try {
            current = snapshot;
            if (isCurrent(current, System.nanoTime())) {
                return compose(current);
            }
            Snapshot probed = tenantWorkScope.unrouted(() -> new Snapshot(
                    System.nanoTime(), status(databaseReady()), status(migrationsReady())));
            snapshot = probed;
            return compose(probed);
        } finally {
            probeLock.unlock();
        }
    }

    private Readiness compose(Snapshot probed) {
        return new Readiness(probed.db(), probed.migrations(), startupStatus());
    }

    private Status startupStatus() {
        return status(applicationAvailability.getReadinessState() == ReadinessState.ACCEPTING_TRAFFIC);
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
     * @param startup startup-runner completion status
     */
    public record Readiness(Status db, Status migrations, Status startup) {
        /**
         * Returns whether every readiness check is up.
         *
         * @return whether the instance is ready
         */
        public boolean isUp() {
            return db == Status.UP && migrations == Status.UP && startup == Status.UP;
        }
    }

    private record Snapshot(long probedAtNanos, Status db, Status migrations) {
    }
}
