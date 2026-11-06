import { Children, isValidElement, type ReactNode, type ComponentProps } from 'react';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import CompanyPage from '@/app/(app)/records/companies/[id]/page';
import ContactPage from '@/app/(app)/records/contacts/[id]/page';
import DealPage from '@/app/(app)/records/deals/[id]/page';
import CampaignPage from '@/app/(app)/marketing/campaigns/[id]/page';
import CampaignDetail from '@/app/components/marketing/campaigns/CampaignDetail';
import DetailSectionState from '@/app/components/records/DetailSectionState';
import { ApiError } from '@/app/lib/api';

const api = vi.hoisted(() => ({
    getCurrentUserResultFromCookie: vi.fn(), getMyWorkspacesFromCookie: vi.fn(),
    getCompanyById: vi.fn(), getCompanyPeople: vi.fn(), getCompanyDeals: vi.fn(),
    getCompanyTags: vi.fn(), getCompanyEngagement: vi.fn(), getCompanyTimeline: vi.fn(), getCompanyEvidence: vi.fn(),
    getContactById: vi.fn(), getContactEmployment: vi.fn(), getContactConnections: vi.fn(), getContactIntroPath: vi.fn(),
    getContactEvidence: vi.fn(), getContactLifecycle: vi.fn(), getContactLifecycleHistory: vi.fn(), getContactQualification: vi.fn(),
    getPersonMarketingStatus: vi.fn(), getPersonCampaignTouches: vi.fn(), getContactTemperatures: vi.fn(),
    getDealById: vi.fn(), getActivitiesForDeal: vi.fn(), getNotesForDeal: vi.fn(), getTasksForDeal: vi.fn(),
    getTagsForDeal: vi.fn(), getDealPeople: vi.fn(), getDealCollaborators: vi.fn(), getDealRisk: vi.fn(),
    getDealStageHistory: vi.fn(), getDealLineItems: vi.fn(), getDealDocuments: vi.fn(),
    getDealDocumentsFromCookie: vi.fn(), getDealLineItemsFromCookie: vi.fn(),
    getContacts: vi.fn(), getPipelineById: vi.fn(), getStagesByPipelineId: vi.fn(),
    getCampaign: vi.fn(), getCampaignAudienceFromCookie: vi.fn(), getCampaignSnapshots: vi.fn(), getCampaignMessages: vi.fn(),
    getCampaignSends: vi.fn(), getCampaignExports: vi.fn(), getCampaignEngagement: vi.fn(), getCapabilities: vi.fn(),
    getEffectivePermissionsResultFromCookie: vi.fn(), getEffectivePermissionsFromCookie: vi.fn(),
    getAttachmentsFromCookie: vi.fn(), getCommentThreads: vi.fn(), getEntityCustomFieldsFromCookie: vi.fn(),
    getContextNotifications: vi.fn(), getTags: vi.fn(), getUserReferences: vi.fn(), getRelationshipMapAvailability: vi.fn(),
}));
vi.mock('@/app/lib/api', async (original) => ({ ...await original<typeof import('@/app/lib/api')>(), ...api }));
vi.mock('next/headers', () => ({
    cookies: async () => ({ toString: () => 'session=test; connex_workspace=1', get: () => ({ value: '1' }) }),
    headers: async () => new Headers({ cookie: 'session=test; connex_workspace=1' }),
}));
vi.mock('next/navigation', () => ({
    redirect: () => { throw new Error('sign-in redirect'); },
    notFound: () => { throw new Error('not found'); },
}));
vi.mock('next-intl/server', () => ({
    getTranslations: async () => (key: string) => key,
    getTimeZone: async () => 'UTC', getLocale: async () => 'en',
}));

beforeEach(() => {
    for (const mock of Object.values(api)) mock.mockReset().mockResolvedValue([]);
    api.getCurrentUserResultFromCookie.mockResolvedValue({ ok: true, data: { id: 1, timezone: 'UTC' } });
    api.getMyWorkspacesFromCookie.mockResolvedValue({ activeWorkspaceId: 1, workspaces: [{ id: 1, timezone: 'UTC' }] });
    api.getCompanyById.mockResolvedValue({ id: 1, name: 'Company' });
    api.getCompanyEngagement.mockResolvedValue({ relatedUserIds: [], weeklyEngagement: [], pastRevenue: 0, projectedRevenue: 0 });
    api.getCompanyTimeline.mockResolvedValue({ activities: [], tasks: [], notes: [] });
    api.getContactById.mockResolvedValue({ id: 1, workspaceId: 1, name: 'Contact' });
    api.getContactIntroPath.mockResolvedValue({ reachable: false, directlyKnown: false, steps: [] });
    api.getDealById.mockResolvedValue({ id: 1, workspaceId: 1, name: 'Deal', value: 100, actualValue: 0, currency: 'JPY' });
    api.getDealLineItems.mockResolvedValue({ items: [], totals: { currency: 'JPY', grandTotal: 100 } });
    api.getDealLineItemsFromCookie.mockImplementation(api.getDealLineItems);
    api.getDealDocumentsFromCookie.mockImplementation(api.getDealDocuments);
    api.getContextNotifications.mockResolvedValue({ items: [], stateVersion: 0 });
    api.getCampaign.mockResolvedValue({ id: 1, name: 'Campaign' });
    api.getCampaignAudienceFromCookie.mockResolvedValue({ ok: true, data: undefined });
    api.getEffectivePermissionsResultFromCookie.mockResolvedValue({ ok: true, data: [] });
    api.getCapabilities.mockResolvedValue({ campaignDelivery: false });
    for (const mock of [api.getContactEvidence, api.getContactLifecycle, api.getPersonMarketingStatus, api.getCompanyEvidence, api.getDealRisk]) mock.mockResolvedValue(null);
});

function boundaries(node: ReactNode): ComponentProps<typeof DetailSectionState>[] {
    const found: ComponentProps<typeof DetailSectionState>[] = [];
    Children.forEach(node, (child) => {
        if (!isValidElement<{ children?: ReactNode }>(child)) return;
        if (child.type === DetailSectionState && isValidElement<ComponentProps<typeof DetailSectionState>>(child)) found.push(child.props);
        found.push(...boundaries(child.props.children));
    });
    return found;
}

const recordCases = [
    { name: 'company contacts', page: CompanyPage, mock: api.getCompanyPeople, title: 'contacts' },
    { name: 'company deals', page: CompanyPage, mock: api.getCompanyDeals, title: 'deals' },
    { name: 'contact employment', page: ContactPage, mock: api.getContactEmployment, title: 'employmentHistory' },
    { name: 'contact connections', page: ContactPage, mock: api.getContactConnections, title: 'connections' },
    { name: 'contact introduction path', page: ContactPage, mock: api.getContactIntroPath, title: 'connections' },
    { name: 'contact lifecycle history', page: ContactPage, mock: api.getContactLifecycleHistory, title: 'timeline' },
    { name: 'deal activities', page: DealPage, mock: api.getActivitiesForDeal, title: 'timeline' },
    { name: 'deal notes', page: DealPage, mock: api.getNotesForDeal, title: 'timeline' },
    { name: 'deal tasks', page: DealPage, mock: api.getTasksForDeal, title: 'openTasks' },
    { name: 'deal documents', page: DealPage, mock: api.getDealDocuments, title: 'title' },
    { name: 'deal line items', page: DealPage, mock: api.getDealLineItems, title: 'title' },
];

describe.each(recordCases)('$name section boundary', ({ page, mock, title }) => {
    it.each([403, 503])('exposes HTTP %s as failure and recovers to a successful empty load', async (status) => {
        mock.mockRejectedValueOnce(new ApiError('private', status, undefined, undefined, 'load-ref'));
        const failedPage = await page({ params: Promise.resolve({ id: 1 }) });
        if (mock === api.getContactLifecycleHistory) expect(JSON.stringify(failedPage)).not.toContain('noRecordedInteractions');
        const failed = boundaries(failedPage);
        expect(failed.find((section) => section.title === title && section.failure?.status === status)?.failure)
            .toEqual({ kind: 'failed', status, reference: 'load-ref' });
        const recovered = boundaries(await page({ params: Promise.resolve({ id: 1 }) }));
        expect(recovered.filter((section) => section.failure)).toEqual([]);
        expect(recovered.find((section) => section.title === title)).toBeDefined();
    });
});

describe.each([
    { name: 'messages', mock: api.getCampaignMessages, prop: 'deliveryFailure' },
    { name: 'sends', mock: api.getCampaignSends, prop: 'deliveryFailure' },
    { name: 'exports', mock: api.getCampaignExports, prop: 'exportsFailure' },
] as const)('campaign $name', ({ mock, prop }) => {
    it.each([403, 503])('passes HTTP %s failure to its tab boundary and recovers', async (status) => {
        mock.mockRejectedValueOnce(new ApiError('private', status));
        const failed = await CampaignPage({ params: Promise.resolve({ id: '1' }) });
        expect(failed.type).toBe(CampaignDetail);
        if (!isValidElement<ComponentProps<typeof CampaignDetail>>(failed)) throw new Error('Expected campaign detail');
        expect(failed.props[prop]).toMatchObject({ kind: 'failed', status });
        const recovered = await CampaignPage({ params: Promise.resolve({ id: '1' }) });
        if (!isValidElement<ComponentProps<typeof CampaignDetail>>(recovered)) throw new Error('Expected campaign detail');
        expect(recovered.props[prop]).toBeNull();
    });
});
