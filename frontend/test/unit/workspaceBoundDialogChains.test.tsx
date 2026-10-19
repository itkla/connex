/** @vitest-environment jsdom */
import { webcrypto } from 'node:crypto';
import { act, type ReactNode } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { ActivityDialogForm } from '@/app/components/activity/activities/ActivityDialog';
import NewCompanyDialog, { NewCompanyForm } from '@/app/components/records/companies/NewCompanyDialog';
import { NewContactForm } from '@/app/components/records/contacts/NewContactDialog';
import { NewDealForm } from '@/app/components/records/deals/NewDealDialog';
import DealTeamDialog from '@/app/components/records/deals/DealTeamDialog';
import ApprovalPolicyDialog from '@/app/components/records/approval-policies/ApprovalPolicyDialog';
import CaptureReviewDialog from '@/app/components/account/connected-capture/CaptureReviewDialog';
import { TooltipProvider } from '@/components/ui/tooltip';
import { createCompany, createContact, createDeal, decideCaptureReview, preflightPersonDuplicates, uploadCompanyLogo, uploadContactPicture } from '@/app/lib/api';
import { bindRenderedWorkspace, releaseRenderedWorkspace } from '@/app/lib/renderedWorkspace';
import type { ApprovalPolicy, CaptureReviewItem, User } from '@/app/lib/types';
import { company, contact, deal, image, ORIGIN, pipeline, shell, stage, stubBackend, switchWorkspace } from './helpers/workspaceChainBackend';

const noop = vi.hoisted(() => () => undefined);
const translate = vi.hoisted(() => Object.assign((key: string) => key, { has: () => false }));
vi.mock('next/navigation', () => ({ useRouter: () => ({ refresh: noop, push: noop, replace: noop }) }));
vi.mock('next-intl', () => ({ useTranslations: () => translate, useLocale: () => 'en' }));
vi.mock('motion/react', () => ({ AnimatePresence: ({ children }: { children?: ReactNode }) => <>{children}</>, motion: { div: ({ children }: { children?: ReactNode }) => <div>{children}</div>, li: ({ children }: { children?: ReactNode }) => <li>{children}</li> }, useReducedMotion: () => true }));
vi.mock('@/app/hooks/useWorkspace', () => ({ useWorkspace: () => ({ activeWorkspaceId: 9 }) }));
vi.mock('@/app/hooks/usePermissions', () => ({ usePermission: () => true }));
vi.mock('@/app/lib/toast', () => ({ toastError: vi.fn(), toastInfo: vi.fn(), toastSuccess: vi.fn(), toastWarn: vi.fn(), toastDismiss: vi.fn() }));
vi.mock('@/components/ui/dialog-status-cover', async (importOriginal) => ({ ...await importOriginal<typeof import('@/components/ui/dialog-status-cover')>(), DialogStatusCover: () => null }));
vi.mock('@/app/components/activity/notes/MentionEditor', () => ({ default: ({ id, value, onChange }: { id: string; value: string; onChange: (value: string) => void }) => <textarea id={id} value={value} onChange={(event) => onChange(event.target.value)} /> }));
vi.mock('@/components/ui/dialog', () => {
    const Pass = ({ children }: { children?: ReactNode }) => <div>{children}</div>;
    return { Dialog: Pass, DialogContent: Pass, DialogHeader: Pass, DialogTitle: Pass, DialogDescription: Pass, DialogFooter: Pass, DialogClose: Pass };
});
vi.mock('@/components/ui/responsive-dialog', () => {
    const Pass = ({ children }: { children?: ReactNode }) => <div>{children}</div>;
    return { ResponsiveDialog: Pass, ResponsiveDialogContent: Pass, ResponsiveDialogHeader: Pass, ResponsiveDialogTitle: Pass, ResponsiveDialogDescription: Pass, ResponsiveDialogFooter: Pass, ResponsiveDialogClose: Pass };
});
vi.mock('@/components/ui/combobox', () => {
    const Pass = ({ children }: { children?: ReactNode }) => <div>{children}</div>;
    return {
        Combobox: ({ children, items, onValueChange }: { children?: ReactNode; items?: User[]; onValueChange?: (value: User | null) => void }) => <div>{children}{items?.map((item) => <button key={item.id} type="button" onClick={() => onValueChange?.(item)}>{item.displayName}</button>)}</div>,
        ComboboxChip: Pass, ComboboxChips: Pass, ComboboxChipsInput: () => null, ComboboxContent: Pass,
        ComboboxEmpty: Pass, ComboboxInput: () => null, ComboboxItem: () => null, ComboboxList: Pass,
        ComboboxValue: () => null, useComboboxAnchor: () => null,
    };
});
let root: Root;
let locks: PropertyDescriptor | undefined;
async function render(child: ReactNode): Promise<void> { await act(async () => root.render(<TooltipProvider>{child}</TooltipProvider>)); }
function button(label: string): HTMLButtonElement {
    const found = [...document.querySelectorAll('button')].find((item) => item.textContent?.trim() === label);
    if (!found) throw new Error('Missing button ' + label);
    return found;
}
async function ready(): Promise<void> {
    await act(async () => { await new Promise((done) => setTimeout(done, 450)); });
    const submit = document.querySelector<HTMLButtonElement>('button[type="submit"]');
    expect(submit?.disabled).toBe(false);
}
async function submitAndSwitch(backend: ReturnType<typeof stubBackend>, heldPath: string): Promise<void> {
    await act(async () => { document.querySelector('form')?.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true })); await backend.started.promise; });
    switchWorkspace();
    await act(async () => backend.held.resolve(backend.response(heldPath)));
}
function expectWrites(backend: ReturnType<typeof stubBackend>, paths: string[]): void {
    expect(backend.requests.map(({ path, workspace }) => ({ path, workspace }))).toEqual(paths.map((path) => ({ path, workspace: String(ORIGIN) })));
}
beforeEach(() => {
    vi.stubGlobal('IS_REACT_ACT_ENVIRONMENT', true);
    vi.stubGlobal('crypto', webcrypto);
    vi.stubGlobal('matchMedia', (query: string) => ({ media: query, matches: false, addEventListener: noop, removeEventListener: noop }));
    vi.stubGlobal('URL', class extends URL { static createObjectURL() { return 'blob:picture'; } static revokeObjectURL() {} });
    locks = Object.getOwnPropertyDescriptor(navigator, 'locks');
    Object.defineProperty(navigator, 'locks', { configurable: true, value: { request: async (_name: string, _options: LockOptions, operation: () => unknown) => operation() } });
    const container = document.createElement('div');
    document.body.appendChild(container);
    root = createRoot(container);
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
    if (locks) Object.defineProperty(navigator, 'locks', locks); else Reflect.deleteProperty(navigator, 'locks');
    vi.unstubAllGlobals();
    vi.clearAllMocks();
});

describe('dialog chain origins', () => {
    it('creates an activity and its follow-up in the same workspace, retaining caller signal', async () => {
        const backend = stubBackend('/api/activities');
        const controller = new AbortController();
        await render(<ActivityDialogForm persons={[]} deals={[]} currentUserId={7} defaultSubject="Origin call" requestInit={{ signal: controller.signal }} onSubmittingChange={noop} onCancel={noop} onClose={noop} />);
        await act(async () => button('followUpAdd').click());
        await submitAndSwitch(backend, '/api/activities');
        expectWrites(backend, ['/api/activities', '/api/tasks']);
        expect(backend.requests.every(({ init }) => init.signal === controller.signal)).toBe(true);
    });

    it.each(['dialog', 'form'].flatMap((surface) => ['first', 'middle'].map((hold) => ({ surface, hold }))))('company $surface while $hold is pending', async ({ surface, hold }) => {
        const backend = stubBackend();
        const heldPath = hold === 'first' ? '/api/duplicate-preflight/companies' : surface === 'dialog' ? '/api/duplicate-preflight/persons' : '/api/companies';
        const payload = { name: company.name };
        const pending = [{ tempId: 'first', name: contact.name, email: '', phone: '', title: '', imageFile: null, duplicateReviewSignature: null, duplicateReviewToken: null }];
        const create = async (_token: string | null, _contacts: unknown, init?: RequestInit) => {
            const saved = await createCompany(payload, init);
            await uploadCompanyLogo(saved.id, image, init);
        };
        await render(surface === 'dialog' ? <NewCompanyDialog open onOpenChange={noop} payload={payload} setPayload={noop} logoFile={null} setLogoFile={noop} isCreating={false} createNewCompany={create} pendingContacts={pending} addPendingContact={noop} updatePendingContact={noop} removePendingContact={noop} /> : <NewCompanyForm active onCancel={noop} payload={payload} setPayload={noop} logoFile={null} setLogoFile={noop} isCreating={false} createNewCompany={create} />);
        await ready();
        backend.arm(heldPath);
        await submitAndSwitch(backend, heldPath);
        expectWrites(backend, ['/api/duplicate-preflight/companies', ...surface === 'dialog' ? ['/api/duplicate-preflight/persons'] : [], '/api/companies', '/api/companies/41/logo']);
    });

    it.each(['/api/duplicate-preflight/persons', '/api/persons'])('contact preflight handoff while %s is pending', async (heldPath) => {
        const backend = stubBackend();
        const controller = new AbortController();
        await render(<NewContactForm active onCancel={noop} newContactPayload={contact} setNewContactPayload={noop} imageFile={image} setImageFile={noop} isCreating={false} requestInit={{ signal: controller.signal }} createNewContact={async (_card, _token, init) => {
            const saved = await createContact(contact, init);
            await uploadContactPicture(saved.id, image, init);
            return { avatarUploaded: true, avatarUploadFailed: false, finalize: noop };
        }} />);
        await ready();
        backend.arm(heldPath);
        await submitAndSwitch(backend, heldPath);
        expectWrites(backend, ['/api/duplicate-preflight/persons', '/api/persons', '/api/persons/41/profile-picture']);
        expect(backend.requests.every(({ init }) => init.signal === controller.signal)).toBe(true);
    });

    it.each(['/api/duplicate-preflight/deals', '/api/deals'])('deal preflight hands its origin to creation while %s is pending', async (heldPath) => {
        const backend = stubBackend();
        await render(<NewDealForm active onCancel={noop} payload={{ ...deal, pipeline: 41, stage: 42 }} setPayload={noop} pipelines={[pipeline]} stagesByPipeline={{ 41: [stage] }} isCreating={false} createNewDeal={async (token, init) => { await createDeal({ name: deal.name, value: 100, actualValue: 0, currency: 'USD', pipeline: 41, stage: 42, duplicateReviewToken: token }, init); }} />);
        await ready();
        backend.arm(heldPath);
        await submitAndSwitch(backend, heldPath);
        expectWrites(backend, ['/api/duplicate-preflight/deals', '/api/deals']);
    });

    it('updates the deal owner before collaborators in the origin', async () => {
        const backend = stubBackend('/api/deals/41/owner');
        const teammate: User = { id: 8, username: 'teammate', displayName: 'Teammate', email: 'team@example.test', createdAt: '', updatedAt: '', timezone: 'UTC', locale: 'en' };
        await render(<DealTeamDialog open onOpenChange={noop} dealId={41} initialOwnerId={7} initialCollaborators={[teammate]} />);
        await act(async () => button('Teammate').click());
        await act(async () => { button('saveTeam').click(); await backend.started.promise; });
        switchWorkspace();
        await act(async () => backend.held.resolve(backend.response('/api/deals/41/owner')));
        expectWrites(backend, ['/api/deals/41/owner', '/api/deals/41/collaborators']);
    });

    it.each(['/api/approval-policies/41/impact', '/api/approval-policies/41'])('keeps approval preview and policy save in the origin while %s is pending', async (heldPath) => {
        const backend = stubBackend(heldPath);
        const policy: ApprovalPolicy = { id: 41, name: 'Origin policy', active: true, mode: 'sequential', separationOfDuties: 'strict', steps: [], createdAt: '', updatedAt: '' };
        await render(<ApprovalPolicyDialog open onOpenChange={noop} policy={policy} onSaved={noop} />);
        await act(async () => { button('save').click(); await backend.started.promise; });
        switchWorkspace();
        await act(async () => backend.held.resolve(backend.response(heldPath)));
        expectWrites(backend, ['/api/approval-policies/41/impact', '/api/approval-policies/41']);
    });

    it.each(['/api/duplicate-preflight/persons', '/api/account/connections/google/reviews/41'])('keeps capture preflight and the review decision in the origin while %s is pending', async (heldPath) => {
        const backend = stubBackend(heldPath);
        const review: CaptureReviewItem = { id: 41, version: 1, interactionId: 42, interactionVersion: 1, provider: 'google', stream: 'calendar', interactionType: 'meeting', subject: 'Origin meeting', occurredAt: '2026-10-19T00:00:00Z', participantRole: 'attendee', displayName: contact.name, email: contact.email, matchState: 'unmatched', heldReason: 'no_match', candidates: [], allowedActions: ['create'] };
        await render(<CaptureReviewDialog review={review} busy={false} canCreatePeople onBack={noop} onApprove={async () => true} onPreflight={preflightPersonDuplicates} onDecide={async (decision, init) => { await decideCaptureReview('google', review.id, decision, init); return true; }} />);
        await act(async () => { button('create').click(); await backend.started.promise; });
        switchWorkspace();
        await act(async () => backend.held.resolve(backend.response(heldPath)));
        expectWrites(backend, ['/api/duplicate-preflight/persons', '/api/account/connections/google/reviews/41']);
    });
});
