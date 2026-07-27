package ooo.klae.connex.backend.dto;

import java.util.Map;

/** Registry-complete post-teardown residual report for one workspace. */
public record TenantResidualReport(
        int workspaceId,
        Map<String, Long> tableRows,
        long totalRows,
        TenantStorageResidual storage) {

    public TenantResidualReport {
        tableRows = Map.copyOf(tableRows);
    }

    /** Whether every org-data table and storage metadata check is empty. */
    public boolean clean() {
        return totalRows == 0 && storage.clean();
    }
}
