"use client";

import { useMemo } from "react";
import { FlagIcon, InboxIcon } from "@heroicons/react/24/outline";

import { useRegisterActions } from "@/app/hooks/useActions";
import type { AppAction } from "@/app/lib/actions/types";
import type { NavAccess } from "@/app/lib/navAccess";

type Props = {
    navAccess: NavAccess;
};

/**
 * Registers the navigation actions that are gated on an instance capability or an effective
 * permission. They live in a bridge rather than the seed registry because the registry context
 * carries only coarse role signals, while these gates are resolved on the server and handed to the
 * shell. Registering nothing when access is absent keeps the palette free of destinations the
 * backend would reject. Renders nothing.
 */
export default function NavActionsBridge({ navAccess }: Props): null {
    const actions = useMemo<readonly AppAction[]>(() => {
        const gated: AppAction[] = [];
        if (navAccess.goals) {
            gated.push({
                id: "navigate.goals",
                group: "navigate",
                labelKey: "navigate.goals",
                icon: FlagIcon,
                order: 75,
                execute: (_context, helpers) => {
                    helpers.router.push("/overview/reports/goals");
                },
            });
        }
        if (navAccess.captureReviews) {
            gated.push({
                id: "navigate.capture-reviews",
                group: "navigate",
                labelKey: "navigate.captureReviews",
                icon: InboxIcon,
                order: 155,
                keywordsKey: "keywords.navigate.captureReviews",
                execute: (_context, helpers) => {
                    helpers.router.push("/account/connections/reviews");
                },
            });
        }
        return gated;
    }, [navAccess.goals, navAccess.captureReviews]);

    useRegisterActions(actions);
    return null;
}
