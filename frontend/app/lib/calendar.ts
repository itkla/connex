import type { Task, Activity, Deal, Note, Contact } from '@/app/lib/types';
import { parseMysqlDateTime } from '@/app/lib/utils';
import { isDealClosed } from '@/app/components/records/deals/dealOutcome';
import { noteContentToPlainText } from '@/app/lib/references';

/**
 * The four record kinds the calendar surfaces, in filter/display order.
 * Priority ordering (which wins a crowded day cell) is separate — see {@link KIND_PRIORITY}.
 */
export type CalendarEventKind = 'task' | 'activity' | 'deal' | 'note';

export const EVENT_KINDS: readonly CalendarEventKind[] = ['task', 'activity', 'deal', 'note'];

/** Importance ranking used to order a day's dots/chips: deals first, notes last. */
const KIND_PRIORITY: Record<CalendarEventKind, number> = { deal: 0, task: 1, activity: 2, note: 3 };

/**
 * Shared shape of a record projected onto the calendar. `startMs` drives ordering
 * and timeline placement; `allDay` distinguishes date-only records (tasks, deals)
 * from timed ones (activities, notes). `draggable` marks the two kinds whose date
 * is reschedulable by drag: any task, and any open deal.
 */
interface CalendarEventBase {
    id: string;
    entityId: number;
    title: string;
    startMs: number;
    allDay: boolean;
    dayKey: string;
    href: string;
    draggable: boolean;
}

/**
 * A calendar event, discriminated on `kind` so `raw` narrows to the concrete record
 * type — consumers can read kind-specific fields (a task's `personId`, a note's
 * `deal`) without casts.
 */
export type CalendarEvent =
    | (CalendarEventBase & { kind: 'task'; raw: Task })
    | (CalendarEventBase & { kind: 'activity'; raw: Activity })
    | (CalendarEventBase & { kind: 'deal'; raw: Deal })
    | (CalendarEventBase & { kind: 'note'; raw: Note });

/** Raw record arrays the calendar projects into {@link CalendarEvent}s. */
export interface CalendarData {
    tasks?: Task[];
    activities?: Activity[];
    deals?: Deal[];
    notes?: Note[];
    persons?: Contact[];
}

/**
 * Local calendar-day key (`YYYY-MM-DD`) for a Date, in the browser's timezone.
 * This is the same string shape as a date-only MySQL value, so it can be sent
 * straight back as a `dueDate`/`expectedCloseDate` without a UTC round-trip.
 */
export function dayKeyOf(d: Date): string {
    const y = d.getFullYear();
    const m = String(d.getMonth() + 1).padStart(2, '0');
    const day = String(d.getDate()).padStart(2, '0');
    return `${y}-${m}-${day}`;
}

/** Local calendar-day key for a millisecond timestamp. */
export function dayKeyFromMs(ms: number): string {
    return dayKeyOf(new Date(ms));
}

export function startOfDay(d: Date): Date {
    return new Date(d.getFullYear(), d.getMonth(), d.getDate());
}

export function startOfMonth(d: Date): Date {
    return new Date(d.getFullYear(), d.getMonth(), 1);
}

/** First day of the week containing `d`, at local midnight. `weekStartsOn` 0 = Sunday. */
export function startOfWeek(d: Date, weekStartsOn = 0): Date {
    const s = startOfDay(d);
    const diff = (s.getDay() - weekStartsOn + 7) % 7;
    s.setDate(s.getDate() - diff);
    return s;
}

/** First cell of the 6-row month grid: the week start on or before the 1st. */
export function startOfGrid(monthStart: Date, weekStartsOn = 0): Date {
    return startOfWeek(monthStart, weekStartsOn);
}

export function addDays(d: Date, n: number): Date {
    const x = new Date(d);
    x.setDate(x.getDate() + n);
    return x;
}

export function addMonths(d: Date, n: number): Date {
    return new Date(d.getFullYear(), d.getMonth() + n, 1);
}

export function sameDay(a: Date, b: Date): boolean {
    return (
        a.getFullYear() === b.getFullYear() &&
        a.getMonth() === b.getMonth() &&
        a.getDate() === b.getDate()
    );
}

/** Minutes elapsed since local midnight — used to place timed events on the hour timeline. */
export function minutesSinceMidnight(ms: number): number {
    const d = new Date(ms);
    return d.getHours() * 60 + d.getMinutes();
}

/** The day cells of a month grid (4–6 week rows as the month requires), starting at {@link startOfGrid}. */
export function monthGridCells(monthStart: Date, weekStartsOn = 0): Date[] {
    const start = startOfGrid(monthStart, weekStartsOn);
    const firstOfMonth = new Date(monthStart.getFullYear(), monthStart.getMonth(), 1);
    const leading = (firstOfMonth.getDay() - weekStartsOn + 7) % 7;
    const daysInMonth = new Date(monthStart.getFullYear(), monthStart.getMonth() + 1, 0).getDate();
    const weeks = Math.ceil((leading + daysInMonth) / 7);
    return Array.from({ length: weeks * 7 }, (_, i) => addDays(start, i));
}

/** The 7 days of the week containing `anchor`. */
export function weekDays(anchor: Date, weekStartsOn = 0): Date[] {
    const start = startOfWeek(anchor, weekStartsOn);
    return Array.from({ length: 7 }, (_, i) => addDays(start, i));
}

/**
 * Orders events within a single day: all-day records first, then by time,
 * then by kind importance, then title. Stable and locale-aware on the title.
 */
export function compareEvents(a: CalendarEvent, b: CalendarEvent): number {
    if (a.allDay !== b.allDay) return a.allDay ? -1 : 1;
    if (a.startMs !== b.startMs) return a.startMs - b.startMs;
    if (KIND_PRIORITY[a.kind] !== KIND_PRIORITY[b.kind]) {
        return KIND_PRIORITY[a.kind] - KIND_PRIORITY[b.kind];
    }
    return a.title.localeCompare(b.title);
}

/**
 * Projects raw records into calendar events. Records with no usable date are
 * dropped. Note labels are prefixed with the linked contact's name when present.
 */
export function buildEvents(data: CalendarData): CalendarEvent[] {
    const events: CalendarEvent[] = [];
    const personById = new Map<number, Contact>();
    for (const p of data.persons ?? []) personById.set(p.id, p);

    for (const task of data.tasks ?? []) {
        const ms = parseMysqlDateTime(task.dueDate);
        if (Number.isNaN(ms)) continue;
        events.push({
            id: `task-${task.id}`,
            kind: 'task',
            entityId: task.id,
            title: noteContentToPlainText(task.description),
            startMs: ms,
            allDay: true,
            dayKey: dayKeyFromMs(ms),
            href: '/activity/tasks',
            draggable: true,
            raw: task,
        });
    }

    for (const activity of data.activities ?? []) {
        const ms = parseMysqlDateTime(activity.timestamp);
        if (Number.isNaN(ms)) continue;
        events.push({
            id: `activity-${activity.id}`,
            kind: 'activity',
            entityId: activity.id,
            title: activity.subject || activity.type,
            startMs: ms,
            allDay: false,
            dayKey: dayKeyFromMs(ms),
            href: '/activity/all',
            draggable: false,
            raw: activity,
        });
    }

    for (const deal of data.deals ?? []) {
        const ms = parseMysqlDateTime(deal.expectedCloseDate);
        if (Number.isNaN(ms)) continue;
        events.push({
            id: `deal-${deal.id}`,
            kind: 'deal',
            entityId: deal.id,
            title: deal.name,
            startMs: ms,
            allDay: true,
            dayKey: dayKeyFromMs(ms),
            href: `/records/deals/${deal.id}`,
            draggable: !isDealClosed(deal),
            raw: deal,
        });
    }

    for (const note of data.notes ?? []) {
        const ms = parseMysqlDateTime(note.createdAt);
        if (Number.isNaN(ms)) continue;
        const contact = personById.get(note.person ?? -1);
        const plain = noteContentToPlainText(note.content);
        const preview = plain.length > 60 ? `${plain.slice(0, 60)}…` : plain;
        events.push({
            id: `note-${note.id}`,
            kind: 'note',
            entityId: note.id,
            title: contact ? `${contact.name}: ${preview}` : preview,
            startMs: ms,
            allDay: false,
            dayKey: dayKeyFromMs(ms),
            href: `/activity/notes?id=${note.id}`,
            draggable: false,
            raw: note,
        });
    }

    return events;
}

/** Groups events by local day key, each bucket ordered by {@link compareEvents}. */
export function groupByDay(events: CalendarEvent[]): Map<string, CalendarEvent[]> {
    const map = new Map<string, CalendarEvent[]>();
    for (const e of events) {
        const arr = map.get(e.dayKey);
        if (arr) arr.push(e);
        else map.set(e.dayKey, [e]);
    }
    for (const arr of map.values()) arr.sort(compareEvents);
    return map;
}

/** The distinct kinds present on a day, in importance order — drives the month dot cluster. */
export function kindsForDay(events: CalendarEvent[]): CalendarEventKind[] {
    const present = new Set<CalendarEventKind>();
    for (const e of events) present.add(e.kind);
    return [...present].sort((a, b) => KIND_PRIORITY[a] - KIND_PRIORITY[b]);
}

/** A timed event placed on the Day/Week hour grid, with its lane and the lane count of its overlap cluster. */
export interface TimedPlacement {
    event: CalendarEvent;
    startMin: number;
    endMin: number;
    lane: number;
    laneCount: number;
}

/**
 * Lays out a day's timed events on the hour timeline. Point-in-time records get a
 * nominal block height so simultaneous events split into side-by-side lanes: greedy
 * interval-graph coloring, with `laneCount` set per connected overlap cluster so each
 * event knows how wide it can be. All-day events are ignored.
 */
export function layoutTimedEvents(events: CalendarEvent[], blockMinutes = 50): TimedPlacement[] {
    const placements: TimedPlacement[] = events
        .filter((e) => !e.allDay)
        .sort((a, b) => a.startMs - b.startMs)
        .map((event) => {
            const startMin = minutesSinceMidnight(event.startMs);
            return { event, startMin, endMin: Math.min(startMin + blockMinutes, 24 * 60), lane: 0, laneCount: 1 };
        });

    let clusterStart = 0;
    let clusterEnd = -1;
    let laneEnds: number[] = [];

    const closeCluster = (endIndex: number) => {
        const count = Math.max(1, laneEnds.length);
        for (let i = clusterStart; i < endIndex; i++) placements[i].laneCount = count;
        laneEnds = [];
    };

    for (let i = 0; i < placements.length; i++) {
        const p = placements[i];
        if (p.startMin >= clusterEnd) {
            closeCluster(i);
            clusterStart = i;
            clusterEnd = p.endMin;
        }
        let lane = laneEnds.findIndex((end) => end <= p.startMin);
        if (lane === -1) {
            lane = laneEnds.length;
            laneEnds.push(p.endMin);
        } else {
            laneEnds[lane] = p.endMin;
        }
        p.lane = lane;
        clusterEnd = Math.max(clusterEnd, p.endMin);
    }
    closeCluster(placements.length);

    return placements;
}
