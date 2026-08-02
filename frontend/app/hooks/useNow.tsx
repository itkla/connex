"use client";

import { createContext, useCallback, useContext, useSyncExternalStore, type ReactNode } from "react";

const NowContext = createContext<number | null>(null);

/** How often the shared clock re-reads the system time once something is watching it. */
const REFRESH_MS = 60_000;

const listeners = new Set<() => void>();
let timer: ReturnType<typeof setInterval> | null = null;
let current = 0;

function readClock(): number {
    if (current === 0 || Date.now() - current >= REFRESH_MS) current = Date.now();
    return current;
}

function subscribe(listener: () => void): () => void {
    listeners.add(listener);
    if (timer === null) {
        timer = setInterval(() => {
            current = Date.now();
            for (const notify of [...listeners]) notify();
        }, REFRESH_MS);
    }
    return () => {
        listeners.delete(listener);
        if (listeners.size === 0 && timer !== null) {
            clearInterval(timer);
            timer = null;
        }
    };
}

/**
 * Publishes a single "now" for one render pass so a server render and the hydration that
 * follows it agree on the current time.
 *
 * Reading `Date.now()` inside a client component's render body looks harmless but is not:
 * Next.js server-renders client components to produce the initial HTML, so the clock is read
 * once on the server and again — milliseconds to seconds later — during hydration. Any derived
 * text that crosses a rounding boundary in that window (a relative-time bucket, a day boundary,
 * an overdue threshold) differs between the two renders and React discards the server-rendered
 * subtree with hydration error #418.
 *
 * The value is supplied by a dynamically rendered server layout, so it is serialized into the
 * RSC payload and hydration reuses the exact number the server used. That keeps the
 * server-rendered output meaningful — a real relative time, not a placeholder swapped in after
 * mount — and costs no post-hydration text flash.
 *
 * @param value - the timestamp shared by this render pass, in milliseconds since the epoch
 * @param children - the tree that reads the shared clock
 */
export function NowProvider({ value, children }: { value: number; children: ReactNode }) {
    return <NowContext.Provider value={value}>{children}</NowContext.Provider>;
}

/**
 * The timestamp shared by the current server render and its hydration, fixed for the life of
 * the mounted tree.
 *
 * Use this instead of `Date.now()` or `new Date()` anywhere the result reaches rendered output,
 * including `useState` lazy initializers — those run during the server render too, so they
 * mismatch exactly like a bare read does. Reach for {@link useLiveNow} instead when the rendered
 * value is a relative time that would read as wrong minutes later.
 *
 * @returns the shared render timestamp in milliseconds since the epoch
 * @throws when called outside a {@link NowProvider}
 */
export function useNow(): number {
    const value = useContext(NowContext);
    if (value === null) throw new Error("useNow must be used within NowProvider");
    return value;
}

/**
 * A clock that matches the server on the first render and stays current afterwards.
 *
 * Hydration reads the shared {@link NowProvider} timestamp, so the rendered text matches the
 * server-rendered HTML exactly and React keeps the subtree. Every later render reads a
 * process-wide clock that refreshes once a minute, which keeps a long-lived page honest and
 * repairs the shared timestamp once it goes stale: the app-shell layout is not re-rendered on
 * client-side navigation, so a component mounting an hour into a session would otherwise inherit
 * an hour-old clock.
 *
 * The clock and its timer are shared by every caller, so rendering one of these per table row
 * costs one interval rather than one per row, and a tick re-renders only the components that
 * actually read it.
 *
 * @returns the current timestamp in milliseconds since the epoch
 */
export function useLiveNow(): number {
    const shared = useNow();
    const getServerSnapshot = useCallback(() => shared, [shared]);
    return useSyncExternalStore(subscribe, readClock, getServerSnapshot);
}
