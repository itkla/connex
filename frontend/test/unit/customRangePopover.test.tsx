/** @vitest-environment jsdom */
import type { ReactNode } from 'react';
import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import CustomRangePopover, { type CustomRangeLabels } from '@/app/components/overview/analytics/CustomRangePopover';
import { RangeCalendar } from '@/components/ui/range-calendar';
import { getActivityVolume } from '@/app/lib/api';
import { calendarPreviewSpan, rangeDays } from '@/app/lib/rangeCalendar';
import type { ActivityVolumeBucket, MemberScopeParams } from '@/app/lib/types';
import en from '@/messages/en/analytics.json';

vi.mock('@/app/lib/api', () => ({ getActivityVolume: vi.fn() }));
const viewport = vi.hoisted(() => ({ mobile: false }));
vi.mock('@/app/hooks/useIsMobile', () => ({ useIsMobile: () => viewport.mobile }));
vi.mock('motion/react', () => ({
    useReducedMotion: () => true,
    AnimatePresence: ({ children }: { children: ReactNode }) => children,
    motion: { div: ({ children, className }: { children: ReactNode; className?: string }) => <div className={className}>{children}</div> },
}));

const labels: CustomRangeLabels = {
    custom: en.AnalyticsPage.rangeCustom,
    title: en.AnalyticsPage.rangeCustomTitle,
    description: en.AnalyticsPage.rangeCustomDescription,
    start: en.AnalyticsPage.rangeCustomStart,
    end: en.AnalyticsPage.rangeCustomEnd,
    days: (count) => `${count} days`,
    activities: (count) => `${count} activities`,
    previewUnavailable: en.AnalyticsPage.rangeCustomPreviewUnavailable,
    grid: en.AnalyticsPage.rangeCustomGrid,
    previous: en.AnalyticsPage.rangeCustomPrevious,
    next: en.AnalyticsPage.rangeCustomNext,
    zoomMonths: en.AnalyticsPage.rangeCustomZoomMonths,
    zoomYears: en.AnalyticsPage.rangeCustomZoomYears,
    cancel: en.AnalyticsPage.rangeCustomCancel,
    apply: en.AnalyticsPage.rangeCustomApply,
};
const scope: MemberScopeParams = {};
const value = { from: '2026-08-10', to: '2026-08-11' };
const onApply = vi.fn();

function popover(selectedScope = scope, timezone = 'Asia/Tokyo', selectedValue = value) {
    return <CustomRangePopover active={false} value={selectedValue} locale="en" today="2026-08-10"
        timezone={timezone} scope={selectedScope} labels={labels} className="" thumb={null} onApply={onApply} />;
}

function bucket(periodStart: string, call: number): ActivityVolumeBucket {
    return { periodStart, bucketIndex: 0, call, email: 0, meeting: 0, note: 0, other: 0 };
}

function deferred() {
    let resolve: (buckets: ActivityVolumeBucket[]) => void = () => { throw new Error('Not initialized'); };
    let reject: (error: Error) => void = () => { throw new Error('Not initialized'); };
    const promise = new Promise<ActivityVolumeBucket[]>((onResolve, onReject) => { resolve = onResolve; reject = onReject; });
    return { promise, resolve, reject };
}

async function open() {
    fireEvent.click(screen.getByRole('button', { name: labels.custom }));
    await screen.findByRole('group', { name: labels.grid });
    await waitFor(() => expect(getActivityVolume).toHaveBeenCalled());
}

function assertServerLimits() {
    for (const [, , window] of vi.mocked(getActivityVolume).mock.calls) {
        expect(window).toBeDefined();
        if (!window) throw new Error('Missing preview window');
        expect(rangeDays(window)).toBeLessThanOrEqual(window.granularity === 'day' ? 120 : 731);
        expect(window.timezone).toBe('Asia/Tokyo');
        if (window.granularity === 'month') {
            const months = (Number(window.to.slice(0, 4)) - Number(window.from.slice(0, 4))) * 12
                + Number(window.to.slice(5, 7)) - Number(window.from.slice(5, 7)) + 1;
            expect(months).toBeLessThanOrEqual(120);
        }
    }
}

beforeEach(() => {
    viewport.mobile = false;
    vi.mocked(getActivityVolume).mockReset().mockResolvedValue([]);
    onApply.mockClear();
});
afterEach(cleanup);

describe('CustomRangePopover previews', () => {
    it('notifies the parent only from navigation, without reporting on mount or callback changes', () => {
        const onVisibleRangeChange = vi.fn();
        const props = { value, onChange: vi.fn(), locale: 'en', labels, maxDays: 731 };
        const mounted = render(<RangeCalendar {...props} onVisibleRangeChange={onVisibleRangeChange} />);
        expect(onVisibleRangeChange).not.toHaveBeenCalled();
        const replacement = vi.fn();
        mounted.rerender(<RangeCalendar {...props} onVisibleRangeChange={replacement} />);
        expect(replacement).not.toHaveBeenCalled();
        fireEvent.click(screen.getByRole('button', { name: labels.next }));
        expect(replacement).toHaveBeenCalledExactlyOnceWith(calendarPreviewSpan('day', new Date(2026, 8, 1)), 'day');
        expect(onVisibleRangeChange).not.toHaveBeenCalled();
    });

    it('loads both day panels on open so resizing from mobile to desktop needs no new request', async () => {
        viewport.mobile = true;
        const mounted = render(popover());
        await open();
        await screen.findByText('2 days · 0 activities');
        expect(screen.getAllByRole('grid')).toHaveLength(1);
        expect(getActivityVolume).toHaveBeenCalledTimes(1);
        expect(vi.mocked(getActivityVolume).mock.calls[0][2]).toEqual({
            from: '2026-07-26', to: '2026-10-10', granularity: 'day', timezone: 'Asia/Tokyo',
        });
        viewport.mobile = false;
        mounted.rerender(popover());
        expect(screen.getAllByRole('grid')).toHaveLength(2);
        expect(getActivityVolume).toHaveBeenCalledTimes(1);
        fireEvent.click(screen.getByRole('button', { name: labels.next }));
        await waitFor(() => expect(getActivityVolume).toHaveBeenCalledTimes(2));
        expect(vi.mocked(getActivityVolume).mock.calls[1][2]).toMatchObject(calendarPreviewSpan('day', new Date(2026, 8, 1)));
        assertServerLimits();
    });

    it('bounds requests on open, month zoom, year zoom and year paging without a request burst', async () => {
        render(popover(scope, 'Asia/Tokyo', { from: '2026-05-14', to: '2026-08-11' }));
        await open();
        expect(getActivityVolume).toHaveBeenCalledTimes(1);
        expect(vi.mocked(getActivityVolume).mock.calls[0][2]?.granularity).toBe('day');
        fireEvent.click(screen.getAllByRole('button', { name: labels.zoomMonths })[0]);
        await waitFor(() => expect(getActivityVolume).toHaveBeenCalledTimes(5));
        fireEvent.click(screen.getAllByRole('button', { name: labels.zoomYears })[0]);
        await waitFor(() => expect(getActivityVolume).toHaveBeenCalledTimes(11));
        for (const [index, direction] of [labels.previous, labels.next, labels.next].entries()) {
            fireEvent.click(screen.getByRole('button', { name: direction }));
            await waitFor(() => expect(getActivityVolume).toHaveBeenCalledTimes(17 + index * 6));
        }
        assertServerLimits();
    });

    it('renders monthly activity shapes in the year picker', async () => {
        vi.mocked(getActivityVolume).mockImplementation(async (_range, _scope, window) => window?.from === '2016-01-01'
            ? [bucket('2016-01-01', 2), bucket('2016-02-01', 4)] : []);
        render(popover());
        await open();
        fireEvent.click(screen.getAllByRole('button', { name: labels.zoomYears })[0]);
        const year = await screen.findByRole('button', { name: '2016' });
        await waitFor(() => expect([...year.querySelectorAll('span[style]')].map((bar) => bar.getAttribute('style')))
            .toEqual(['height: 50%;', 'height: 100%;', ...Array<string>(10).fill('height: 6%;')]));
        expect(screen.queryByText(/0 activities/)).toBeNull();
    });

    it('shows an accessible unavailable note after a refusal and still allows applying dates', async () => {
        vi.mocked(getActivityVolume).mockRejectedValue(new Error('400'));
        render(popover());
        await open();
        expect((await screen.findByRole('status')).textContent).toBe(labels.previewUnavailable);
        expect(screen.queryByText(/0 activities/)).toBeNull();
        fireEvent.click(screen.getByRole('button', { name: labels.apply }));
        expect(onApply).toHaveBeenCalledWith(value);
    });

    it('omits all year preview marks if one request fails, rather than displaying partial zeros', async () => {
        vi.mocked(getActivityVolume).mockImplementation(async (_range, _scope, window) => {
            if (window?.from === '2018-01-01') throw new Error('Unavailable');
            return [bucket('2016-01-01', 4)];
        });
        render(popover());
        await open();
        fireEvent.click(screen.getAllByRole('button', { name: labels.zoomYears })[0]);
        await screen.findByRole('status');
        const calendar = screen.getByRole('group', { name: labels.grid });
        expect(calendar.querySelector('.border-b')).toBeNull();
        expect(within(calendar).getByRole('button', { name: '2016' }).querySelector('span[style]')).toBeNull();
        expect(screen.queryByText(/activities/)).toBeNull();
    });

    it('reports a real zero only after the whole drafted range has loaded', async () => {
        render(popover());
        await open();
        expect(await screen.findByText('2 days · 0 activities')).toBeTruthy();
        cleanup();
        render(popover(scope, 'Asia/Tokyo', { from: '2025-01-01', to: '2026-12-31' }));
        await open();
        await act(async () => { await vi.mocked(getActivityVolume).mock.results.at(-1)?.value; });
        expect(screen.queryByText(/0 activities/)).toBeNull();
    });

    it.each(['resolve', 'reject'] as const)('aborts on close and ignores a late %s after reopening', async (outcome) => {
        const old = deferred();
        vi.mocked(getActivityVolume).mockImplementationOnce(() => old.promise);
        render(popover());
        await open();
        const signal = vi.mocked(getActivityVolume).mock.calls[0][3]?.signal;
        fireEvent.click(screen.getByRole('button', { name: labels.cancel }));
        expect(signal?.aborted).toBe(true);
        vi.mocked(getActivityVolume).mockResolvedValue([bucket(value.from, 9)]);
        await open();
        await screen.findByText('2 days · 9 activities');
        await act(async () => {
            if (outcome === 'resolve') old.resolve([bucket(value.from, 99)]);
            else old.reject(new Error('Late failure'));
        });
        expect(screen.getByText('2 days · 9 activities')).toBeTruthy();
        expect(screen.queryByRole('status')).toBeNull();
    });

    it.each(['scope', 'timezone'] as const)('aborts and replaces preview data when the %s changes', async (change) => {
        const old = deferred();
        vi.mocked(getActivityVolume).mockImplementationOnce(() => old.promise);
        const mounted = render(popover());
        await open();
        const signal = vi.mocked(getActivityVolume).mock.calls[0][3]?.signal;
        vi.mocked(getActivityVolume).mockResolvedValue([bucket(value.from, 7)]);
        const nextScope: MemberScopeParams = change === 'scope' ? { scope: 'me' } : scope;
        const timezone = change === 'timezone' ? 'America/New_York' : 'Asia/Tokyo';
        mounted.rerender(popover(nextScope, timezone));
        expect(signal?.aborted).toBe(true);
        await screen.findByText('2 days · 7 activities');
        await act(async () => old.resolve([bucket(value.from, 99)]));
        expect(screen.getByText('2 days · 7 activities')).toBeTruthy();
        expect(vi.mocked(getActivityVolume).mock.calls.at(-1)?.[1]).toBe(nextScope);
        expect(vi.mocked(getActivityVolume).mock.calls.at(-1)?.[2]?.timezone).toBe(timezone);
    });
});
