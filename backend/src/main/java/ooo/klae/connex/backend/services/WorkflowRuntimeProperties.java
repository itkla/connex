package ooo.klae.connex.backend.services;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Deployment gate that remains off until the V144 lease runtime is installed everywhere. */
@Component
public class WorkflowRuntimeProperties {

    private final boolean enabled;

    public WorkflowRuntimeProperties(
            @Value("${connex.workflows.runtime.enabled:false}") boolean enabled) {
        this.enabled = enabled;
    }

    public boolean enabled() {
        return enabled;
    }
}
