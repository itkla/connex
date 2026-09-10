"use client";

import { useTheme } from "next-themes";
import { useSyncExternalStore, type ReactNode } from "react";

/**
 * Art-directs the landing page dark by default without changing the product's theme.
 *
 * The app defaults to `system`, which is right for a CRM people sit in all day; the marketing page
 * wants the opposite — the brand green reads best on near-black, and dark-by-default is the pattern
 * design-led SaaS has converged on. Rather than move the global default, this scopes `.dark` to the
 * landing subtree: `@custom-variant dark (&:is(.dark *))` matches on any ancestor, and `.dark`
 * re-declares the token values, so everything inside renders dark.
 *
 * Only an explicit `light` choice opts out, so the toggle still works and still persists. The class
 * is applied on the server render too, which is why there is no flash of the light theme.
 */
const subscribe = () => () => {};
const onClient = () => true;
const onServer = () => false;

export default function LandingTheme({ children }: { children: ReactNode }) {
    const { theme } = useTheme();
    const mounted = useSyncExternalStore(subscribe, onClient, onServer);

    const optedOutOfDark = mounted && theme === "light";

    return <div className={optedOutOfDark ? undefined : "dark"}>{children}</div>;
}
