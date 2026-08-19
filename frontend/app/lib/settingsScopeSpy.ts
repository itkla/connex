/**
 * One settings-scope section as the scroll-spy last observed it.
 *
 * `top` is the section's viewport-relative top from its last `IntersectionObserverEntry`, so a
 * caller can order the sections currently in the observation band without measuring again.
 */
export type ObservedSection = {
    id: string;
    isIntersecting: boolean;
    top: number;
};

/**
 * The section a reader is currently in: the highest one still inside the observation band.
 *
 * This exists as its own function because the naive version is wrong in both directions, and both
 * failures are invisible until you scroll. `IntersectionObserver` delivers only the sections whose
 * intersection *changed*, never the full set, so reducing over the callback's own entries picks the
 * topmost of whatever just moved rather than the topmost of what is on screen: scrolling down, the
 * next scope enters the band and wins the reduction while the previous one still tops it; scrolling
 * back up, the last scope's *exit* is the only entry, an "ignore an empty visible set" guard fires,
 * and the spine stays stuck on a scope the reader has left. The caller therefore keeps every
 * section's latest state and passes the whole map here on every callback.
 *
 * @param sections - every observed section's most recent state
 * @returns the id of the topmost intersecting section, or null when none intersects
 */
export function topmostIntersecting(sections: readonly ObservedSection[]): string | null {
    let topmost: ObservedSection | null = null;
    for (const section of sections) {
        if (!section.isIntersecting) continue;
        if (topmost === null || section.top < topmost.top) topmost = section;
    }
    return topmost?.id ?? null;
}
