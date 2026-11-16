/** @vitest-environment jsdom */
import { afterEach, describe, expect, it, vi } from 'vitest';
import { chartAxisLabelOverflow, X_AXIS_TICK_LABELS, Y_AXIS_TICK_LABELS } from '@/test/e2e/support/chart-labels';

afterEach(() => vi.restoreAllMocks());

function chartSurface(labels: readonly string[]) {
    const chart = document.createElement('div');
    chart.innerHTML = `<svg class="recharts-surface">
        <g class="recharts-cartesian-grid-horizontal"><line /></g>
        <g class="recharts-zIndex-layer_500"><g class="recharts-yAxis"></g><g class="recharts-xAxis"></g></g>
        <g class="recharts-zIndex-layer_2000">
            <g class="recharts-yAxis-tick-labels">${labels.map((label) => `<text class="recharts-cartesian-axis-tick-value">${label}</text>`).join('')}</g>
            <g class="recharts-xAxis-tick-labels"><text class="recharts-cartesian-axis-tick-value">Jan 2027</text></g>
        </g>
    </svg>`;
    const svg = chart.querySelector('svg');
    const line = chart.querySelector('line');
    if (!svg || !line) throw new Error('Expected chart surface and plot line');
    vi.spyOn(svg, 'getBoundingClientRect').mockReturnValue(new DOMRect(10, 20, 320, 240));
    vi.spyOn(line, 'getBoundingClientRect').mockReturnValue(new DOMRect(80, 28, 242, 0));
    for (const tick of chart.querySelectorAll(Y_AXIS_TICK_LABELS)) {
        vi.spyOn(tick, 'getBoundingClientRect').mockReturnValue(new DOMRect(12, 30, 60, 12));
    }
    return chart;
}

describe('chart label bounds with separate Recharts text layers', () => {
    it.each([
        ['$0', '$3.5B', '$7B', '$10.5B', '$14B'],
        ['￥0', '￥35億', '￥70億', '￥105億', '￥140億'],
    ])('finds currency labels outside the axis group (%s)', (...labels) => {
        const chart = chartSurface(labels);
        expect(chart.querySelectorAll('.recharts-yAxis .recharts-cartesian-axis-tick-value')).toHaveLength(0);
        expect([...chart.querySelectorAll(Y_AXIS_TICK_LABELS)].map((tick) => tick.textContent)).toEqual(labels);
        expect(chart.querySelector(X_AXIS_TICK_LABELS)?.textContent).toBe('Jan 2027');
        expect(chartAxisLabelOverflow(chart, Y_AXIS_TICK_LABELS)).toEqual([]);
    });

    it.each([
        { left: 8, width: 64 },
        { left: 20, width: 64 },
    ])('rejects labels extending beyond SVG or allocated axis bounds ($left/$width)', ({ left, width }) => {
        const chart = chartSurface(['￥140億']);
        const tick = chart.querySelector(Y_AXIS_TICK_LABELS);
        if (!tick) throw new Error('Expected tick label');
        vi.mocked(tick.getBoundingClientRect).mockReturnValue(new DOMRect(left, 30, width, 12));
        expect(chartAxisLabelOverflow(chart, Y_AXIS_TICK_LABELS)).toEqual(['Clipped tick: ￥140億']);
    });

    it('refuses an axis without painted labels', () => {
        expect(chartAxisLabelOverflow(chartSurface([]), Y_AXIS_TICK_LABELS)).toEqual(['Missing Y-axis labels']);
    });
});
