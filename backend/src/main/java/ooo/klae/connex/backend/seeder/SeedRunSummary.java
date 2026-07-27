package ooo.klae.connex.backend.seeder;

import java.time.LocalDate;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Immutable row-count summary for one complete deterministic seed invocation.
 */
public record SeedRunSummary(
        SeederProperties.Profile profile,
        long seed,
        LocalDate anchorDate,
        List<WorkspaceSummary> workspaces) {

    public SeedRunSummary {
        workspaces = List.copyOf(workspaces);
    }

    /**
     * Per-workspace logical identity and inserted row counts.
     *
     * <p>Insertion order is preserved so that the logged summary is byte-reproducible;
     * {@code Map.copyOf} would randomize iteration order per JVM.
     */
    public record WorkspaceSummary(int ordinal, String slug, Map<String, Integer> rowCounts) {

        public WorkspaceSummary {
            rowCounts = Collections.unmodifiableMap(new LinkedHashMap<>(rowCounts));
        }
    }
}
