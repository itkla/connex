/** @vitest-environment jsdom */
import { act } from 'react';
import { renderToString } from 'react-dom/server';
import { hydrateRoot, type Root } from 'react-dom/client';
import { NextIntlClientProvider, useFormatter, useTimeZone } from 'next-intl';
import { describe, expect, it, vi } from 'vitest';
import { formatCalendarDate, formatDate, formatDateTime, formatShortDate, formatUtcDateTime, parseCalendarDateUtc, parseMysqlDateTime, toDatetimeLocalValue, toMysqlDateTime } from '@/app/lib/utils';
import { calendarDayInTimeZone, greetingName, resolveDisplayTimeZone } from '@/app/lib/timeZone';
import { greetingKey } from '@/app/components/me/meData';
import { formatDue } from '@/app/components/activity/tasks/taskDue';
import { buildEvents, layoutTimedEvents, nextCalendarEvent } from '@/app/lib/calendar';

globalThis.IS_REACT_ACT_ENVIRONMENT = true;

describe('profile time zone display', () => {
    it.each(['2026-11-04T23:30:00', '2026-11-04 23:30:00', '2026-11-04T23:30:00Z'])(
        'reads %s as the same UTC instant', (value) => {
            expect(formatDateTime(value, 'en-US', 'Asia/Tokyo')).toBe('Nov 5, 2026, 8:30 AM');
            expect(formatDateTime(value, 'en-US', 'UTC')).toBe('Nov 4, 2026, 11:30 PM');
            expect(formatUtcDateTime(value, 'ja', '—', 'Asia/Tokyo')).toBe('2026/11/05 8:30');
            expect(formatDate(value, 'en-US', 'Asia/Tokyo')).toBe('November 5, 2026');
        },
    );

    it('preserves explicit offsets', () => {
        expect(formatDateTime('2026-11-05T08:30:00+09:00', 'en-US', 'UTC')).toBe('Nov 4, 2026, 11:30 PM');
    });

    it.each(['Asia/Tokyo', 'Pacific/Kiritimati', 'America/Los_Angeles', 'Pacific/Honolulu', 'UTC'])(
        'preserves calendar dates in %s', (timeZone) => {
            expect(formatDate('2026-11-05', 'en-US', timeZone)).toBe('November 5, 2026');
            expect(formatShortDate('2026-11-05', 'en-US', timeZone)).toBe('Nov 5');
            expect(formatCalendarDate('2026-11-05', 'ja', { month: 'long', day: 'numeric', timeZone })).toBe('11月5日');
        },
    );

    it('honors both sides of spring and fall DST changes', () => {
        expect(formatDateTime('2026-03-08T06:30:00', 'en-US', 'America/New_York')).toBe('Mar 8, 2026, 1:30 AM');
        expect(formatDateTime('2026-03-08T07:30:00', 'en-US', 'America/New_York')).toBe('Mar 8, 2026, 3:30 AM');
        expect(formatDateTime('2026-11-01T05:30:00', 'en-US', 'America/New_York')).toBe('Nov 1, 2026, 1:30 AM');
        expect(formatDateTime('2026-11-01T06:30:00', 'en-US', 'America/New_York')).toBe('Nov 1, 2026, 1:30 AM');
    });

    it('round-trips civil activity inputs and rejects missing DST times', () => {
        expect(toDatetimeLocalValue('2026-11-04T23:30:00', 'Asia/Tokyo')).toBe('2026-11-05T08:30');
        expect(toMysqlDateTime('2026-11-05T08:30', 'Asia/Tokyo')).toBe('2026-11-04 23:30:00');
        expect(() => toMysqlDateTime('2026-03-08T02:30', 'America/New_York')).toThrow('Invalid date');
        expect(toMysqlDateTime('2026-11-01T01:30', 'America/New_York')).toBe('2026-11-01 05:30:00');
        expect(toMysqlDateTime('2026-11-01T01:30', 'America/New_York', '2026-11-01T06:30:00')).toBe('2026-11-01 06:30:00');
    });

    it('rejects impossible calendar days and keeps empty/invalid fallbacks', () => {
        expect(parseCalendarDateUtc('2026-02-30')).toBeNaN();
        expect(formatDate(undefined, 'en')).toBe('—');
        expect(formatDateTime('invalid', 'en')).toBe('invalid');
        expect(formatUtcDateTime(null, 'en', 'missing', 'Asia/Tokyo')).toBe('missing');
    });

    it('uses profile, workspace, then UTC without a host-dependent fallback', () => {
        expect(resolveDisplayTimeZone('Asia/Tokyo', 'America/New_York')).toBe('Asia/Tokyo');
        expect(resolveDisplayTimeZone('invalid', 'America/New_York')).toBe('America/New_York');
        expect(resolveDisplayTimeZone(null, 'invalid')).toBe('UTC');
        expect(resolveDisplayTimeZone()).toBe('UTC');
    });

    it('uses the same civil day and greeting on Dashboard and My Work', () => {
        const now = Date.parse('2026-11-04T23:30:00Z');
        expect(calendarDayInTimeZone(now, 'Asia/Tokyo')).toBe('2026-11-05');
        expect(greetingKey('Asia/Tokyo', now)).toBe('morning');
        expect(greetingKey('UTC', now)).toBe('evening');
        expect(greetingKey('invalid', now)).toBe(greetingKey('UTC', now));
        expect(greetingName('佐藤 美咲', 'ja')).toBe('佐藤 美咲');
        expect(greetingName('Mika Sato', 'en')).toBe('Mika');
    });

    it('computes due-day urgency from profile days across DST', () => {
        const now = Date.parse('2026-03-09T03:30:00Z');
        expect(formatDue('2026-03-09', (key) => key, 'en', now, 'America/New_York')).toEqual({ label: 'dueTomorrow', tone: 'soon' });
        expect(formatDue('2026-03-09', (key) => key, 'en', now, 'Asia/Tokyo')).toEqual({ label: 'dueToday', tone: 'today' });
    });

    it.each(['Asia/Tokyo', 'America/Los_Angeles'])(
        'keeps all-day keys fixed while grouping and placing instants in %s', (timeZone) => {
            const events = buildEvents({
                tasks: [{ id: 1, description: 'Follow up', completed: false, status: 'todo', position: 0,
                    dueDate: '2026-11-05', assignedToId: 1, createdAt: '2026-11-01T00:00:00', updatedAt: '2026-11-01T00:00:00' }],
                activities: [{ id: 2, subject: 'Call', type: 'Call', createdById: 1, timestamp: '2026-11-04T23:30:00' }],
            }, timeZone);
            expect(events[0].dayKey).toBe('2026-11-05');
            expect(events[0].startMs).toBe(Date.UTC(2026, 10, 5));
            expect(events[1].dayKey).toBe(timeZone === 'Asia/Tokyo' ? '2026-11-05' : '2026-11-04');
            expect(layoutTimedEvents(events, 50, timeZone)[0].startMin).toBe(timeZone === 'Asia/Tokyo' ? 8 * 60 + 30 : 15 * 60 + 30);
        },
    );

    it('keeps tomorrow’s all-day task behind an activity later today west of UTC', () => {
        const events = buildEvents({
            tasks: [{ id: 1, description: 'Follow up', completed: false, status: 'todo', position: 0,
                dueDate: '2026-11-05', assignedToId: 1, createdAt: '2026-11-01T00:00:00', updatedAt: '2026-11-01T00:00:00' }],
            activities: [{ id: 2, subject: 'Call', type: 'Call', createdById: 1, timestamp: '2026-11-05T05:00:00' }],
        }, 'America/Los_Angeles');
        expect(nextCalendarEvent(events, Date.parse('2026-11-05T04:00:00Z'), 'America/Los_Angeles')?.id).toBe('activity-2');
        const dueToday = { ...events[0], dayKey: '2026-11-04', startMs: Date.UTC(2026, 10, 4) };
        expect(nextCalendarEvent([dueToday, events[1]], Date.parse('2026-11-05T04:00:00Z'), 'America/Los_Angeles')?.id).toBe('activity-2');
        expect(nextCalendarEvent(events, Date.parse('2026-11-05T06:00:00Z'), 'America/Los_Angeles')?.id).toBe('task-1');
        expect(nextCalendarEvent(events, Date.parse('2026-11-06T08:01:00Z'), 'America/Los_Angeles')).toBeNull();
    });

    it('hydrates the same instant and calendar day using the serialized provider zone', async () => {
        function Dates() {
            const zone = useTimeZone();
            const format = useFormatter();
            return <p>{formatDateTime('2026-11-04T23:30:00', 'en-US', zone)} | {format.dateTime(parseCalendarDateUtc('2026-11-05'), { month: 'short', day: 'numeric', timeZone: 'UTC' })} | {format.dateTime(parseMysqlDateTime('2026-11-04T23:30:00'), { hour: 'numeric', minute: 'numeric' })}</p>;
        }
        const tree = <NextIntlClientProvider locale="en-US" timeZone="Asia/Tokyo"><Dates /></NextIntlClientProvider>;
        const container = document.createElement('div');
        container.innerHTML = renderToString(tree);
        const server = container.textContent;
        const onRecoverableError = vi.fn();
        let root: Root | undefined;
        await act(() => { root = hydrateRoot(container, tree, { onRecoverableError }); });
        expect(container.textContent).toBe(server);
        expect(server).toBe('Nov 5, 2026, 8:30 AM | Nov 5 | 8:30 AM');
        expect(onRecoverableError).not.toHaveBeenCalled();
        await act(() => root?.unmount());
    });
});
