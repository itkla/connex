package ooo.klae.connex.backend.services;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.tenant.TenantWorkScope;

import ooo.klae.connex.backend.mappers.RuleMapper;

/**
 * Periodically evaluates time-based (schedule) rules. Mirrors the notification scheduler: it fans out
 * over the workspaces that have enabled schedule rules and asks the engine to run each cadence. The
 * engine's per-bucket idempotency makes a frequent tick safe — a daily rule fires once per day even
 * if this runs every few minutes. Toggle with {@code connex.rules.scheduling-enabled}.
 */
@Component
@RequiredArgsConstructor
public class RuleScheduler {

    private final RuleMapper ruleMapper;
    private final PlacementRegistry placementRegistry;
    private final TenantWorkScope tenantWorkScope;
    private final RuleEngineService ruleEngineService;

    private static final Logger log = LoggerFactory.getLogger(RuleScheduler.class);
    private static final String[] CADENCES = {"hourly", "daily", "weekly"};

    @Value("${connex.rules.scheduling-enabled:true}")
    private boolean schedulingEnabled;

    @Scheduled(
        fixedDelayString = "${connex.rules.evaluation-delay-ms:900000}",
        initialDelayString = "${connex.rules.initial-delay-ms:900000}")
    public void evaluate() {
        if (!schedulingEnabled) {
            return;
        }
        for (String catalog : placementRegistry.activeCatalogs()) {
            try {
                evaluateCatalog(catalog);
            } catch (Exception e) {
                log.warn("Schedule sweep failed for catalog {}: {}", catalog == null ? "(default)" : catalog, e.getMessage());
            }
        }
    }

    /**
     * Enumerates and runs one catalog's schedule rules. The {@code rule} table
     * is org-data, so the enumeration must run inside the catalog being swept;
     * failures are isolated per catalog by the caller and per workspace here,
     * so one bad placement never starves the rest of the fleet.
     */
    private void evaluateCatalog(String catalog) {
        for (int workspaceId : tenantWorkScope.withCatalog(catalog, ruleMapper::workspaceIdsWithEnabledScheduleRules)) {
            for (String cadence : CADENCES) {
                try {
                    tenantWorkScope.inWorkspace(workspaceId, () -> ruleEngineService.runSchedule(workspaceId, cadence));
                } catch (Exception e) {
                    log.warn("Schedule evaluation failed for workspace {} cadence {}: {}", workspaceId, cadence, e.getMessage());
                }
            }
        }
    }
}
