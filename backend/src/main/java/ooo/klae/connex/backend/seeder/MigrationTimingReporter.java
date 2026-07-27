package ooo.klae.connex.backend.seeder;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/**
 * Logs the installed-rank view of Flyway migration durations and their total.
 */
@Slf4j
@Component
public class MigrationTimingReporter {

    /**
     * Reads applied migration metadata from Flyway and emits a deterministic timing report.
     *
     * @param flyway the migrated Flyway instance
     */
    public void report(Flyway flyway) {
        List<MigrationInfo> applied = Arrays.stream(flyway.info().all())
            .filter(info -> info.getInstalledRank() != null)
            .sorted(Comparator.comparingInt(MigrationInfo::getInstalledRank))
            .toList();

        long totalMillis = 0;
        log.info("Flyway migration timing report: {} installed entries", applied.size());
        for (MigrationInfo info : applied) {
            int executionTime = info.getExecutionTime() == null ? 0 : info.getExecutionTime();
            totalMillis += executionTime;
            String version = info.getVersion() == null ? null : info.getVersion().toString();
            log.info(
                "Flyway migration timing installed_rank={} version={} description=\"{}\" execution_time_ms={} success={}",
                info.getInstalledRank(),
                version,
                info.getDescription(),
                executionTime,
                !info.getState().isFailed()
            );
        }
        log.info("Flyway migration timing total_ms={}", totalMillis);
    }
}
