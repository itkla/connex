import SettingsAvailabilityNotice from "@/app/components/settings/SettingsAvailabilityNotice";

/**
 * Fail-closed route state for an instance-capability lookup that could not be completed.
 *
 * The `retry` posture of {@link SettingsAvailabilityNotice}, under the name the routes that render
 * it already import. It stays a component of its own because a route-level dead end reads better as
 * one named state than as a posture argument, and because the three routes rendering it assert on
 * that identity.
 */
export default function CapabilityUnavailablePage() {
    return <SettingsAvailabilityNotice state="retry" />;
}
