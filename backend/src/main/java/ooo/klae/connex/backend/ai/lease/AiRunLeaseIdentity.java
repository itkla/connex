package ooo.klae.connex.backend.ai.lease;

import java.util.UUID;

import org.springframework.stereotype.Component;

/**
 * This JVM's stable run-lease owner id, minted once at construction.
 *
 * <p>The id is per-process rather than per-claim so that an incident can be answered from the table
 * alone — which instance held this run, and when did another take it over. Per-process ids are safe
 * because claim uniqueness comes from the strictly increasing {@code epoch}, not from the owner
 * string: the same instance re-claiming the same subject still gets a fresh, greater epoch.
 */
@Component
public class AiRunLeaseIdentity {

    private final String owner = UUID.randomUUID().toString();

    /**
     * Returns this instance's owner id.
     *
     * @return the owner id written into {@code ai_run_lease.owner}
     */
    public String owner() {
        return owner;
    }
}
