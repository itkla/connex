/**
 * Returns a user-facing record label or null when the API supplied an identifier-shaped fallback.
 *
 * Current Radar responses use `#42` when a visible record has no name. That value is useful to
 * operators and logs, but product copy forbids raw ids. Keeping the guard at the rendering boundary
 * also protects every Radar consumer while the backend remains locale-neutral.
 */
export function radarRecordLabel(value: unknown): string | null {
    if (typeof value !== 'string') return null;
    const label = value.trim();
    if (label.length === 0 || /^#\s*\d+$/.test(label)) return null;
    return label;
}
