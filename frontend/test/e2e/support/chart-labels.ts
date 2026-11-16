export const Y_AXIS_TICK_LABELS = '.recharts-yAxis-tick-labels .recharts-cartesian-axis-tick-value';
export const X_AXIS_TICK_LABELS = '.recharts-xAxis-tick-labels .recharts-cartesian-axis-tick-value';

/** Measures label boxes in Recharts' separate text layer against the SVG and allocated axis space. */
export function chartAxisLabelOverflow(element: Element, labelSelector: string): string[] {
    const svg = element.querySelector('svg.recharts-surface');
    if (!svg) return ['Missing chart surface'];
    const plotLine = element.querySelector('.recharts-cartesian-grid-horizontal line');
    if (!plotLine) return ['Missing plot grid'];
    const ticks = Array.from(element.querySelectorAll(labelSelector));
    if (ticks.length === 0) return ['Missing Y-axis labels'];
    const bounds = svg.getBoundingClientRect();
    const axisRight = plotLine.getBoundingClientRect().left;
    const axisWidth = axisRight - bounds.left;
    return ticks.flatMap((tick) => {
        const box = tick.getBoundingClientRect();
        return box.left < bounds.left - 0.5 || box.right > bounds.right + 0.5
            || box.right > axisRight + 0.5 || box.width > axisWidth + 0.5
            ? [`Clipped tick: ${tick.textContent}`] : [];
    });
}
