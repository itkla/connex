/** @vitest-environment jsdom */
import { webcrypto } from 'node:crypto';
import { useState, type ReactNode } from 'react';
import { act, cleanup, fireEvent, render, waitFor } from '@testing-library/react';
import { NextIntlClientProvider } from 'next-intl';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { AuthForm } from '@/app/components/AuthForm';
import { ActivityDialogForm } from '@/app/components/activity/activities/ActivityDialog';
import EditActivitySheet from '@/app/components/activity/activities/EditActivitySheet';
import { NewContactForm } from '@/app/components/records/contacts/NewContactDialog';
import NewCompanyDialog, { NewCompanyForm } from '@/app/components/records/companies/NewCompanyDialog';
import { TooltipProvider } from '@/components/ui/tooltip';
import { ApiError } from '@/app/lib/api';
import { bindRenderedWorkspace, releaseRenderedWorkspace } from '@/app/lib/renderedWorkspace';
import type { Activity, CreateCompanyPayload, CreateContactPayload, DuplicatePreflightResponse } from '@/app/lib/types';
import enAuth from '@/messages/en/auth.json';
import jaAuth from '@/messages/ja/auth.json';
import enActivity from '@/messages/en/activity.json';
import jaActivity from '@/messages/ja/activity.json';
import enContacts from '@/messages/en/contacts.json';
import jaContacts from '@/messages/ja/contacts.json';
import enCompanies from '@/messages/en/companies.json';
import jaCompanies from '@/messages/ja/companies.json';
import enCommon from '@/messages/en/common.json';
import jaCommon from '@/messages/ja/common.json';
import { duplicatePreflightResponseSignature } from '@/app/hooks/useDuplicatePreflight';
import { deferred, stubBackend } from './helpers/workspaceChainBackend';

const recordState: { file: File | null; response: DuplicatePreflightResponse | null } = vi.hoisted(() => ({ file: null, response: null }));
const api = vi.hoisted(() => ({ register: vi.fn(), login: vi.fn(), update: vi.fn(), review: vi.fn() }));
vi.mock('next/navigation', () => ({ useRouter: () => ({ push: vi.fn(), replace: vi.fn(), refresh: vi.fn() }) }));
vi.mock('@/app/hooks/useWorkspace', () => ({ useWorkspace: () => ({ activeWorkspaceId: 9, switching: false }) }));
vi.mock('@/app/hooks/usePasskeySupport', () => ({ usePasskeySupport: () => false }));
vi.mock('@/app/hooks/usePermissions', () => ({ usePermission: () => true }));
vi.mock('@/app/hooks/useApiErrorToast', () => ({ useApiErrorToast: () => vi.fn() }));
vi.mock('@/app/hooks/useRecordTargetSearch', () => ({ useContactTargetSearch: () => ({ contacts: [] }), useDealTargetSearch: () => ({ deals: [] }) }));
vi.mock('@/app/hooks/useDuplicatePreflight', async (importOriginal) => ({
    ...await importOriginal<typeof import('@/app/hooks/useDuplicatePreflight')>(),
    useDuplicatePreflight: (kind: string) => ({ status: kind === 'person' && recordState.response ? 'ready' : 'idle', blocked: false, response: kind === 'person' ? recordState.response : null, reviewNow: api.review }),
}));
vi.mock('@/app/components/records/contacts/useBusinessCardCapture', async (importOriginal) => {
    const actual = await importOriginal<typeof import('@/app/components/records/contacts/useBusinessCardCapture')>();
    return { useBusinessCardCapture: (props: Parameters<typeof actual.useBusinessCardCapture>[0]) => {
        const capture = actual.useBusinessCardCapture(props);
        return recordState.file ? { ...capture, file: recordState.file } : capture;
    } };
});
vi.mock('@/app/lib/api', async (importOriginal) => ({
    ...await importOriginal<typeof import('@/app/lib/api')>(), register: api.register, login: api.login, updateActivity: api.update,
}));
vi.mock('@/app/lib/toast', () => ({ toastSuccess: vi.fn(), toastError: vi.fn(), toastInfo: vi.fn(), toastWarn: vi.fn() }));
vi.mock('@/app/components/auth/AuthBrandPanel', () => ({ default: () => null }));
vi.mock('@/app/components/activity/notes/MentionEditor', () => ({ default: () => null }));
vi.mock('@/components/ui/dialog-status-cover', async (importOriginal) => ({ ...await importOriginal<typeof import('@/components/ui/dialog-status-cover')>(), DialogStatusCover: () => null }));
vi.mock('@/components/ui/drawer', () => {
    const Pass = ({ children }: { children?: ReactNode }) => <div>{children}</div>;
    return { Drawer: Pass, DrawerContent: Pass, DrawerHeader: Pass, DrawerTitle: Pass, DrawerDescription: Pass, DrawerFooter: Pass, DrawerClose: Pass };
});
vi.mock('@/components/ui/responsive-dialog', () => {
    const Pass = ({ children }: { children?: ReactNode }) => <div>{children}</div>;
    return { ResponsiveDialog: Pass, ResponsiveDialogContent: Pass, ResponsiveDialogHeader: Pass, ResponsiveDialogTitle: Pass, ResponsiveDialogDescription: Pass, ResponsiveDialogFooter: Pass, ResponsiveDialogClose: Pass };
});
const shell = {};
const activity: Activity = { id: 41, type: 'call', subject: 'Follow up', timestamp: '2026-01-01 10:00:00', createdById: 7 };
const noop = () => {};
beforeEach(() => {
    vi.resetAllMocks();
    recordState.file = null;
    recordState.response = null;
    vi.stubGlobal('crypto', webcrypto);
    Object.defineProperty(navigator, 'locks', { configurable: true, value: { request: async (_name: string, _options: LockOptions, operation: () => unknown) => operation() } });
    vi.stubGlobal('ResizeObserver', class { observe() {} unobserve() {} disconnect() {} });
    vi.stubGlobal('matchMedia', (media: string) => ({ media, matches: false, addEventListener() {}, removeEventListener() {} }));
    vi.stubGlobal('requestAnimationFrame', (callback: FrameRequestCallback) => setTimeout(() => callback(0), 0));
    vi.stubGlobal('cancelAnimationFrame', clearTimeout);
    HTMLElement.prototype.scrollIntoView = vi.fn();
    bindRenderedWorkspace(shell, 9);
    document.cookie = 'connex_workspace=9; path=/';
    stubBackend();
});
afterEach(() => { cleanup(); releaseRenderedWorkspace(shell); Reflect.deleteProperty(navigator, 'locks'); window.localStorage.clear(); window.sessionStorage.clear(); document.cookie = 'connex_workspace=; path=/; max-age=0'; vi.unstubAllGlobals(); });
function submit(container: HTMLElement) {
    const form = container.querySelector('form');
    if (!form) throw new Error('Missing form');
    expect(form.noValidate).toBe(true);
    const button = container.querySelector<HTMLButtonElement>('button[type="submit"]');
    if (!button) throw new Error('Missing submit');
    button.focus();
    expect(document.activeElement).toBe(button);
    fireEvent.submit(form);
}
function input(id: string): HTMLInputElement {
    const element = document.getElementById(id);
    if (!(element instanceof HTMLInputElement)) throw new Error(`Missing input ${id}`);
    return element;
}
function fillRegistration() {
    for (const [key, value] of Object.entries({ username: 'form_probe', email: 'form@example.test', displayName: 'Form probe', password: 'Aa12345#' })) fireEvent.change(input(`register-${key}`), { target: { value } });
}
function codedError(field: string, code: string) {
    const error = new ApiError('raw backend prose', 400, 'VALIDATION_FAILED', { [field]: 'size must be between 0 and 255' });
    error.fieldErrorCodes = { [field]: code };
    return error;
}
function RecordForm({ kind, create }: { kind: 'contact' | 'company' | 'company-dialog'; create: () => Promise<void> }) {
    const [contact, setContact] = useState<CreateContactPayload>({ name: '   ', email: '', phone: '', title: '' });
    const [company, setCompany] = useState<CreateCompanyPayload>({ name: '   ' });
    if (kind === 'contact') return <NewContactForm active onCancel={noop} newContactPayload={contact} setNewContactPayload={setContact} imageFile={null} setImageFile={noop} isCreating={false} createNewContact={create} />;
    const props = { payload: company, setPayload: setCompany, logoFile: null, setLogoFile: noop, isCreating: false, createNewCompany: create };
    return kind === 'company' ? <NewCompanyForm {...props} active onCancel={noop} /> : <NewCompanyDialog {...props} open onOpenChange={noop} pendingContacts={[]} addPendingContact={noop} updatePendingContact={noop} removePendingContact={noop} />;
}
for (const { locale, auth, activities, contacts, companies, common } of [
    { locale: 'en', auth: enAuth, activities: enActivity, contacts: enContacts, companies: enCompanies, common: enCommon },
    { locale: 'ja', auth: jaAuth, activities: jaActivity, contacts: jaContacts, companies: jaCompanies, common: jaCommon },
]) {
    function surface(child: ReactNode) {
        return render(<NextIntlClientProvider locale={locale} timeZone="UTC" messages={{ ...auth, ...activities, ...contacts, ...companies, ...common }}><TooltipProvider>{child}</TooltipProvider></NextIntlClientProvider>);
    }
    describe(`localized validation in ${locale}`, () => {
        it('A11Y-23/A11Y-22 checks required fields before sending and focuses username with one summary', async () => {
            const view = surface(<AuthForm mode="register" redirectUrl={null} />);
            submit(view.container);
            await waitFor(() => expect(document.activeElement).toBe(input('register-username')));
            expect(api.register).not.toHaveBeenCalled();
            expect(view.container.querySelectorAll('[aria-invalid="true"]')).toHaveLength(4);
            expect(view.container.querySelectorAll('[role="alert"]')).toHaveLength(0);
            expect(view.container.querySelector('[aria-live="polite"]')?.textContent).toBe(auth.AuthForm.formHasErrors);
        });
        it.each([
            ['username', 'ab', 'usernameLength'], ['username', 'a'.repeat(65), 'usernameLength'], ['username', 'bad name', 'usernameFormat'],
            ['email', 'a'.repeat(256), 'emailLength'], ['email', 'bad-email', 'emailFormat'], ['displayName', 'a'.repeat(129), 'nameLength'],
            ['password', 'Aa1#' + 'a'.repeat(69), 'password'],
        ])('A11Y-23 checks %s before sending', async (field, value, key) => {
            const view = surface(<AuthForm mode="register" redirectUrl={null} />);
            fillRegistration(); fireEvent.change(input(`register-${field}`), { target: { value } }); submit(view.container);
            await waitFor(() => expect(document.activeElement).toBe(input(`register-${field}`)));
            expect(api.register).not.toHaveBeenCalled();
            expect(document.getElementById(`register-${field}-error`)?.textContent).toBe(key === 'password' ? auth.PasswordPolicy.length : Reflect.get(auth.AuthForm, key));
        });
        it('A11Y-22 focuses server errors in form order and guards repeat requests while submit remains focusable', async () => {
            const pending = deferred<ApiError>(); api.register.mockImplementationOnce(async () => { throw await pending.promise; });
            const view = surface(<AuthForm mode="register" redirectUrl={null} />); fillRegistration();
            const button = view.container.querySelector<HTMLButtonElement>('button[type="submit"]');
            if (!button) throw new Error('Missing submit');
            button.focus(); submit(view.container);
            expect(button.disabled).toBe(false); expect(button.getAttribute('aria-disabled')).toBe('true'); expect(button.getAttribute('aria-busy')).toBe('true'); expect(document.activeElement).toBe(button);
            submit(view.container); expect(api.register).toHaveBeenCalledOnce();
            const failure = codedError('email', 'Email');
            failure.fieldErrors = { password: 'raw password', email: 'raw email', username: 'raw username' };
            failure.fieldErrorCodes = { username: 'Pattern', email: 'Email' };
            await act(async () => pending.resolve(failure));
            await waitFor(() => expect(document.activeElement).toBe(input('register-username')));
            expect(document.getElementById('register-username-error')?.textContent).toBe(auth.AuthForm.usernameFormat);
            expect(view.container.textContent).not.toContain('raw username'); expect(button.disabled).toBe(false); expect(button.textContent).toBe(auth.AuthRegister.submitLabel);
        });
        it('A11Y-22 leaves login available after field errors', async () => {
            api.login.mockRejectedValueOnce(codedError('password', 'NotBlank'));
            const view = surface(<AuthForm mode="login" redirectUrl={null} />); submit(view.container);
            await waitFor(() => expect(document.activeElement).toBe(input('login-password')));
            const button = view.container.querySelector<HTMLButtonElement>('button[type="submit"]');
            expect(button?.disabled).toBe(false); expect(button?.textContent).toBe(auth.AuthLogin.submitLabel);
            submit(view.container); await waitFor(() => expect(api.login).toHaveBeenCalledTimes(2));
        });
        it.each(['create', 'edit'] as const)('ACT-28 validates %s subject inline and focuses after rendering the error', async (mode) => {
            const create = vi.fn();
            const view = surface(mode === 'create' ? <ActivityDialogForm persons={[]} deals={[]} currentUserId={7} createRequest={create} onSubmittingChange={noop} onCancel={noop} onClose={noop} /> : <EditActivitySheet activity={activity} open onOpenChange={noop} persons={[]} deals={[]} originWorkspaceId={9} />);
            fireEvent.change(input('activity-subject'), { target: { value: '   ' } }); submit(view.container);
            await waitFor(() => expect(document.activeElement).toBe(input('activity-subject')));
            expect(document.getElementById('activity-subject-error')?.textContent).toBe(mode === 'create' ? activities.ActivityCreateDialog.toastSubjectRequired : activities.ActivityEditActivitySheet.subjectRequired);
            expect(input('activity-subject').getAttribute('aria-describedby')).toContain('activity-subject-error');
            expect(view.container.querySelector('label[for="activity-subject"]')?.textContent).toContain('*'); expect(create).not.toHaveBeenCalled(); expect(api.update).not.toHaveBeenCalled();
        });
        it.each(['create', 'edit'] as const)('ACT-29 caps %s subject, counts near the limit and localizes server size errors', async (mode) => {
            const create = vi.fn().mockRejectedValueOnce(codedError('subject', 'Size')); if (mode === 'edit') api.update.mockRejectedValueOnce(codedError('subject', 'Size'));
            const view = surface(mode === 'create' ? <ActivityDialogForm persons={[]} deals={[]} currentUserId={7} createRequest={create} onSubmittingChange={noop} onCancel={noop} onClose={noop} /> : <EditActivitySheet activity={activity} open onOpenChange={noop} persons={[]} deals={[]} originWorkspaceId={9} />);
            const subject = input('activity-subject'); expect(subject.maxLength).toBe(255); expect(document.getElementById('activity-subject-count')).toBeNull();
            fireEvent.change(subject, { target: { value: 'a'.repeat(230) } }); expect(document.getElementById('activity-subject-count')?.textContent).toContain('230/255'); submit(view.container);
            const copy = mode === 'create' ? activities.ActivityCreateDialog.subjectTooLong : activities.ActivityEditActivitySheet.subjectTooLong;
            await waitFor(() => expect(document.getElementById('activity-subject-error')?.textContent).toBe(copy)); await waitFor(() => expect(document.activeElement).toBe(subject)); expect(view.container.textContent).not.toContain('size must be');
        });
        it('REC-27 permits attaching a nameless card to an existing matched contact', async () => {
            recordState.file = new File(['image'], 'card.png', { type: 'image/png' });
            const response: DuplicatePreflightResponse = {
                recordType: 'person', truncated: false, reviewToken: 'review',
                candidates: [{ recordId: 41, recordType: 'person', name: 'Existing contact', ownedByActiveWorkspace: true,
                    strength: 'STRONG', matches: [{ kind: 'EMAIL', normalizedValue: 'existing@example.test', strength: 'STRONG' }] }],
            };
            recordState.response = response;
            api.review.mockResolvedValue({ allowed: true, response, duplicateReviewToken: 'review', reviewSignature: duplicatePreflightResponseSignature(response) });
            const view = surface(<RecordForm kind="contact" create={vi.fn()} />);
            await waitFor(() => expect(input('name').disabled).toBe(false));
            fireEvent.click(view.getByText(common.DuplicateWarning.useExisting));
            expect(input('name').disabled).toBe(true);
            submit(view.container);
            await waitFor(() => expect(api.review).toHaveBeenCalled());
            expect(document.getElementById('name-error')).toBeNull();
        });
        it.each(['contact', 'company', 'company-dialog'] as const)('REC-27 validates %s name before duplicate review and focuses it', async (kind) => {
            const create = vi.fn(); const view = surface(<RecordForm kind={kind} create={create} />);
            const field = input(kind === 'contact' ? 'name' : 'company-name');
            await waitFor(() => expect(field.disabled).toBe(false));
            expect(view.container.querySelector(`label[for="${field.id}"]`)?.textContent).toContain('*'); submit(view.container);
            await waitFor(() => expect(document.activeElement).toBe(field)); expect(field.getAttribute('aria-invalid')).toBe('true');
            expect(document.getElementById(kind === 'contact' ? 'name-error' : 'company-name-error')?.textContent).toBe(kind === 'contact' ? contacts.ContactsNewContactDialog.nameRequired : companies.CompaniesNewDialog.nameRequired);
            expect(create).not.toHaveBeenCalled(); expect(api.review).not.toHaveBeenCalled();
        });
    });
}
