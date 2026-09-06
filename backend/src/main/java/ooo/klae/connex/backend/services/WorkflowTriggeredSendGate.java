package ooo.klae.connex.backend.services;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Startup-bound deployment fence for the {@code send_message} automation action. */
@Component
public class WorkflowTriggeredSendGate {

    private final boolean enabled;
    private final int recipientLimit;
    private final int dispatchPageSize;

    /**
     * Captures the deployment fence at application startup.
     *
     * @param enabled whether this backend instance starts open for triggered sends
     * @param recipientLimit maximum recipients enrolled by one scheduled send-message run
     * @param dispatchPageSize maximum pending deliveries selected by one worker page
     */
    @Autowired
    public WorkflowTriggeredSendGate(
            @Value("${connex.workflows.triggered-send.enabled:false}") boolean enabled,
            @Value("${connex.workflows.triggered-send.recipient-limit:200}") int recipientLimit,
            @Value("${connex.workflows.triggered-send.dispatch-page-size:200}") int dispatchPageSize) {
        if (recipientLimit < 1 || recipientLimit > 500) {
            throw new IllegalArgumentException(
                    "connex.workflows.triggered-send.recipient-limit must be between 1 and 500");
        }
        if (dispatchPageSize < 1 || dispatchPageSize > 1000) {
            throw new IllegalArgumentException(
                    "connex.workflows.triggered-send.dispatch-page-size must be between 1 and 1000");
        }
        this.enabled = enabled;
        this.recipientLimit = recipientLimit;
        this.dispatchPageSize = dispatchPageSize;
    }

    WorkflowTriggeredSendGate(boolean enabled) {
        this(enabled, 200, 200);
    }

    /** Returns whether triggered-send automation is enabled on this deployment. */
    public boolean enabled() {
        return enabled;
    }

    /** Returns the validated maximum number of recipients for one scheduled send-message run. */
    public int recipientLimit() {
        return recipientLimit;
    }

    /** Returns the validated maximum number of deliveries selected by one worker page. */
    public int dispatchPageSize() {
        return dispatchPageSize;
    }

    /**
     * Returns whether the supplied normalized action token may be validated.
     *
     * @param actionType normalized action token
     * @return whether validation may accept the token
     */
    public boolean permits(String actionType) {
        return enabled() || !"send_message".equals(actionType);
    }
}
