"use client";

import { useCallback, useEffect, useRef, useState, useSyncExternalStore } from "react";
import { useReducedMotion } from "motion/react";

import { durationExpressiveMs } from "@/app/lib/motion";

/**
 * Subscribes to same-document fragment changes. A fragment set by a client-side navigation arrives
 * in the first snapshot rather than as an event, so this only has to carry the case where the
 * fragment changes while the page stays mounted.
 */
function subscribeToHash(onChange: () => void): () => void {
    window.addEventListener("hashchange", onChange);
    return () => window.removeEventListener("hashchange", onChange);
}

function hashSnapshot(): string {
    return window.location.hash;
}

/**
 * How long after an arrival the sections above the target are still allowed to move it.
 *
 * A consolidated destination's sections load their own data, so the content above the target keeps
 * growing after the first scroll: a roster resolving four serial requests can add hundreds of pixels
 * and push the target back off the top of the viewport. One animation frame catches a layout that
 * has already settled and nothing else, so the scroll is held against the geometry until the page
 * has plausibly finished arriving. Bounded, because after that a movement is the reader's own.
 */
const SETTLE_WINDOW_MS = 2000;

/** What a page needs to make its sections arrive at: a ref registrar, and which one was arrived at. */
export type SectionArrival = {
    /** Registers a section's element under its slug; pass to the section wrapper's `ref`. */
    register: (section: string) => (element: HTMLElement | null) => void;
    /** The section the current fragment arrived at, while its arrival is still worth marking. */
    arrived: string | null;
};

/**
 * Brings a page's addressable sections into view when the reader arrives at one of them by fragment.
 *
 * The browser cannot do this for a consolidated page on its own. A client-side navigation resolves
 * the fragment against whatever is mounted at the time, which for a route with a `loading.tsx` is
 * the skeleton, and the resolution is then discarded: the reader lands at the top of a long page
 * with no sign that they asked for a section of it. So the page scrolls itself, once per
 * navigation, and re-asserts on the next frame because the sections below the fold are still
 * settling their own heights when the first call runs.
 *
 * The guard is keyed on the fragment rather than set once, so leaving a section and coming back to
 * it scrolls again instead of silently doing nothing the second time.
 *
 * Only the first scroll animates. The corrections that follow it are instant, because they are the
 * page holding its position while content settles above the target, and a surface that re-animates
 * every time a list resolves reads as a page that cannot make up its mind.
 *
 * Arrival is also marked, briefly: a reader who followed a deep link into the middle of a page
 * needs to know which of its sections answered them. The mark clears itself, because it is an
 * arrival and not a selection.
 *
 * @param sections - the slugs this page can be arrived at, so a foreign fragment is ignored
 * @returns the ref registrar and the section currently arrived at
 */
export function useSectionArrival(sections: readonly string[]): SectionArrival {
    const reduceMotion = useReducedMotion() ?? false;
    const hash = useSyncExternalStore(subscribeToHash, hashSnapshot, () => "");
    const elements = useRef(new Map<string, HTMLElement>());
    const scrolledForHash = useRef<string | null>(null);
    const wanted = useRef<string | null>(null);
    const [appearances, setAppearances] = useState(0);
    const [arrived, setArrived] = useState<string | null>(null);

    const target = hash.startsWith("#") ? hash.slice(1) : "";
    const section = sections.includes(target) ? target : null;

    /**
     * Registers a section's element, and wakes the arrival effect when the one being waited for
     * appears.
     *
     * A page that renders its regions unconditionally has the target in hand before the effect
     * below first runs, and this counter never moves. A page that gates them behind its own load
     * does not: the organization's General destination renders a skeleton with no section ids in
     * it, so the effect finds nothing and returns without claiming the hash. The ref callback that
     * arrives when the read resolves would then change nothing the effect depends on, and a reader
     * who deep-linked into the middle of the page would be left sitting at the top of it — which is
     * precisely what a redirect that carries a section exists to prevent.
     */
    const register = useCallback(
        (slug: string) => (element: HTMLElement | null) => {
            if (element === null) {
                elements.current.delete(slug);
                return;
            }
            elements.current.set(slug, element);
            if (slug === wanted.current) setAppearances((count) => count + 1);
        },
        [],
    );

    useEffect(() => {
        if (scrolledForHash.current !== hash) scrolledForHash.current = null;
    }, [hash]);

    useEffect(() => {
        wanted.current = section;
    }, [section]);

    useEffect(() => {
        if (section === null || scrolledForHash.current === hash) return;
        const element = elements.current.get(section);
        if (!element) return;
        scrolledForHash.current = hash;
        const hold = () => element.scrollIntoView({ block: "start" });

        element.scrollIntoView({ behavior: reduceMotion ? "auto" : "smooth", block: "start" });
        setArrived(section);
        const frame = requestAnimationFrame(hold);

        const observer = typeof ResizeObserver === "undefined" ? null : new ResizeObserver(hold);
        if (observer) {
            for (const registered of elements.current.values()) {
                const relation = registered.compareDocumentPosition(element);
                if (relation & Node.DOCUMENT_POSITION_FOLLOWING) observer.observe(registered);
            }
        }
        const stop = window.setTimeout(() => observer?.disconnect(), SETTLE_WINDOW_MS);

        return () => {
            cancelAnimationFrame(frame);
            window.clearTimeout(stop);
            observer?.disconnect();
        };
    }, [hash, section, reduceMotion, appearances]);

    useEffect(() => {
        if (arrived === null) return;
        const timer = window.setTimeout(() => setArrived(null), durationExpressiveMs * 4);
        return () => window.clearTimeout(timer);
    }, [arrived]);

    return { register, arrived };
}
