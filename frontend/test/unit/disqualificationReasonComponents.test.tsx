// @vitest-environment jsdom

import { act, type ReactElement, type ReactNode } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import ContactStageDialog from '@/app/components/records/contacts/ContactStageDialog';
import ContactLeadPanel from '@/app/components/records/contacts/ContactLeadPanel';
import TimelineRow from '@/app/components/me/TimelineRow';
import DisqualificationReasonsPanel from '@/app/components/settings/DisqualificationReasonsPanel';
import type {
    Contact,
    ContactLifecycle,
    ContactLifecycleHistoryEntry,
    DisqualificationReason,
} from '@/app/lib/types';

(globalThis as typeof globalThis & { IS_REACT_ACT_ENVIRONMENT: boolean })
    .IS_REACT_ACT_ENVIRONMENT = true;

const api = vi.hoisted(() => ({
    archive: vi.fn(),
    create: vi.fn(),
    get: vi.fn(),
    restore: vi.fn(),
    update: vi.fn(),
    updateLifecycle: vi.fn(),
    withdrawLifecycle: vi.fn(),
}));
const workspace = vi.hoisted(() => ({ activeWorkspaceId: 7 as number | null }));
const navigation = vi.hoisted(() => ({ refresh: vi.fn() }));

vi.mock('next-intl', () => ({
    useLocale: () => 'en',
    useTranslations: () => (key: string) => key,
}));

vi.mock('next/navigation', () => ({
    usePathname: () => '/records/contacts/30',
    useRouter: () => navigation,
    useSearchParams: () => new URLSearchParams(),
}));

vi.mock('motion/react', () => ({ useReducedMotion: () => false }));

vi.mock('@/components/ui/tooltip', async () => {
    const React = await import('react');
    const Wrapper = ({ children }: { children: ReactNode }) =>
        React.createElement(React.Fragment, null, children);
    return {
        Tooltip: Wrapper,
        TooltipContent: Wrapper,
        TooltipTrigger: Wrapper,
    };
});

vi.mock('@/app/hooks/useWorkspace', () => ({
    useWorkspace: () => ({ activeWorkspaceId: workspace.activeWorkspaceId }),
}));

vi.mock('@/app/hooks/useApiErrorToast', () => ({
    useApiErrorToast: () => vi.fn(),
}));

vi.mock('@/app/lib/toast', () => ({ toastSuccess: vi.fn() }));

vi.mock('@/app/lib/api', () => ({
    archiveDisqualificationReason: api.archive,
    createDisqualificationReason: api.create,
    getDisqualificationReasons: api.get,
    restoreDisqualificationReason: api.restore,
    updateContactLifecycle: api.updateLifecycle,
    updateDisqualificationReason: api.update,
    withdrawContactLifecycle: api.withdrawLifecycle,
}));

vi.mock('@/components/ui/switch', async () => {
    const React = await import('react');
    return {
        Switch: ({ checked, disabled, id, onCheckedChange }: {
            checked: boolean;
            disabled?: boolean;
            id?: string;
            onCheckedChange: (checked: boolean) => void;
        }) => React.createElement('button', {
            'aria-checked': checked,
            disabled,
            id,
            onClick: () => onCheckedChange(!checked),
            role: 'switch',
            type: 'button',
        }),
    };
});

vi.mock('@/components/ui/select', async () => {
    const React = await import('react');
    return {
        Select: ({ children, disabled, onValueChange, value }: {
            children: ReactNode;
            disabled?: boolean;
            onValueChange: (value: string) => void;
            value: string;
        }) => React.createElement(
            'select',
            {
                disabled,
                onChange: (event: React.ChangeEvent<HTMLSelectElement>) =>
                    onValueChange(event.currentTarget.value),
                value,
            },
            children,
        ),
        SelectContent: ({ children }: { children: ReactNode }) => children,
        SelectItem: ({ children, value }: { children: ReactNode; value: string }) =>
            React.createElement('option', { value }, children),
        SelectTrigger: () => null,
        SelectValue: () => null,
    };
});

vi.mock('@/components/ui/responsive-dialog', async () => {
    const React = await import('react');
    const Wrapper = ({ children }: { children: ReactNode }) =>
        React.createElement('div', null, children);
    return {
        ResponsiveDialog: ({ children, open }: { children: ReactNode; open: boolean }) =>
            open ? React.createElement(React.Fragment, null, children) : null,
        ResponsiveDialogClose: ({ children }: { children: ReactNode }) => children,
        ResponsiveDialogContent: Wrapper,
        ResponsiveDialogDescription: Wrapper,
        ResponsiveDialogFooter: Wrapper,
        ResponsiveDialogHeader: Wrapper,
        ResponsiveDialogTitle: Wrapper,
    };
});

const ACTIVE_REASON: DisqualificationReason = {
    id: 10,
    code: 'NO_REGION',
    label: 'Outside our region',
    requiresNote: true,
    position: 10,
    builtIn: false,
    archivedAt: null,
};
const ARCHIVED_REASON: DisqualificationReason = {
    id: 11,
    code: 'OLD_REASON',
    label: 'Old reason',
    requiresNote: false,
    position: 11,
    builtIn: false,
    archivedAt: '2026-08-01T00:00:00Z',
};
const CURRENT_ARCHIVED_REASON: DisqualificationReason = {
    ...ARCHIVED_REASON,
    id: 12,
    code: 'CURRENT_OLD_REASON',
    label: 'Current old reason',
};
const LIFECYCLE: ContactLifecycle = {
    personId: 30,
    stage: 'DISQUALIFIED',
    changedAt: '2026-08-01T00:00:00Z',
    disqualifiedReason: CURRENT_ARCHIVED_REASON.code,
    reasonLabel: CURRENT_ARCHIVED_REASON.label,
    qualificationNotes: null,
    allowedTransitions: ['RECYCLED'],
};

let container: HTMLDivElement;
let root: Root;

beforeEach(() => {
    container = document.createElement('div');
    document.body.append(container);
    root = createRoot(container);
    workspace.activeWorkspaceId = 7;
    api.get.mockReset();
    api.archive.mockReset().mockResolvedValue(undefined);
    api.create.mockReset().mockResolvedValue(ACTIVE_REASON);
    api.restore.mockReset().mockResolvedValue(undefined);
    api.update.mockReset().mockResolvedValue(ACTIVE_REASON);
    api.updateLifecycle.mockReset().mockResolvedValue(LIFECYCLE);
    api.withdrawLifecycle.mockReset().mockResolvedValue(LIFECYCLE);
    navigation.refresh.mockReset();
});

afterEach(async () => {
    await act(async () => root.unmount());
    container.remove();
});

async function render(element: ReactElement) {
    await act(async () => {
        root.render(element);
        await Promise.resolve();
    });
}

async function settle() {
    await act(async () => {
        await Promise.resolve();
        await Promise.resolve();
    });
}

function button(text: string): HTMLButtonElement {
    const found = Array.from(container.querySelectorAll('button'))
        .find((candidate) => candidate.textContent?.trim() === text);
    if (!(found instanceof HTMLButtonElement)) throw new Error(`Missing button: ${text}`);
    return found;
}

async function click(element: HTMLElement) {
    await act(async () => element.click());
    await settle();
}

async function changeInput(input: HTMLInputElement | HTMLTextAreaElement, value: string) {
    const prototype = input instanceof HTMLTextAreaElement
        ? HTMLTextAreaElement.prototype
        : HTMLInputElement.prototype;
    const setter = Object.getOwnPropertyDescriptor(prototype, 'value')?.set;
    setter?.call(input, value);
    await act(async () => input.dispatchEvent(new Event('input', { bubbles: true })));
}

async function changeSelect(select: HTMLSelectElement, value: string) {
    select.value = value;
    await act(async () => select.dispatchEvent(new Event('change', { bubbles: true })));
}

describe('DisqualificationReasonsPanel', () => {
    it('renders the real panel and exercises create, update, archive, and restore controls', async () => {
        api.get.mockResolvedValue([ACTIVE_REASON, ARCHIVED_REASON]);
        await render(<DisqualificationReasonsPanel />);
        await settle();

        expect(container.textContent).toContain(ACTIVE_REASON.label);
        await click(button('edit'));
        const editedLabel = container.querySelector('#reason-label');
        if (!(editedLabel instanceof HTMLInputElement)) throw new Error('Missing edit label');
        await changeInput(editedLabel, 'A narrower region');
        await click(button('save'));
        expect(api.update).toHaveBeenCalledWith(
            ACTIVE_REASON.id,
            expect.objectContaining({ code: ACTIVE_REASON.code, label: 'A narrower region' }),
        );

        await click(button('archive'));
        expect(api.archive).toHaveBeenCalledWith(ACTIVE_REASON.id);
        const archivedSwitch = container.querySelector('[role="switch"]');
        if (!(archivedSwitch instanceof HTMLButtonElement)) throw new Error('Missing archived switch');
        await click(archivedSwitch);
        await click(button('restore'));
        expect(api.restore).toHaveBeenCalledWith(ARCHIVED_REASON.id);

        await click(button('add'));
        const code = container.querySelector('#reason-code');
        const label = container.querySelector('#reason-label');
        if (!(code instanceof HTMLInputElement) || !(label instanceof HTMLInputElement)) {
            throw new Error('Missing create fields');
        }
        await changeInput(code, 'partner_only');
        await changeInput(label, 'Partners only');
        expect(button('save').disabled).toBe(true);
        expect(container.textContent).toContain('codeInvalid');
        await click(button('save'));
        expect(api.create).not.toHaveBeenCalled();

        await changeInput(code, 'PARTNER_ONLY');
        expect(button('save').disabled).toBe(false);
        await click(button('save'));
        expect(api.create).toHaveBeenCalledWith(expect.objectContaining({
            code: 'PARTNER_ONLY',
            label: 'Partners only',
        }));

        expect(button('add').tabIndex).toBe(0);
        expect(button('add').tagName).toBe('BUTTON');
    });

    it('drops the old catalog and draft immediately when the workspace changes', async () => {
        let resolveSecond!: (reasons: DisqualificationReason[]) => void;
        const second = new Promise<DisqualificationReason[]>((resolve) => {
            resolveSecond = resolve;
        });
        api.get.mockImplementation(() => workspace.activeWorkspaceId === 7
            ? Promise.resolve([ACTIVE_REASON])
            : second);
        await render(<DisqualificationReasonsPanel />);
        await settle();
        await click(button('edit'));
        expect(container.querySelector('#reason-label')).not.toBeNull();

        workspace.activeWorkspaceId = 8;
        await render(<DisqualificationReasonsPanel />);
        expect(container.textContent).not.toContain(ACTIVE_REASON.label);
        expect(container.querySelector('#reason-label')).toBeNull();

        const secondReason = { ...ACTIVE_REASON, id: 80, label: 'Workspace eight reason' };
        resolveSecond([secondReason]);
        await settle();
        expect(container.textContent).toContain(secondReason.label);
        expect(container.textContent).not.toContain(ACTIVE_REASON.label);
    });
});

describe('ContactStageDialog', () => {
    it('renders active reasons plus the archived current choice and submits through semantic controls', async () => {
        const invalidReasons = [
            { ...ACTIVE_REASON, id: 21, code: 'A' },
            { ...ACTIVE_REASON, id: 22, code: 'BAD-CODE' },
            { ...ACTIVE_REASON, id: 23, code: 'ÖTHER' },
        ];
        api.get.mockResolvedValue([
            ACTIVE_REASON,
            ARCHIVED_REASON,
            CURRENT_ARCHIVED_REASON,
            ...invalidReasons,
        ]);
        await render(
            <ContactStageDialog
                contactId={30}
                lifecycle={LIFECYCLE}
                hasLinkedDeal={false}
                open
                onOpenChange={vi.fn()}
            />,
        );
        await settle();

        const stage = container.querySelector('select');
        if (!(stage instanceof HTMLSelectElement)) throw new Error('Missing stage select');
        await changeSelect(stage, 'DISQUALIFIED');
        const selects = container.querySelectorAll('select');
        const reason = selects.item(1);
        if (!(reason instanceof HTMLSelectElement)) throw new Error('Missing reason select');
        const values = Array.from(reason.options, (option) => option.value);
        expect(values).toContain(ACTIVE_REASON.code);
        expect(values).toContain(CURRENT_ARCHIVED_REASON.code);
        expect(values).not.toContain(ARCHIVED_REASON.code);
        expect(values).not.toContain('A');
        expect(values).not.toContain('BAD-CODE');
        expect(values).not.toContain('ÖTHER');

        await changeSelect(reason, ACTIVE_REASON.code);
        expect(button('save').disabled).toBe(true);
        const note = container.querySelector('#contact-lifecycle-note');
        if (!(note instanceof HTMLTextAreaElement)) throw new Error('Missing lifecycle note');
        await changeInput(note, 'Territory is not supported');
        expect(button('save').disabled).toBe(false);
        expect(stage.tabIndex).toBe(0);
        expect(button('save').tagName).toBe('BUTTON');
        await click(button('save'));
        expect(api.updateLifecycle).toHaveBeenCalledWith(30, {
            stage: 'DISQUALIFIED',
            reason: ACTIVE_REASON.code,
            note: 'Territory is not supported',
        });
        expect(navigation.refresh).toHaveBeenCalled();
    });

    it('fails closed when reasons are unavailable and resets an open dialog on workspace switch', async () => {
        api.get.mockRejectedValueOnce(new Error('unavailable'));
        await render(
            <ContactStageDialog
                contactId={30}
                lifecycle={LIFECYCLE}
                hasLinkedDeal={false}
                open
                onOpenChange={vi.fn()}
            />,
        );
        await settle();
        const stage = container.querySelector('select');
        if (!(stage instanceof HTMLSelectElement)) throw new Error('Missing stage select');
        await changeSelect(stage, 'DISQUALIFIED');
        expect(container.textContent).toContain('reasonsUnavailable');
        expect(button('save').disabled).toBe(true);

        let resolveNext!: (reasons: DisqualificationReason[]) => void;
        api.get.mockImplementationOnce(() => new Promise((resolve) => {
            resolveNext = resolve;
        }));
        workspace.activeWorkspaceId = 8;
        await render(
            <ContactStageDialog
                contactId={30}
                lifecycle={LIFECYCLE}
                hasLinkedDeal={false}
                open
                onOpenChange={vi.fn()}
            />,
        );
        await settle();
        expect(container.querySelectorAll('select')).toHaveLength(1);
        resolveNext([ACTIVE_REASON]);
        await settle();
    });

    it('does not offer or submit a malformed current catalog code', async () => {
        const malformedCode = 'BAD-CODE';
        api.get.mockResolvedValue([{ ...ACTIVE_REASON, code: malformedCode }]);
        await render(
            <ContactStageDialog
                contactId={30}
                lifecycle={{ ...LIFECYCLE, disqualifiedReason: malformedCode }}
                hasLinkedDeal={false}
                open
                onOpenChange={vi.fn()}
            />,
        );
        await settle();

        const stage = container.querySelector('select');
        if (!(stage instanceof HTMLSelectElement)) throw new Error('Missing stage select');
        await changeSelect(stage, 'DISQUALIFIED');
        const reason = container.querySelectorAll('select').item(1);
        if (!(reason instanceof HTMLSelectElement)) throw new Error('Missing reason select');
        expect(Array.from(reason.options, (option) => option.value)).not.toContain(malformedCode);
        expect(button('save').disabled).toBe(true);
        await click(button('save'));
        expect(api.updateLifecycle).not.toHaveBeenCalled();
    });
});

describe('workspace-authored reason labels', () => {
    it('renders the server-resolved label in ContactLeadPanel', async () => {
        const contact: Contact = {
            id: 30,
            workspaceId: 7,
            name: 'Lead contact',
            email: 'lead@example.com',
            phone: '',
            title: '',
            imageUrl: '',
            createdAt: '2026-08-01T00:00:00Z',
            updatedAt: '2026-08-01T00:00:00Z',
        };

        await render(
            <ContactLeadPanel
                contact={contact}
                lifecycle={LIFECYCLE}
                qualification={null}
                referrer={null}
                hasLinkedDeal={false}
                canEdit={false}
            />,
        );

        expect(container.textContent).toContain(CURRENT_ARCHIVED_REASON.label);
        expect(container.textContent).not.toContain(`reason.${CURRENT_ARCHIVED_REASON.code}`);
    });

    it('renders the server-resolved label in TimelineRow', async () => {
        const lifecycle: ContactLifecycleHistoryEntry = {
            id: 91,
            personId: 30,
            fromStage: 'WORKING',
            toStage: 'DISQUALIFIED',
            reason: ACTIVE_REASON.code,
            reasonLabel: ACTIVE_REASON.label,
            note: null,
            changedById: null,
            changedAt: '2026-08-01T00:00:00Z',
        };

        await render(
            <TimelineRow
                entry={{ kind: 'lifecycle', sortAt: Date.now(), lifecycle }}
                persons={[]}
                deals={[]}
                companyId={null}
                originWorkspaceId={7}
            />,
        );

        expect(container.textContent).toContain(ACTIVE_REASON.label);
        expect(container.textContent).not.toContain(`reason.${ACTIVE_REASON.code}`);
    });
});
