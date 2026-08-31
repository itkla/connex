package ooo.klae.connex.backend.work;

import ooo.klae.connex.backend.dto.WorkItemActionResponse;
import ooo.klae.connex.backend.dto.WorkItemSource;

/** Source adapter for bounded My Work reads and authoritative actions. */
public interface WorkItemProvider {
    /** Returns the single source owned by this provider. */
    WorkItemSource source();

    /** Loads the provider's top bounded candidates and known totals. */
    WorkItemProviderResult load(WorkItemProviderQuery query);

    /** Executes one source-owned action after current-state validation. */
    WorkItemActionResponse execute(int sourceId, WorkItemActionCommand command);
}
