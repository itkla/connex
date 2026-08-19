/**
 * The scope section a reader is currently in: the first one, in document order, still inside the
 * scroll-spy's observation band.
 *
 * This exists as its own function because the obvious versions are wrong in ways that only appear
 * once you scroll. `IntersectionObserver` delivers only the sections whose intersection *changed*,
 * never the full set, so reducing over the callback's own entries picks the topmost of whatever just
 * moved rather than the topmost of what is on screen: scrolling down, the next scope enters the band
 * and wins while the previous one still tops it; scrolling back up, the last scope's *exit* is the
 * only entry, an "ignore an empty set" guard fires, and the spine stays stuck on a scope the reader
 * has left. The caller therefore keeps every section's latest state and passes all of it here.
 *
 * Order comes from the document, not from each entry's `boundingClientRect`. A rectangle is only
 * refreshed when that element's own intersection changes, so two sections that both sit in the band
 * for a while would be compared using one fresh measurement and one arbitrarily old one. Their
 * relative order never changes, so reading it off the section list is both exact and free.
 *
 * @param order - the section ids in document order
 * @param intersecting - the ids currently inside the band
 * @returns the id of the first section in the band, or null when none is
 */
export function activeScopeSection(
    order: readonly string[],
    intersecting: ReadonlySet<string>,
): string | null {
    return order.find((id) => intersecting.has(id)) ?? null;
}
