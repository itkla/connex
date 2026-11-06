import type { DragEndEvent, Over } from '@dnd-kit/core';
import { createTranslator } from 'next-intl';
import { describe, expect, it } from 'vitest';
import { kanbanAccessibility } from '@/app/components/kanban/kanbanAccessibility';
import dealsEn from '@/messages/en/deals.json';
import dealsJa from '@/messages/ja/deals.json';
import activityEn from '@/messages/en/activity.json';
import activityJa from '@/messages/ja/activity.json';

function event(over: Over | null): DragEndEvent {
    return {
        active: { id: 1, data: { current: {} }, rect: { current: { initial: null, translated: null } } },
        over, activatorEvent: new Event('keydown'), collisions: null, delta: { x: 0, y: 0 },
    };
}

function target(id: number | string, column?: string): Over {
    const rect = { top: 0, left: 0, right: 10, bottom: 10, width: 10, height: 10 };
    const data: Over['data'] = { current: column ? { sortable: { containerId: `col:${column}` } } : {} };
    return { id, data, rect, disabled: false };
}

for (const locale of ['en', 'ja'] as const) {
    for (const board of ['deals', 'tasks'] as const) {
        const catalog = board === 'deals' ? (locale === 'en' ? dealsEn.DealsKanban : dealsJa.DealsKanban) : (locale === 'en' ? activityEn.ActivityTasks : activityJa.ActivityTasks);
        const messages: Record<string, string> = {
            a11yInstructions: catalog.a11yInstructions, a11yLifted: catalog.a11yLifted, a11yOver: catalog.a11yOver,
            a11yDropped: catalog.a11yDropped, a11yCancelled: catalog.a11yCancelled,
        };
        const t = createTranslator({ locale, messages });
        describe(`${locale} ${board} announcements`, () => {
            const accessibility = kanbanAccessibility(t, () => 'Example', (column) => `Stage ${column}`);
            it('names the live destination when over the reparented active card', () => {
                const drag = event(target(1, 'destination'));
                expect(accessibility.announcements.onDragOver?.(drag)).toBe(t('a11yOver', { name: 'Example', column: 'Stage destination' }));
                expect(accessibility.announcements.onDragEnd(drag)).toBe(t('a11yDropped', { name: 'Example', column: 'Stage destination' }));
            });
            it('names a non-empty target container and an empty target column', () => {
                for (const over of [target(2, 'next'), target('col:next')]) {
                    expect(accessibility.announcements.onDragEnd(event(over))).toBe(t('a11yDropped', { name: 'Example', column: 'Stage next' }));
                }
            });
            it('announces cancellation outside the board without inventing a destination', () => {
                expect(accessibility.announcements.onDragEnd(event(null))).toBe(t('a11yCancelled', { name: 'Example' }));
            });
            it('allows deals to announce a pending confirmation rather than a saved move', () => {
                const pending = kanbanAccessibility(t, () => 'Example', (column) => column, (id, column) => `Review ${id} in ${column}`);
                expect(pending.announcements.onDragEnd(event(target(1, 'won')))).toBe('Review 1 in won');
            });
        });
    }
}
