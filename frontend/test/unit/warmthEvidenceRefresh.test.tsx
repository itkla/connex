/** @vitest-environment jsdom */

import { cleanup, fireEvent, render, screen, within } from '@testing-library/react';
import { NextIntlClientProvider } from 'next-intl';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import WarmthEvidenceChip from '@/app/components/records/WarmthEvidenceChip';
import { NowProvider } from '@/app/hooks/useNow';
import { TooltipProvider } from '@/components/ui/tooltip';
import type { RelationshipEvidence } from '@/app/lib/types';
import enRecords from '@/messages/en/records.json';
import jaRecords from '@/messages/ja/records.json';
import enCommon from '@/messages/en/common.json';
import jaCommon from '@/messages/ja/common.json';

const en = { ...enRecords, ...enCommon };
const ja = { ...jaRecords, ...jaCommon };

const now = Date.parse('2026-11-07T12:00:00Z');
const cool: RelationshipEvidence = {
    subjectType: 'person', subjectId: 42, asOf: '2026-11-07T12:00:00Z',
    attributionRule: 'direct_person_touches', contributors: [],
    temperature: { id: 42, score: 22, band: 'cool', trend: 'steady', touchCount: 3, lastTouchAt: '2026-08-21T12:00:00Z' },
    totals: { contributorCount: 3, returnedCount: 0, omittedCount: 3, totalDecayedContribution: 1, returnedDecayedContribution: 0, omittedDecayedContribution: 1, sourceCounts: { activities: 3, notes: 0, tasks: 0 } },
    coverage: { limitedEvidence: false, minimumContributorsForConfidence: 3, callerPrivateNotesExcluded: 0, privateNoteCountScope: 'current_caller_only' },
};
const warm: RelationshipEvidence = {
    ...cool, temperature: { ...cool.temperature, score: 42, band: 'warm', trend: 'rising', touchCount: 4, lastTouchAt: cool.asOf },
    contributors: [{ sourceType: 'task', sourceId: 9, interactionType: 'task', occurredAt: cool.asOf, baseWeight: 0.3, decayedContribution: 0.3 }],
    totals: { ...cool.totals, contributorCount: 4, returnedCount: 1, sourceCounts: { ...cool.totals.sourceCounts, tasks: 1 } },
};

function chip(evidence: RelationshipEvidence, locale: 'en' | 'ja') {
    return <NextIntlClientProvider locale={locale} messages={locale === 'en' ? en : ja} timeZone="UTC">
        <NowProvider value={now}><TooltipProvider><WarmthEvidenceChip evidence={evidence} /></TooltipProvider></NowProvider>
    </NextIntlClientProvider>;
}

beforeEach(() => {
    vi.stubGlobal('matchMedia', (media: string) => ({ media, matches: false, addEventListener() {}, removeEventListener() {} }));
});
afterEach(() => { cleanup(); vi.unstubAllGlobals(); });

describe('warmth evidence prop refresh', () => {
    it.each(['en', 'ja'] as const)('updates the real header trigger and open evidence together (%s)', async (locale) => {
        const copy = locale === 'en' ? en : ja;
        const view = render(chip(cool, locale));
        const trigger = screen.getByRole('button');
        expect(trigger.textContent).toBe(copy.Temperature.cool);
        fireEvent.click(trigger);
        const panel = await screen.findByRole('region', { name: copy.RelationshipEvidence.title });
        expect(within(panel).getByText(copy.Temperature.cool)).toBeTruthy();
        view.rerender(chip(warm, locale));
        expect(trigger.textContent).toBe(copy.Temperature.warm);
        expect(trigger.getAttribute('aria-label')).toBe(copy.RelationshipEvidence.openEvidenceNamed.replace('{band}', copy.Temperature.warm));
        expect(within(panel).getByText(copy.Temperature.warm)).toBeTruthy();
        expect(panel.querySelector('a[href="/activity/tasks/9"]')).not.toBeNull();
        view.rerender(chip(cool, locale));
        expect(trigger.textContent).toBe(copy.Temperature.cool);
        expect(within(panel).getByText(copy.Temperature.cool)).toBeTruthy();
    });
});
