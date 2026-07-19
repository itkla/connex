"use client";

import { useMemo } from "react";

import { useRegisterActions } from "@/app/hooks/useActions";
import { usePinnedViews } from "@/app/hooks/usePinnedViews";
import { savedViewHref, savedViewRecordIcon, savedViewToken } from "@/app/lib/savedViewLink";
import type { AppAction } from "@/app/lib/actions/types";

const PINNED_VIEW_ACTION_BASE_ORDER = 200;

/**
 * Registers a navigation action for each of the current user's pinned saved views, so they are
 * reachable from the command palette by name. Each action carries the view's dynamic {@code label}
 * (its name) — the generic {@code navigate.savedView} key is only a fallback. Rendered inside
 * {@link PinnedViewsProvider} so it re-registers as pins change. Renders nothing.
 */
export default function PinnedViewsActionsBridge(): null {
    const { pins } = usePinnedViews();

    const actions = useMemo<readonly AppAction[]>(
        () => pins.map((pin, index) => ({
            id: `navigate.saved-view:${savedViewToken(pin)}`,
            group: "navigate",
            labelKey: "navigate.savedView",
            label: pin.name,
            icon: savedViewRecordIcon(pin.recordType),
            order: PINNED_VIEW_ACTION_BASE_ORDER + index,
            execute: (_context, helpers) => {
                helpers.router.push(savedViewHref(pin));
            },
        })),
        [pins],
    );

    useRegisterActions(actions);
    return null;
}
