import {
    BuildingOffice2Icon,
    ExclamationTriangleIcon,
    PowerIcon,
    UserGroupIcon,
} from "@heroicons/react/24/outline";
import { useTranslations } from "next-intl";
import type { ComponentType } from "react";

import PermissionsUnavailable from "@/app/components/PermissionsUnavailable";
import WorkspaceUnavailableRetry from "@/app/components/WorkspaceUnavailableRetry";
import type { SettingsAvailabilityState } from "@/app/lib/settingsManifest";

const STATE_ICON: Record<SettingsAvailabilityState, ComponentType<{ className?: string }>> = {
    managed: BuildingOffice2Icon,
    "not-enabled": PowerIcon,
    "ask-admin": UserGroupIcon,
    retry: ExclamationTriangleIcon,
};

const STATE_KEY: Record<Exclude<SettingsAvailabilityState, "retry">, string> = {
    managed: "managed",
    "not-enabled": "notEnabled",
    "ask-admin": "askAdmin",
};

/**
 * The in-place explanation a capability- or permission-managed settings destination gives about
 * itself, in the four postures the manifest's {@link SettingsAvailabilityState} names.
 *
 * This is the presentation half of #1340's rule that a managed destination never silently vanishes
 * or teleports the reader elsewhere: it stays where its name says it is and says which of these it
 * is. `managed`, `not-enabled`, and `ask-admin` are settled answers and carry no action — a button
 * that cannot change the answer is worse than none. `retry` is the only unsettled one, so it is the
 * only one that acts: {@link WorkspaceUnavailableRetry} re-runs the server render, which re-reads
 * the capabilities the state was resolved from.
 *
 * The retry posture reads the shipped `CapabilityUnavailable` copy rather than a fourth string of
 * its own. That copy already says exactly this in the product's error dialect, and it is what the
 * three routes rendering `CapabilityUnavailablePage` show today; duplicating it would create two
 * translations of one sentence and let them drift.
 *
 * Free of its own state and of client-only APIs, so it renders in a server tree (a settings route
 * that resolved its gate on the server) and inside a client panel that resolved one after mounting.
 *
 * @param state - which posture the destination is in
 * @param variant - `page` for a route-level state, `inline` for one section of a page that renders
 * @param title - copy specific to this destination, where it says more than the posture's own
 * @param body - the explanation specific to this destination, for the same reason
 */
export default function SettingsAvailabilityNotice({
    state,
    variant = "page",
    title,
    body,
}: {
    state: SettingsAvailabilityState;
    variant?: "page" | "inline";
    title?: string;
    body?: string;
}) {
    const tState = useTranslations("SettingsAvailability");
    const tRetry = useTranslations("CapabilityUnavailable");
    const resolvedTitle = title
        ?? (state === "retry" ? tRetry("title") : tState(`${STATE_KEY[state]}Title`));
    const resolvedBody = body
        ?? (state === "retry" ? tRetry("body") : tState(`${STATE_KEY[state]}Body`));
    const action = state === "retry"
        ? (
            <WorkspaceUnavailableRetry
                label={tRetry("retry")}
                pendingLabel={tRetry("retrying")}
                variant={variant === "inline" ? "outline" : undefined}
                size={variant === "inline" ? "sm" : undefined}
            />
        )
        : undefined;

    if (variant === "inline") {
        return (
            <PermissionsUnavailable
                variant="inline"
                icon={STATE_ICON[state]}
                title={resolvedTitle}
                body={resolvedBody}
                action={action}
            />
        );
    }
    return (
        <PermissionsUnavailable
            icon={STATE_ICON[state]}
            title={resolvedTitle}
            body={resolvedBody}
            action={action}
        />
    );
}
