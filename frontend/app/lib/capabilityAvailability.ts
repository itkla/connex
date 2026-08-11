/** Whether an instance capability is enabled, disabled, or could not be resolved. */
export type CapabilityAvailability = "enabled" | "disabled" | "unavailable";

/** Maps a resolved boolean or failed lookup to the shared capability availability state. */
export function capabilityAvailability(enabled: boolean | null): CapabilityAvailability {
    if (enabled === null) return "unavailable";
    return enabled ? "enabled" : "disabled";
}
