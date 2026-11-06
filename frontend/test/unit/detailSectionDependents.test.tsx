/** @vitest-environment jsdom */

import { cleanup, fireEvent, render, screen, within } from '@testing-library/react';
import { NextIntlClientProvider } from 'next-intl';
import type { ComponentProps } from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { DetailSectionsProvider } from '@/app/hooks/useDetailSections';
import DetailSectionState from '@/app/components/records/DetailSectionState';
import TimelineContent from '@/app/components/me/TimelineContent';
import type TimelineRow from '@/app/components/me/TimelineRow';
import { NowProvider } from '@/app/hooks/useNow';
import type { Activity, Contact } from '@/app/lib/types';
import enRecords from '@/messages/en/records.json';
import enCompanies from '@/messages/en/companies.json';
import enMe from '@/messages/en/me.json';
import { DealEngagementSection } from '@/app/components/records/DetailCollections';

const people = vi.hoisted(() => vi.fn());
const activities = vi.hoisted(() => vi.fn());
vi.mock('@/app/lib/api', async (original) => ({ ...await original<typeof import('@/app/lib/api')>(), getCompanyPeople: people, getActivitiesForDeal: activities }));
vi.mock('@/app/lib/renderedWorkspace', async (original) => ({ ...await original<typeof import('@/app/lib/renderedWorkspace')>(), renderedWorkspaceId: () => 1 }));
vi.mock('@/app/hooks/useWorkspace', () => ({ useWorkspace: () => ({ activeWorkspaceId: 1, switching: false }) }));
vi.mock('@/app/hooks/useApiErrorToast', () => ({ useApiErrorToast: () => vi.fn() }));
vi.mock('@/app/components/me/TimelineDeepLinkFallback', () => ({ default: () => null }));
vi.mock('@/app/components/me/TimelineRow', () => ({ default: ({ entry, persons, personsUnavailable }: ComponentProps<typeof TimelineRow>) => <li>
    <p>{entry.kind === 'activity' ? entry.activity.subject : entry.kind}</p>
    <span>{persons.map((person) => person.name).join(', ')}</span>
    <button disabled={personsUnavailable}>Edit activity choices</button>
</li> }));
vi.mock('@/app/components/records/companies/CompanyCard', () => ({ EngagementSparkline: ({ data }: { data: { count: number }[] }) => <p>Touches: {data.reduce((sum, point) => sum + point.count, 0)}</p> }));
vi.mock('@/app/components/records/deals/DealActivityBreakdown', () => ({ default: ({ activities }: { activities: Activity[] }) => <p>Activities: {activities.length}</p> }));

const activity: Activity = { id: 4, subject: 'A successful company meeting', type: 'meeting', createdById: 1, timestamp: '2026-11-06T00:00:00Z' };
const contact: Contact = { id: 8, name: 'Recovered contact', email: '', phone: '', title: '', imageUrl: '', createdAt: '', updatedAt: '' };
const messages = { ...enRecords, ...enCompanies, ...enMe };
afterEach(() => { cleanup(); vi.clearAllMocks(); });

describe('detail dependency boundaries', () => {
    it('keeps loaded company history visible and restores only its failed editor choices', async () => {
        people.mockResolvedValueOnce([contact]);
        render(<NextIntlClientProvider locale="en" messages={messages} timeZone="UTC">
            <DetailSectionsProvider recordId={1} workspaceId={1} initial={{ companyPeople: { kind: 'failed', status: 503 }, companyDeals: { kind: 'loaded', data: [] } }}>
                <DetailSectionState title="Contacts" sectionKeys={['companyPeople']}><p>Contacts loaded</p></DetailSectionState>
                <TimelineContent detailKind="company" originWorkspaceId={1} tasks={[]} activities={[activity]} notes={[]} persons={[]} deals={[]} />
            </DetailSectionsProvider>
        </NextIntlClientProvider>);
        expect(screen.getByText(activity.subject)).toBeDefined();
        expect(screen.getByRole('button', { name: 'Edit activity choices' }).hasAttribute('disabled')).toBe(true);
        fireEvent.click(screen.getByRole('button', { name: enRecords.RecordListState.retry }));
        await screen.findByText(contact.name);
        expect(screen.getByText(activity.subject)).toBeDefined();
        expect(screen.getByRole('button', { name: 'Edit activity choices' }).hasAttribute('disabled')).toBe(false);
    });

    it('updates the timeline and engagement derived from a recovered activity collection', async () => {
        activities.mockResolvedValueOnce([activity]);
        render(<NextIntlClientProvider locale="en" messages={{ ...messages, DealsPage: { timeline: 'Timeline', engagement: 'Engagement' } }} timeZone="UTC">
            <NowProvider value={Date.parse('2026-11-06T12:00:00Z')}>
                <DetailSectionsProvider recordId={1} workspaceId={1} initial={{ dealActivities: { kind: 'failed', status: 503 }, dealNotes: { kind: 'loaded', data: [] }, dealTasks: { kind: 'loaded', data: [] } }}>
                    <DealEngagementSection />
                    <TimelineContent detailKind="deal" originWorkspaceId={1} tasks={[]} activities={[]} notes={[]} />
                </DetailSectionsProvider>
            </NowProvider>
        </NextIntlClientProvider>);
        expect(screen.queryByText('Touches: 0')).toBeNull();
        fireEvent.click(within(screen.getByRole('region', { name: 'Engagement' })).getByRole('button', { name: enRecords.RecordListState.retry }));
        await screen.findByText(activity.subject);
        expect(screen.getByText('Touches: 1')).toBeDefined();
        expect(screen.getByText('Activities: 1')).toBeDefined();
        expect(activities).toHaveBeenCalledOnce();
    });
});
