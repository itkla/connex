"use client";

import { useTheme } from "next-themes";
import { useEffect, useSyncExternalStore, type ReactNode } from "react";

const subscribe = () => () => {};
const onClient = () => true;
const onServer = () => false;

/** next-themes' storage key. Absent means the visitor has never expressed a preference. */
const THEME_KEY = "theme";

/**
 * Art-directs the landing page dark by default.
 *
 * Two things happen, and both are needed.
 *
 * The wrapper renders `.dark` on the server, so the first paint is already dark and there is no
 * flash of the light theme. `@custom-variant dark (&:is(.dark *))` matches on any ancestor and
 * `.dark` re-declares the token values, so everything inside is themed.
 *
 * A subtree class cannot reach content portalled to `document.body`, though — the language menu
 * uses `DropdownMenu.Portal` — and it cannot tell the theme toggle what the page is actually
 * showing. So when the visitor has never chosen a theme, this also promotes dark to the real
 * preference. That puts `.dark` on the document element, which portals inherit and the toggle
 * reads, keeping the whole page consistent.
 *
 * It only ever fills an empty preference. Anyone who has chosen light or dark keeps that choice,
 * and the toggle continues to work and persist.
 */
export default function LandingTheme({ children }: { children: ReactNode }) {
    const { theme, setTheme } = useTheme();
    const mounted = useSyncExternalStore(subscribe, onClient, onServer);

    useEffect(() => {
        if (window.localStorage.getItem(THEME_KEY) === null) setTheme("dark");
    }, [setTheme]);

    const optedOutOfDark = mounted && theme === "light";

    return <div className={optedOutOfDark ? undefined : "dark"}>{children}</div>;
}
