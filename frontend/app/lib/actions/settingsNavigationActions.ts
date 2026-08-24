import {
    BuildingLibraryIcon,
    ClipboardDocumentListIcon,
    Cog6ToothIcon,
    IdentificationIcon,
    InboxIcon,
    ShieldCheckIcon,
    UserGroupIcon,
} from "@heroicons/react/24/outline";

import type { NavAccess } from "@/app/lib/navAccess";
import {
    settingsDestination,
    type SettingsDestinationId,
} from "@/app/lib/settingsEntryPoints";
import type { ActionIcon, AppAction } from "./types";

/**
 * The command palette's settings destinations, generated from the committed settings manifest
 * (#1340 PR 7).
 *
 * Before this module the palette listed settings routes by hand in two places, and the manifest's
 * `entryPoints` was an assertion nobody could check: `navigate.settings` pushed `/settings/members`
 * while `/settings` was reachable from nowhere, and `navigate.users` pushed `/users` under a name
 * PRODUCT.md §4 bans. Here the manifest owns which destinations exist, where each one is, what it is
 * called, and what a reader may type to find it. What stays below is the part the manifest does not
 * describe: the icon, the sort weight, the stable action id, and the gate.
 */

/** How the palette presents one settings destination; the manifest owns everything else about it. */
type SettingsPalettePresentation = {
    /** The manifest entry this action stands for. */
    entryId: SettingsDestinationId;
    /**
     * The action id, unchanged by the consolidation.
     *
     * An id is the key usage history and favorites are recorded against, and it is deliberately not
     * derived from the destination's name or its route — so a job moving to its canonical address
     * keeps the identity it was invoked under, exactly as {@link AppAction.id} promises.
     */
    actionId: string;
    icon: ActionIcon;
    order: number;
    /**
     * Whether the shell's server-resolved navigation access offers this destination at all, or null
     * when it is ungated. Registering nothing rather than registering a locked door, as the gated
     * navigation actions beside these already do.
     */
    reachable: ((navAccess: NavAccess) => boolean) | null;
    /** A coarse registry-context gate evaluated per invocation; see {@link AppAction.isAvailable}. */
    isAvailable?: AppAction["isAvailable"];
};

/**
 * Every settings destination the palette registers, in the order the manifest declares them.
 *
 * The orders are the ones these actions already carried, so the eight-item navigate list the palette
 * shows for an empty query is unchanged by the rewire.
 */
const SETTINGS_PALETTE: readonly SettingsPalettePresentation[] = [
    {
        entryId: "account.home",
        actionId: "navigate.account",
        icon: IdentificationIcon,
        order: 180,
        reachable: null,
    },
    {
        entryId: "account.capture-reviews",
        actionId: "navigate.capture-reviews",
        icon: InboxIcon,
        order: 155,
        reachable: (navAccess) => navAccess.captureReviews !== "disabled",
    },
    {
        entryId: "workspace.audit-log",
        actionId: "navigate.audit-log",
        icon: ClipboardDocumentListIcon,
        order: 170,
        reachable: (navAccess) => navAccess.auditLog,
    },
    {
        entryId: "organization.administrators",
        actionId: "navigate.organization",
        icon: BuildingLibraryIcon,
        order: 90,
        reachable: null,
        isAvailable: (context) => context.can("ORGANIZATION_VIEW"),
    },
    {
        entryId: "workspace.approval-policies",
        actionId: "navigate.approval-policies",
        icon: ShieldCheckIcon,
        order: 165,
        reachable: null,
    },
    {
        entryId: "settings.home",
        actionId: "navigate.settings",
        icon: Cog6ToothIcon,
        order: 80,
        reachable: null,
    },
    {
        entryId: "workspace.diagnostics",
        actionId: "navigate.diagnostics",
        icon: Cog6ToothIcon,
        order: 81,
        reachable: (navAccess) => navAccess.diagnostics,
    },
    {
        entryId: "workspace.people-directory",
        actionId: "navigate.users",
        icon: UserGroupIcon,
        order: 160,
        reachable: null,
    },
];

/** One generated palette registration, as the manifest gate and the route gate read it. */
export type SettingsPaletteRegistration = {
    /** The manifest entry the action stands for. */
    entryId: string;
    /** The action id it is registered under. */
    actionId: string;
    /** The address it pushes, fragment included where the job is a section of a shared destination. */
    href: string;
    /** The message key it renders as its name. */
    titleKey: string;
    /** The message key holding its aliases, or null. */
    aliasKey: string | null;
};

/**
 * The generated registrations, resolved from the manifest at module load.
 *
 * Exported as data so the gates read structure instead of scraping source text for a route literal
 * that no longer exists: `settingsManifest.test.ts` reconciles these against the entries declaring
 * `command-palette`, and `navTargets.test.ts` proves every address resolves to a real page.
 */
export const SETTINGS_PALETTE_REGISTRATIONS: readonly SettingsPaletteRegistration[] =
    SETTINGS_PALETTE.map((presentation) => {
        const destination = settingsDestination(presentation.entryId);
        return {
            entryId: destination.id,
            actionId: presentation.actionId,
            href: destination.href,
            titleKey: destination.titleKey,
            aliasKey: destination.aliasKey,
        };
    });

/**
 * The palette's settings destinations for one reader.
 *
 * @param navAccess - the shell's server-resolved navigation access
 * @returns the actions to register, gated to what this reader can reach
 */
export function settingsNavigationActions(navAccess: NavAccess): readonly AppAction[] {
    const actions: AppAction[] = [];
    for (const presentation of SETTINGS_PALETTE) {
        if (presentation.reachable !== null && !presentation.reachable(navAccess)) continue;
        const destination = settingsDestination(presentation.entryId);
        actions.push({
            id: presentation.actionId,
            group: "navigate",
            labelMessageKey: destination.titleKey,
            ...(destination.aliasKey === null ? {} : { keywordsMessageKey: destination.aliasKey }),
            icon: presentation.icon,
            order: presentation.order,
            isAvailable: presentation.isAvailable,
            execute: (_context, helpers) => {
                helpers.router.push(destination.href);
            },
        });
    }
    return actions;
}
