package ooo.klae.connex.backend.services;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Rolling-deployment fence for the {@code document} automation record type.
 *
 * <p>A binary that predates document automation recognizes {@code create_task},
 * {@code log_activity}, and {@code create_note} but attaches them only for {@code person} and
 * {@code deal}, so it would persist an unattached task, activity, or note for a {@code document}
 * run, and its {@code SegmentMapper} has no {@code document} branch, so every canonical document
 * run fails closed with {@code record_unavailable}. Such a binary can share this database during a
 * rolling upgrade or after a rollback, so {@code document} stays refused until an operator asserts
 * that no such binary remains.
 *
 * <p>The fence is {@code connex.workflows.document-automation.enabled}
 * ({@code CONNEX_WORKFLOWS_DOCUMENT_AUTOMATION_ENABLED}), default {@code false}. While it is
 * closed, {@link RuleDefinitionValidator} refuses {@code document} everywhere a definition is
 * validated — authoring, publication, manual runs, simulation, and every runtime revalidation —
 * and {@link RuleTriggerPublisher} enqueues no document trigger, so an already-authored document
 * workflow goes inert instead of reaching either runtime.
 *
 * <p>Rollout order: install the document-capable binary on every node with the fence closed, then
 * open it on every node. Roll back in the reverse order — close the fence everywhere first, so no
 * document trigger is enqueued, before replacing any binary.
 *
 * <p>Removal: once no supported rollout window can contain a binary that predates document
 * automation, delete this component, the property, and its environment variable, and make
 * {@code document} unconditional in {@link RuleDefinitionValidator}.
 */
@Component
public class WorkflowDocumentAutomationGate {

    private static final String FENCED_RECORD_TYPE = "document";

    private final boolean enabled;

    /**
     * Reads the deployment fence.
     *
     * @param enabled whether this deployment has opened document automation
     */
    public WorkflowDocumentAutomationGate(
            @Value("${connex.workflows.document-automation.enabled:false}") boolean enabled) {
        this.enabled = enabled;
    }

    /**
     * Returns whether this deployment has opened document automation.
     *
     * @return {@code true} when the fence is open
     */
    public boolean enabled() {
        return enabled;
    }

    /**
     * Returns whether a record type may be authored, triggered, and executed on this deployment.
     *
     * @param recordType normalized automation record type, or {@code null}
     * @return {@code true} unless the fenced record type is requested while the fence is closed
     */
    public boolean permits(String recordType) {
        return enabled || !FENCED_RECORD_TYPE.equals(recordType);
    }
}
