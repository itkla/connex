const AI_AVAILABILITY_EVENT = "connex:ai-availability-changed";

/** Rechecks assistant readiness after a successful provider or workspace-governance edit. */
export function publishAiAvailabilityChange(): void {
    if (typeof window !== "undefined") window.dispatchEvent(new Event(AI_AVAILABILITY_EVENT));
}

/** Observes local AI configuration changes without retaining any provider configuration. */
export function subscribeAiAvailabilityChange(listener: () => void): () => void {
    window.addEventListener(AI_AVAILABILITY_EVENT, listener);
    return () => window.removeEventListener(AI_AVAILABILITY_EVENT, listener);
}
