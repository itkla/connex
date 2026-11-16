import { Children, isValidElement, type ReactNode } from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it, vi } from 'vitest';
import ContactsBrowser from '@/app/components/records/contacts/ContactsBrowser';
import CompaniesBrowser from '@/app/components/records/companies/CompaniesBrowser';
import PipelinesBrowser from '@/app/components/records/pipelines/PipelinesBrowser';
import DealsBrowser from '@/app/components/records/deals/DealsBrowser';
import RecordsRenderView from '@/app/components/records/RecordsRenderView';
import SummaryTile from '@/app/components/SummaryTile';
import type { ColumnDef } from '@/app/components/records/types';
import type { Contact } from '@/app/lib/types';

const display = vi.hoisted(() => ({ locale: 'en', timeZone: 'Asia/Tokyo', noop: () => undefined }));
const translate = vi.hoisted(() => Object.assign((key: string) => key, { has: () => false }));

vi.mock('next/navigation', () => ({
    useRouter: () => ({ push: display.noop, replace: display.noop, refresh: display.noop }),
    usePathname: () => '/records/contacts', useSearchParams: () => new URLSearchParams(),
}));
vi.mock('next-intl', () => ({
    useTranslations: () => translate, useLocale: () => display.locale, useTimeZone: () => display.timeZone,
}));
vi.mock('motion/react', () => ({ useReducedMotion: () => true }));
vi.mock('@/app/hooks/useWorkspace', () => ({ useWorkspace: () => ({ activeWorkspaceId: 9, workspaces: [] }) }));
vi.mock('@/app/hooks/usePermissions', () => ({ usePermission: () => true }));
vi.mock('@/app/hooks/useActions', () => ({
    useActions: () => ({ actions: [], pendingIds: new Set(), context: { user: { id: 7 } }, run: display.noop }),
    useActionSelection: () => undefined,
}));
vi.mock('@/app/hooks/useRecordsBrowser', () => ({
    useRecordsBrowser: () => ({
        displayMode: 'table', effectiveDisplayMode: 'table', setDisplayMode: display.noop,
        filterState: {}, setFilterState: display.noop, selectedIds: new Set(), setSelectedIds: display.noop,
        deleteDialogOpen: false, setDeleteDialogOpen: display.noop, query: '', setQuery: display.noop,
        filteredItems: [], selectedItems: [],
    }),
}));
vi.mock('@/app/hooks/useServerRecords', () => ({
    useServerRecords: () => ({
        items: [], total: 0, loading: false, page: 1, size: 25, query: '', sortKey: null,
        sortDirection: 'asc', revision: 0, setPage: display.noop, setSize: display.noop,
        setQuery: display.noop, applyQuery: display.noop, onSortChange: display.noop,
        applySort: display.noop, reload: display.noop, patchItem: display.noop, replaceItem: display.noop,
    }),
}));
vi.mock('@/app/components/records/CustomFieldColumns', () => ({
    useCustomFieldColumns: () => ({ columns: [], addColumnSlot: null }),
}));
vi.mock('@/app/components/records/useRecordPeekController', () => ({
    useRecordPeekController: () => ({ activeId: null, drawer: null }),
}));
vi.mock('@/app/components/PageShell', () => ({
    PageShell: ({ children }: { children?: ReactNode }) => <>{formattingChildren(children)}</>,
}));

const record: Contact = {
    id: 1, name: 'Formatting record', imageUrl: '', email: '', phone: '', title: '',
    createdAt: '2026-11-04 23:30:00', updatedAt: '2026-11-05 01:45:00',
};

function formattingChildren(children: ReactNode): ReactNode {
    return Children.map(children, (child) => {
        if (!isValidElement<{ children?: ReactNode; columns?: ColumnDef<Contact>[]; label?: string; value?: ReactNode }>(child)) return null;
        if (child.type === RecordsRenderView) {
            const columns = child.props.columns?.filter((column) => column.key === 'createdAt' || column.key === 'updatedAt');
            return <table><tbody><tr>{columns?.map((column) => <td key={column.key}>{column.render?.(record)}</td>)}</tr></tbody></table>;
        }
        if (child.type === SummaryTile && child.props.label === 'openDeals') return <p>{child.props.value}</p>;
        return formattingChildren(child.props.children);
    });
}

describe.each([
    { locale: 'en', timeZone: 'Asia/Tokyo', created: 'Nov 5, 2026, 8:30 AM', updated: 'Nov 5, 2026, 10:45 AM' },
    { locale: 'en', timeZone: 'UTC', created: 'Nov 4, 2026, 11:30 PM', updated: 'Nov 5, 2026, 1:45 AM' },
    { locale: 'ja', timeZone: 'Asia/Tokyo', created: '2026/11/05 8:30', updated: '2026/11/05 10:45' },
    { locale: 'ja', timeZone: 'UTC', created: '2026/11/04 23:30', updated: '2026/11/05 1:45' },
])('record lists in $locale / $timeZone', ({ locale, timeZone, created, updated }) => {
    it.each(['contacts', 'companies', 'pipelines'])('formats both %s date columns through the profile preferences', (entity) => {
        display.locale = locale;
        display.timeZone = timeZone;
        const tree = entity === 'contacts' ? <ContactsBrowser savedViews={[]} defaultView={null} />
            : entity === 'companies' ? <CompaniesBrowser savedViews={[]} defaultView={null} />
                : <PipelinesBrowser pipelines={[]} />;
        const html = renderToStaticMarkup(tree);
        expect(html).toContain(`<td>${created}</td>`);
        expect(html).toContain(`<td>${updated}</td>`);
        expect(html).not.toMatch(/\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}/u);
    });

    it('groups the Deals open-count tile', () => {
        display.locale = locale;
        display.timeZone = timeZone;
        const html = renderToStaticMarkup(
            <DealsBrowser deals={[]} total={1234} metrics={{ totalCount: 1234, byCurrency: [{
                currency: 'USD', openCount: 1234, closedCount: 0, openValue: 5000,
                closedForecast: 0, closedRevenue: 0, wonCount: 0, lostCount: 0,
            }] }} serverFacets={{ status: [], stages: [], pipelines: [], companies: [], currencies: [], risk: [], owners: [] }}
                savedViews={[]} defaultView={null} timezone="UTC" currentUserId={7} />,
        );
        expect(html).toContain('<p>1,234</p>');
    });
});
