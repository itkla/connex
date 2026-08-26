"use client";

import { useMemo } from "react";
import { BoltIcon, FlagIcon, MegaphoneIcon } from "@heroicons/react/24/outline";

import { useRegisterActions } from "@/app/hooks/useActions";
import { settingsNavigationActions } from "@/app/lib/actions/settingsNavigationActions";
import type { AppAction } from "@/app/lib/actions/types";
import type { NavAccess } from "@/app/lib/navAccess";

type Props = {
    navAccess: NavAccess;
};

/**
 * Registers the navigation actions the seed registry cannot hold: the ones gated on an instance
 * capability or an effective permission, and every settings destination the committed manifest
 * declares a command-palette entry point for.
 *
 * The gated ones live here because the registry context carries only coarse role signals, which
 * cannot answer for custom roles; these gates are resolved from the viewer's effective permissions
 * on the server and handed to the shell. Registering nothing when access is absent keeps the palette
 * free of destinations the backend would reject.
 *
 * The settings ones live here because #1340 makes the manifest their single source of truth — their
 * ids, addresses, names, and aliases are generated in `settingsNavigationActions`, and several of
 * them are gated on the same server-resolved access as the rest of this bridge. Renders nothing.
 */
export default function NavActionsBridge({ navAccess }: Props): null {
    const actions = useMemo<readonly AppAction[]>(() => {
        const access: NavAccess = {
            goals: navAccess.goals,
            auditLog: navAccess.auditLog,
            captureReviews: navAccess.captureReviews,
            campaigns: navAccess.campaigns,
            workflows: navAccess.workflows,
            diagnostics: navAccess.diagnostics,
        };
        const gated: AppAction[] = [];
        if (navAccess.goals) {
            gated.push({
                id: "navigate.goals",
                group: "navigate",
                labelKey: "navigate.goals",
                icon: FlagIcon,
                order: 75,
                execute: (_context, helpers) => {
                    helpers.router.push("/insights/reports/goals");
                },
            });
        }
        if (navAccess.campaigns) {
            gated.push({
                id: "navigate.campaigns",
                group: "navigate",
                labelKey: "navigate.campaigns",
                icon: MegaphoneIcon,
                order: 130,
                execute: (_context, helpers) => {
                    helpers.router.push("/marketing/campaigns");
                },
            });
        }
        if (navAccess.workflows) {
            gated.push({
                id: "navigate.workflows",
                group: "navigate",
                labelKey: "navigate.workflows",
                icon: BoltIcon,
                order: 150,
                execute: (_context, helpers) => {
                    helpers.router.push("/workflows");
                },
            });
        }
        return [...gated, ...settingsNavigationActions(access)];
    }, [
        navAccess.goals,
        navAccess.auditLog,
        navAccess.captureReviews,
        navAccess.campaigns,
        navAccess.workflows,
        navAccess.diagnostics,
    ]);

    useRegisterActions(actions);
    return null;
}
