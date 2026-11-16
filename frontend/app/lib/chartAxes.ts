/** Uses Recharts' measured widest formatted tick, plus its margin, to size a numeric Y axis. */
export function autoYAxisWidth(tickFormatter?: (value: number) => string) {
    return { width: 'auto' as const, tickSize: 0, tickMargin: 8, tickFormatter };
}

/** Keeps every January visible and omits neighboring month ticks that would crowd the year label. */
export function monthAxisTicks(keys: readonly string[]): string[] {
    if (keys.length === 0) return [];
    const indices = new Set<number>();
    keys.forEach((key, index) => {
        if (key.slice(5, 7) === '01') indices.add(index);
    });
    const gap = Math.max(1, Math.ceil((keys.length - 1) / 4));
    for (const index of [0, keys.length - 1, ...keys.map((_, index) => index)]) {
        if ([...indices].every((selected) => Math.abs(selected - index) >= gap)) indices.add(index);
    }
    return [...indices].sort((a, b) => a - b).map((index) => keys[index]);
}
