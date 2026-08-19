import { readFileSync } from 'node:fs';
import path from 'node:path';
import { describe, expect, it } from 'vitest';

import { linkedIds } from '@/app/components/calendar/eventLinks';
import type { CalendarEvent } from '@/app/lib/calendar';
import type { Activity, Note, Task } from '@/app/lib/types';

const SHELL = 'app/components/calendar/CalendarShell.tsx';
const PEEK = 'app/components/calendar/EventPeekPopover.tsx';
const DETAIL = 'app/components/calendar/EventDetailSheet.tsx';
const POPOVER = 'components/ui/popover.tsx';

function source(relativePath: string): string {
    return readFileSync(path.resolve(process.cwd(), relativePath), 'utf8');
}

function taskEvent(): CalendarEvent {
    return {
        kind: 'task',
        id: 'task:1',
        entityId: 1,
        title: 'Send the proposal',
        startMs: 0,
        allDay: false,
        dayKey: '2026-03-01',
        href: '/activity/tasks',
        draggable: true,
        raw: { personId: 5, dealId: 9 } as Task,
    };
}

describe('a calendar event inspects in an anchored popover that expands into the drawer', () => {
    it('lets the popover point at the clicked entry rather than its own trigger', () => {
        const popover = source(POPOVER);

        expect(popover).toContain('"side" | "align" | "sideOffset" | "anchor"');
        expect(popover).toContain('anchor={anchor}');
        expect(source(PEEK)).toContain('anchor={anchor}');
    });

    it('routes a click to the popover on a fine pointer and to the sheet on a coarse one', () => {
        const shell = source(SHELL);

        expect(shell).toContain('const onOpenEvent = (event: CalendarEvent, anchor: HTMLElement | null) => {');
        expect(shell).toContain('if (coarse || !anchor) {');
        expect(shell).toContain('setPeek({ eventId: event.id, anchor });');
    });

    it('expands the same event, so the drawer is never a second selection', () => {
        const shell = source(SHELL);
        const expand = shell.slice(shell.indexOf('const expandPeek'), shell.indexOf('};', shell.indexOf('const expandPeek')));

        expect(expand).toContain('setOpenEventId(peek.eventId)');
        expect(expand).toContain('setPeek(null)');
    });

    it('opens the expanded view as a right drawer on desktop and a bottom sheet on a narrow screen', () => {
        const detail = source(DETAIL);

        expect(detail).toContain("swipeDirection={isNarrow ? 'down' : 'right'}");
        expect(detail).toContain('md:max-w-md');
    });

    it('reads the same links for both surfaces, whatever kind the entry is', () => {
        expect(linkedIds(taskEvent())).toEqual({ personId: 5, dealId: 9 });
        expect(linkedIds({
            ...taskEvent(),
            kind: 'note',
            raw: { person: 3, deal: null } as Note,
        })).toEqual({ personId: 3, dealId: null });
        expect(linkedIds({
            ...taskEvent(),
            kind: 'activity',
            raw: { personId: null, dealId: 7 } as unknown as Activity,
        })).toEqual({ personId: null, dealId: 7 });
    });
});
