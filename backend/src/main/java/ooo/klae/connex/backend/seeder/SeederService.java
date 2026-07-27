package ooo.klae.connex.backend.seeder;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import lombok.extern.slf4j.Slf4j;
import ooo.klae.connex.backend.tenant.TenantRoutingProperties;

/**
 * Coordinates deterministic workspace generation in one transaction per workspace.
 *
 * <p>The writer uses direct mapper SQL and never invokes entity services, rule
 * publishers, notification publishers, or audit publishers. This matches bulk import's
 * no-per-row-event semantics while preserving the application's MyBatis insert contracts.
 */
@Slf4j
@Lazy
@Service
public class SeederService {

    private final SeederBatchWriter writer;
    private final PlatformTransactionManager transactionManager;
    private final String routingMode;

    public SeederService(
            SeederBatchWriter writer,
            PlatformTransactionManager transactionManager,
            @Value("${connex.tenancy.routing.mode:single-database}") String routingMode) {
        this.writer = writer;
        this.transactionManager = transactionManager;
        this.routingMode = routingMode;
    }

    /**
     * Seeds one or more independently derived workspaces.
     *
     * @param profile fixture size
     * @param seed root deterministic seed
     * @param workspaceCount number of independent organization/workspace tenants
     * @param anchorDate inclusive date anchor for all generated timestamps
     * @return inserted logical row counts
     */
    public SeedRunSummary seed(
            SeederProperties.Profile profile,
            long seed,
            int workspaceCount,
            LocalDate anchorDate) {
        Objects.requireNonNull(profile, "Seeder profile is required");
        Objects.requireNonNull(anchorDate, "Seeder anchor date is required");
        if (workspaceCount < 1 || workspaceCount > 100) {
            throw new IllegalArgumentException("Seeder workspace count must be between 1 and 100");
        }
        if (!TenantRoutingProperties.MODE_SINGLE_DATABASE.equals(routingMode)) {
            throw new IllegalStateException(
                "Seeder supports only connex.tenancy.routing.mode=single-database");
        }

        List<SeedRunSummary.WorkspaceSummary> summaries = new ArrayList<>(workspaceCount);
        for (int workspaceIndex = 0; workspaceIndex < workspaceCount; workspaceIndex++) {
            long workspaceSeed = DeterministicSeederRandom.workspaceSeed(seed, workspaceIndex);
            int ordinal = workspaceIndex + 1;
            log.info("Seeding workspace {} of {}", ordinal, workspaceCount);
            TransactionTemplate transaction = new TransactionTemplate(transactionManager);
            transaction.setName("connex-seeder-workspace-" + ordinal);
            transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
            transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
            int logicalIndex = workspaceIndex;
            SeedRunSummary.WorkspaceSummary summary = transaction.execute(status ->
                writer.write(profile, workspaceSeed, logicalIndex, anchorDate));
            summaries.add(Objects.requireNonNull(
                summary,
                "Seeder workspace transaction returned no summary"
            ));
            log.info("Seeded workspace {} of {}", ordinal, workspaceCount);
        }
        return new SeedRunSummary(profile, seed, anchorDate, summaries);
    }
}
