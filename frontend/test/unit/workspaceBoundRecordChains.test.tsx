/** @vitest-environment jsdom */
import { createHash, webcrypto } from 'node:crypto';
import { act, type ComponentProps, type ReactNode } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import CompanyCreateContainer from '@/app/components/actions/create/CompanyCreateContainer';
import ContactCreateContainer from '@/app/components/actions/create/ContactCreateContainer';
import DealCreateContainer from '@/app/components/actions/create/DealCreateContainer';
import CalendarNewDealContainer from '@/app/components/calendar/CalendarNewDealContainer';
import { RecordActivityComposer, RecordTaskComposer } from '@/app/components/records/RecordComposers';
import ContactsBrowser from '@/app/components/records/contacts/ContactsBrowser';
import ContactsGrid from '@/app/components/records/companies/ContactsGrid';
import CompanyActionsMenu from '@/app/components/records/companies/CompanyActionsMenu';
import ContactCard from '@/app/components/records/contacts/ContactCard';
import EditContactSheet from '@/app/components/records/contacts/EditContactSheet';
import EditCompanySheet from '@/app/components/records/companies/EditCompanySheet';
import EditDealSheet from '@/app/components/records/deals/EditDealSheet';
import DealsBrowser from '@/app/components/records/deals/DealsBrowser';
import EditPipelineSheet from '@/app/components/records/pipelines/EditPipelineSheet';
import PipelinesBrowser from '@/app/components/records/pipelines/PipelinesBrowser';
import type TaskDialog from '@/app/components/activity/tasks/TaskDialog';
import type ActivityDialog from '@/app/components/activity/activities/ActivityDialog';
import type NewContactDialog from '@/app/components/records/contacts/NewContactDialog';
import type NewCompanyDialog from '@/app/components/records/companies/NewCompanyDialog';
import type NewDealDialog from '@/app/components/records/deals/NewDealDialog';
import type NewPipelineDialog from '@/app/components/records/pipelines/NewPipelineDialog';
import type QuickEditSheet from '@/app/components/records/contacts/QuickEditSheet';
import type QuickEditCompanySheet from '@/app/components/records/companies/QuickEditCompanySheet';
import type QuickEditDealSheet from '@/app/components/records/deals/QuickEditDealSheet';
import type QuickEditPipelineSheet from '@/app/components/records/pipelines/QuickEditPipelineSheet';
import { TooltipProvider } from '@/components/ui/tooltip';
import { bindRenderedWorkspace, releaseRenderedWorkspace, workspacePinnedRequest } from '@/app/lib/renderedWorkspace';
import type { BusinessCardImportDraft } from '@/app/lib/types';
import { company, contact, deal, image, ORIGIN, pipeline, shell, stage, stubBackend, switchWorkspace } from './helpers/workspaceChainBackend';

const captured = vi.hoisted<{
    task: ComponentProps<typeof TaskDialog> | null;
    activity: ComponentProps<typeof ActivityDialog> | null;
    contact: ComponentProps<typeof NewContactDialog> | null;
    company: ComponentProps<typeof NewCompanyDialog> | null;
    deal: ComponentProps<typeof NewDealDialog> | null;
    pipeline: ComponentProps<typeof NewPipelineDialog> | null;
    contactSheet: ComponentProps<typeof QuickEditSheet> | null;
    companySheet: ComponentProps<typeof QuickEditCompanySheet> | null;
    dealSheet: ComponentProps<typeof QuickEditDealSheet> | null;
    pipelineSheet: ComponentProps<typeof QuickEditPipelineSheet> | null;
}>(() => ({
    task: null,
    activity: null,
    contact: null,
    company: null,
    deal: null,
    pipeline: null,
    contactSheet: null,
    companySheet: null,
    dealSheet: null,
    pipelineSheet: null,
}));
const browser = vi.hoisted(() => ({
    noop: () => undefined,
    selectedIds: new Set([41]),
    filterState: {},
    items: [],
    search: new URLSearchParams(),
}));
const translate = vi.hoisted(() => Object.assign((key: string) => key, { has: () => false }));
vi.mock('next/navigation', () => ({
    useRouter: () => ({ refresh: browser.noop, push: browser.noop, replace: browser.noop }),
    usePathname: () => '/records/pipelines', useSearchParams: () => browser.search,
}));
vi.mock('next-intl', () => ({ useTranslations: () => translate, useLocale: () => 'en' }));
vi.mock('motion/react', () => ({ useReducedMotion: () => true }));
vi.mock('@/app/lib/toast', () => ({ toastError: vi.fn(), toastInfo: vi.fn(), toastSuccess: vi.fn(), toastWarn: vi.fn(), toastDismiss: vi.fn() }));
vi.mock('@/app/hooks/useWorkspace', () => ({ useWorkspace: () => ({ activeWorkspaceId: 9, workspaces: [] }) }));
vi.mock('@/app/hooks/usePermissions', () => ({ usePermission: () => true }));
vi.mock('@/app/hooks/useActions', () => ({
    useActions: () => ({ actions: [], pendingIds: new Set(), context: { user: { id: 7 } }, run: browser.noop }),
    useActionSelection: browser.noop,
}));
vi.mock('@/app/hooks/useRecordsBrowser', () => ({
    useRecordsBrowser: (options: { items?: unknown[] }) => ({
        displayMode: 'grid', effectiveDisplayMode: 'grid', setDisplayMode: browser.noop,
        filterState: browser.filterState, setFilterState: browser.noop,
        selectedIds: browser.selectedIds, setSelectedIds: browser.noop,
        deleteDialogOpen: false, setDeleteDialogOpen: browser.noop,
        query: '', setQuery: browser.noop, filteredItems: options.items ?? [], selectedItems: options.items ?? [],
    }),
}));
vi.mock('@/app/hooks/useServerRecords', () => ({
    useServerRecords: () => ({
        items: browser.items, total: 0, loading: false, page: 1, size: 25, query: '',
        sortKey: null, sortDirection: 'asc', revision: 0,
        setPage: browser.noop, setSize: browser.noop, setQuery: browser.noop, applyQuery: browser.noop,
        onSortChange: browser.noop, applySort: browser.noop, reload: browser.noop, patchItem: browser.noop,
    }),
}));
vi.mock('@/app/components/records/useRecordPeekController', () => ({ useRecordPeekController: () => ({ activeId: null, drawer: null }) }));
vi.mock('@/app/components/records/CustomFieldColumns', () => ({ useCustomFieldColumns: () => ({ columns: [], addColumnSlot: null }) }));
vi.mock('@/app/components/PageShell', () => ({ PageShell: ({ children }: { children?: ReactNode }) => <>{children}</> }));
vi.mock('@/app/components/motion/Rise', () => ({ default: () => null }));
vi.mock('@/components/ui/segmented-control', () => ({ SegmentedControl: () => null }));
vi.mock('@/components/ui/dialog-status-cover', async (importOriginal) => ({ ...await importOriginal<typeof import('@/components/ui/dialog-status-cover')>(), DialogStatusCover: () => null }));
vi.mock('@/app/components/records/ArchiveRecordDialog', () => ({ default: () => null }));
vi.mock('@/app/components/records/BulkTagDialog', () => ({ default: () => null }));
vi.mock('@/app/components/records/BulkAssignOwnerDialog', () => ({ default: () => null }));
vi.mock('@/app/components/records/contacts/NewContactDialog', () => ({ default: (props: ComponentProps<typeof NewContactDialog>) => { captured.contact = props; return null; } }));
vi.mock('@/app/components/records/companies/NewCompanyDialog', () => ({ default: (props: ComponentProps<typeof NewCompanyDialog>) => { captured.company = props; return null; } }));
vi.mock('@/app/components/records/deals/NewDealDialog', async (importOriginal) => ({ ...await importOriginal<typeof import('@/app/components/records/deals/NewDealDialog')>(), default: (props: ComponentProps<typeof NewDealDialog>) => { captured.deal = props; return null; } }));
vi.mock('@/app/components/records/pipelines/NewPipelineDialog', () => ({ default: (props: ComponentProps<typeof NewPipelineDialog>) => { captured.pipeline = props; return null; } }));
vi.mock('@/app/components/activity/tasks/TaskDialog', () => ({ default: (props: ComponentProps<typeof TaskDialog>) => { captured.task = props; return null; } }));
vi.mock('@/app/components/activity/activities/ActivityDialog', () => ({ default: (props: ComponentProps<typeof ActivityDialog>) => { captured.activity = props; return null; } }));
vi.mock('@/app/components/records/contacts/QuickEditSheet', () => ({ default: (props: ComponentProps<typeof QuickEditSheet>) => { captured.contactSheet = props; return <>{props.customFieldsSlot}</>; } }));
vi.mock('@/app/components/records/companies/QuickEditCompanySheet', () => ({ default: (props: ComponentProps<typeof QuickEditCompanySheet>) => { captured.companySheet = props; return <>{props.customFieldsSlot}</>; } }));
vi.mock('@/app/components/records/deals/QuickEditDealSheet', () => ({ default: (props: ComponentProps<typeof QuickEditDealSheet>) => { captured.dealSheet = props; return <>{props.customFieldsSlot}</>; } }));
vi.mock('@/app/components/records/pipelines/QuickEditPipelineSheet', () => ({ default: (props: ComponentProps<typeof QuickEditPipelineSheet>) => { captured.pipelineSheet = props; return null; } }));

let root: Root;
function get<K extends keyof typeof captured>(key: K): NonNullable<typeof captured[K]> {
    const value = captured[key];
    if (value === null) throw new Error('Missing component: ' + key);
    return value;
}
async function render(child: ReactNode) {
    await act(async () => root.render(<TooltipProvider>{child}</TooltipProvider>));
    await act(async () => { await new Promise((done) => setTimeout(done, 30)); });
}
async function holdAndSwitch(backend: ReturnType<typeof stubBackend>, submit: () => unknown, heldPath: string) {
    let pending: unknown;
    await act(async () => {
        pending = submit();
        await backend.started.promise;
    });
    switchWorkspace();
    await act(async () => { backend.held.resolve(backend.response(heldPath)); await pending; });
}
function expectWrites(backend: ReturnType<typeof stubBackend>, paths: string[]) {
    expect(backend.requests.map(({ path, workspace }) => ({ path, workspace }))).toEqual(
        paths.map((path) => ({ path, workspace: String(ORIGIN) })),
    );
}
beforeEach(() => {
    vi.stubGlobal('IS_REACT_ACT_ENVIRONMENT', true);
    vi.stubGlobal('crypto', webcrypto);
    vi.stubGlobal('matchMedia', (query: string) => ({ media: query, matches: false, addEventListener: browser.noop, removeEventListener: browser.noop }));
    const container = document.createElement('div');
    document.body.appendChild(container);
    root = createRoot(container);
    for (const key of Object.keys(captured)) Reflect.set(captured, key, null);
    bindRenderedWorkspace(shell, ORIGIN);
    document.cookie = 'connex_workspace=' + ORIGIN + '; path=/';
});
afterEach(async () => {
    await act(async () => root.unmount());
    releaseRenderedWorkspace(shell);
    document.body.innerHTML = '';
    window.localStorage.clear();
    window.sessionStorage.clear();
    document.cookie = 'connex_workspace=; path=/; max-age=0';
    vi.unstubAllGlobals();
    vi.clearAllMocks();
});

describe('record composers preserve one origin', () => {
    it.each(['task', 'activity'] as const)('%s creation and deal-person linking', async (kind) => {
        const path = kind === 'task' ? '/api/tasks' : '/api/activities';
        const backend = stubBackend(path);
        const props = { anchor: { kind: 'person' as const, person: contact, companyId: null }, currentUserId: 7, open: true };
        await render(kind === 'task' ? <RecordTaskComposer {...props} /> : <RecordActivityComposer {...props} />);
        await holdAndSwitch(backend, () => {
            if (kind === 'task') return get('task').createRequest?.({ description: 'Origin task', assignedToId: 7, personId: 41, dealId: 41 });
            return get('activity').createRequest?.({ type: 'Call', subject: 'Origin activity', createdById: 7, personId: 41, dealId: 41 });
        }, path);
        expectWrites(backend, [path, '/api/deals/41/people/41']);
    });
});

describe('contact creation and pictures preserve one origin', () => {
    const surfaces = ['browser', 'company grid', 'company menu', 'quick create'] as const;
    it.each(surfaces.flatMap((surface) => ['manual', 'import'].flatMap((mode) => ['first', 'picture'].map((hold) => ({ surface, mode, hold })))))('$surface $mode while $hold is pending', async ({ surface, mode, hold }) => {
        const first = mode === 'import' ? '/api/business-cards/import' : '/api/persons';
        const paths = [first, '/api/persons/41/profile-picture'];
        const heldPath = hold === 'first' ? first : paths[1];
        const backend = stubBackend(heldPath);
        await render(surface === 'browser' ? <ContactsBrowser savedViews={[]} defaultView={null} />
            : surface === 'company grid' ? <ContactsGrid contacts={[]} company={company} allTags={[]} />
                : surface === 'company menu' ? <CompanyActionsMenu company={company} />
                    : <ContactCreateContainer open onOpenChange={browser.noop} />);
        await act(async () => {
            get('contact').setNewContactPayload(contact);
            get('contact').setImageFile(image);
        });
        const card: BusinessCardImportDraft | undefined = mode === 'import' ? {
            requestId: '3246a805-449d-4d5e-bc76-c5272aad7f8e', image, contact,
            recoveryContext: { workspaceId: String(ORIGIN), scope: createHash('sha256').update(String(ORIGIN) + '\u0000' + 7).digest('hex') },
            personAction: { type: 'create' }, companyAction: { type: 'none' },
        } : undefined;
        await holdAndSwitch(backend, () => get('contact').createNewContact(card), heldPath);
        expectWrites(backend, paths);
    });
    it.each(surfaces)('%s accepts the preflight origin after the selection changed', async (surface) => {
        const backend = stubBackend();
        await render(surface === 'browser' ? <ContactsBrowser savedViews={[]} defaultView={null} />
            : surface === 'company grid' ? <ContactsGrid contacts={[]} company={company} allTags={[]} />
                : surface === 'company menu' ? <CompanyActionsMenu company={company} />
                    : <ContactCreateContainer open onOpenChange={browser.noop} />);
        await act(async () => { get('contact').setNewContactPayload(contact); get('contact').setImageFile(image); });
        const controller = new AbortController();
        const origin = workspacePinnedRequest(ORIGIN, { signal: controller.signal });
        switchWorkspace();
        await act(async () => { await get('contact').createNewContact(undefined, null, origin); });
        expectWrites(backend, ['/api/persons', '/api/persons/41/profile-picture']);
        expect(backend.requests.every(({ init }) => init.signal === controller.signal)).toBe(true);
    });
});

describe('record edit chains preserve one origin', () => {
    it.each(['card', 'contact', 'company', 'deal'].flatMap((surface) => (surface === 'card' ? ['first', 'picture'] : surface === 'deal' ? ['first', 'custom fields'] : ['first', 'custom fields', 'picture']).map((hold) => ({ surface, hold }))))('$surface while $hold is pending', async ({ surface, hold }) => {
        const entityPath = surface === 'company' ? '/api/companies/41' : surface === 'deal' ? '/api/deals/41' : '/api/persons/41';
        const fieldPath = entityPath + '/custom-fields';
        const picturePath = entityPath + (surface === 'company' ? '/logo' : '/profile-picture');
        const heldPath = hold === 'first' ? entityPath : hold === 'custom fields' ? fieldPath : picturePath;
        const backend = stubBackend(heldPath);
        await render(surface === 'card' ? <ContactCard id={41} workspaceId={ORIGIN} name={contact.name} />
            : surface === 'contact' ? <EditContactSheet contact={contact} open onOpenChange={browser.noop} />
                : surface === 'company' ? <EditCompanySheet company={company} open onOpenChange={browser.noop} />
                    : <EditDealSheet deal={deal} open onOpenChange={browser.noop} pipelines={[pipeline]} stagesByPipeline={{ 41: [stage] }} />);
        await act(async () => {
            if (surface === 'company') { get('companySheet').updateDraft(41, { name: 'Updated company' }); get('companySheet').updateLogoFile?.(41, image); }
            else if (surface === 'deal') get('dealSheet').updateDraft(41, { name: 'Updated deal' });
            else { get('contactSheet').updateDraft(41, { name: 'Updated contact' }); get('contactSheet').updateImageFile?.(41, image); }
        });
        if (surface !== 'card') {
            const input = document.getElementById('cfe-1');
            if (!(input instanceof HTMLInputElement)) throw new Error('Custom field did not load');
            await act(async () => {
                Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')?.set?.call(input, 'West');
                input.dispatchEvent(new Event('input', { bubbles: true }));
            });
        }
        await holdAndSwitch(backend, () => surface === 'company' ? get('companySheet').saveEdits() : surface === 'deal' ? get('dealSheet').saveEdits() : get('contactSheet').saveEdits(), heldPath);
        expectWrites(backend, [entityPath, ...surface === 'card' ? [] : [fieldPath], ...surface === 'deal' ? [] : [picturePath]]);
    });
});

describe('company quick creation preserves one origin', () => {
    it.each(['/api/companies', '/api/companies/41/logo', '/api/persons'])('while %s is pending', async (heldPath) => {
        const backend = stubBackend(heldPath);
        await render(<CompanyCreateContainer open onOpenChange={browser.noop} />);
        await act(async () => { get('company').setPayload({ name: company.name }); get('company').setLogoFile(image); });
        const pending = [1, 2].map((id) => ({ tempId: String(id), name: 'Staged ' + id, email: '', phone: '', title: '', imageFile: image, duplicateReviewSignature: null, duplicateReviewToken: null }));
        await holdAndSwitch(backend, () => get('company').createNewCompany(null, pending), heldPath);
        expectWrites(backend, ['/api/companies', '/api/companies/41/logo', '/api/persons', '/api/persons/41/profile-picture', '/api/persons', '/api/persons/41/profile-picture']);
    });
});

describe('pipeline chains preserve one origin', () => {
    it.each(['sheet', 'browser'] as const)('%s updates name then stages', async (surface) => {
        const backend = stubBackend('/api/pipelines/41');
        await render(surface === 'sheet' ? <EditPipelineSheet pipeline={pipeline} stages={[stage]} open onOpenChange={browser.noop} /> : <PipelinesBrowser pipelines={[pipeline]} />);
        await act(async () => { get('pipelineSheet').updateDraft(41, { name: 'Updated pipeline', stages: [{ ...stage, name: 'Updated stage' }] }); });
        await holdAndSwitch(backend, () => get('pipelineSheet').saveEdits(), '/api/pipelines/41');
        expectWrites(backend, ['/api/pipelines/41', '/api/pipelines/41/stages']);
    });
    it('creates a pipeline then stages', async () => {
        const backend = stubBackend('/api/pipelines');
        await render(<PipelinesBrowser pipelines={[pipeline]} />);
        await act(async () => get('pipeline').setPayload({ name: 'New pipeline', stages: [{ name: 'First' }, { name: 'Second' }] }));
        await holdAndSwitch(backend, () => get('pipeline').createNewPipeline(), '/api/pipelines');
        expectWrites(backend, ['/api/pipelines', '/api/pipelines/41/stages', '/api/pipelines/41/stages']);
    });
});

describe('deal creation callbacks accept the preflight origin', () => {
    it.each(['browser', 'company menu', 'quick create', 'calendar'])('%s', async (surface) => {
        const backend = stubBackend();
        await render(surface === 'browser' ? <DealsBrowser deals={[]} total={0} metrics={{ byCurrency: [], totalCount: 0 }} serverFacets={{ status: [], stages: [], pipelines: [], companies: [], currencies: [], risk: [], owners: [] }} savedViews={[]} defaultView={null} timezone="UTC" currentUserId={7} /> : surface === 'company menu' ? <CompanyActionsMenu company={company} /> : surface === 'quick create' ? <DealCreateContainer open onOpenChange={browser.noop} /> : <CalendarNewDealContainer open onOpenChange={browser.noop} defaultExpectedCloseDate="2026-10-20" />);
        const origin = workspacePinnedRequest(ORIGIN);
        switchWorkspace();
        await act(async () => { await get('deal').createNewDeal('review-token', origin); });
        expectWrites(backend, ['/api/deals']);
    });
});
