// @vitest-environment jsdom

import { act, type ReactElement } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import ContactStageDialog from '@/app/components/records/contacts/ContactStageDialog';
import DisqualificationReasonsPanel from '@/app/components/settings/DisqualificationReasonsPanel';
import type { ContactLifecycle, DisqualificationReason } from '@/app/lib/types';

(globalThis as typeof globalThis & { IS_REACT_ACT_ENVIRONMENT: boolean })
    .IS_REACT_ACT_ENVIRONMENT = true;

const api = vi.hoisted(() => ({
    create: vi.fn(),
    get: vi.fn(),
    updateLifecycle: vi.fn(),
}));
const navigation = vi.hoisted(() => ({ refresh: vi.fn() }));

vi.mock('next-intl', () => ({
    useTranslations: () => (key: string) => key,
}));

vi.mock('next/navigation', () => ({
    useRouter: () => navigation,
}));

vi.mock('@/app/hooks/useWorkspace', () => ({
    useWorkspace: () => ({ activeWorkspaceId: 7 }),
}));

vi.mock('@/app/hooks/useApiErrorToast', () => ({
    useApiErrorToast: () => vi.fn(),
}));

vi.mock('@/app/lib/toast', () => ({ toastSuccess: vi.fn() }));

vi.mock('@/app/lib/api', async () => {
    const actual = await vi.importActual<typeof import('@/app/lib/api')>('@/app/lib/api');
    return {
        ...actual,
        createDisqualificationReason: api.create,
        getDisqualificationReasons: api.get,
        updateContactLifecycle: api.updateLifecycle,
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

let container: HTMLDivElement;
let root: Root;

beforeEach(() => {
    Object.defineProperty(window, 'matchMedia', {
        configurable: true,
        value: (query: string) => ({
            matches: false,
            media: query,
            onchange: null,
            addEventListener: () => undefined,
            removeEventListener: () => undefined,
            addListener: () => undefined,
            removeListener: () => undefined,
            dispatchEvent: () => false,
        }),
    });
    vi.stubGlobal('ResizeObserver', class {
        observe(): void { return undefined; }
        unobserve(): void { return undefined; }
        disconnect(): void { return undefined; }
    });
    Object.defineProperty(HTMLElement.prototype, 'scrollIntoView', {
        configurable: true,
        value: () => undefined,
    });
    container = document.createElement('div');
    document.body.append(container);
    root = createRoot(container);
    api.get.mockReset().mockResolvedValue([ACTIVE_REASON]);
    api.create.mockReset().mockResolvedValue(ACTIVE_REASON);
    api.updateLifecycle.mockReset().mockResolvedValue(undefined);
    navigation.refresh.mockReset();
});

afterEach(async () => {
    await act(async () => root.unmount());
    container.remove();
    document.body.querySelectorAll('[data-radix-popper-content-wrapper]').forEach((node) => node.remove());
    vi.unstubAllGlobals();
});

async function render(element: ReactElement) {
    await act(async () => {
        root.render(element);
        await Promise.resolve();
    });
    await settle();
}

async function settle() {
    await act(async () => {
        await Promise.resolve();
        await Promise.resolve();
    });
}

function focusableElements(): HTMLElement[] {
    return Array.from(document.body.querySelectorAll<HTMLElement>(
        'button:not([disabled]), input:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])',
    )).filter((element) => !element.hasAttribute('hidden'));
}

async function tabUntil(predicate: (element: HTMLElement) => boolean) {
    for (let index = 0; index < 30; index++) {
        await tab();
        if (document.activeElement instanceof HTMLElement
                && predicate(document.activeElement)) return document.activeElement;
    }
    throw new Error('Keyboard focus did not reach the expected control');
}

async function tab() {
    await act(async () => {
        const current = document.activeElement instanceof HTMLElement
            ? document.activeElement
            : null;
        const controls = focusableElements();
        const currentIndex = current === null ? -1 : controls.indexOf(current);
        const next = controls[(currentIndex + 1) % controls.length];
        const event = new KeyboardEvent('keydown', {
            bubbles: true,
            cancelable: true,
            key: 'Tab',
        });
        current?.dispatchEvent(event);
        if (!event.defaultPrevented) next?.focus();
        current?.dispatchEvent(new KeyboardEvent('keyup', { bubbles: true, key: 'Tab' }));
    });
    await settle();
}

async function press(key: string) {
    await act(async () => {
        const target = document.activeElement;
        if (!(target instanceof HTMLElement)) throw new Error('No focused keyboard target');
        const event = new KeyboardEvent('keydown', {
            bubbles: true,
            cancelable: true,
            key,
        });
        target.dispatchEvent(event);
        if (!event.defaultPrevented
                && target instanceof HTMLButtonElement
                && (key === 'Enter' || key === ' ')) {
            target.click();
        }
        target.dispatchEvent(new KeyboardEvent('keyup', { bubbles: true, key }));
    });
    await settle();
}

async function typeText(value: string) {
    for (const character of value) {
        await act(async () => {
            const target = document.activeElement;
            if (!(target instanceof HTMLInputElement || target instanceof HTMLTextAreaElement)) {
                throw new Error('Focused control cannot receive text');
            }
            target.dispatchEvent(new KeyboardEvent('keydown', {
                bubbles: true,
                cancelable: true,
                key: character,
            }));
            const setter = Object.getOwnPropertyDescriptor(
                target instanceof HTMLTextAreaElement
                    ? HTMLTextAreaElement.prototype
                    : HTMLInputElement.prototype,
                'value',
            )?.set;
            setter?.call(target, target.value + character);
            target.dispatchEvent(new Event('input', { bubbles: true }));
            target.dispatchEvent(new KeyboardEvent('keyup', { bubbles: true, key: character }));
        });
    }
    await settle();
}

describe('disqualification keyboard interactions', () => {
    it('tabs through the real panel controls and activates buttons and the switch', async () => {
        api.get.mockResolvedValue([]);
        await render(<DisqualificationReasonsPanel />);

        await tabUntil((element) => element.textContent?.trim() === 'add');
        await press('Enter');
        await tabUntil((element) => element.id === 'reason-code');
        await typeText('PARTNER_ONLY');
        await tabUntil((element) => element.id === 'reason-label');
        await typeText('Partners only');
        await tabUntil((element) => element.id === 'reason-requires-note');
        await press(' ');
        expect(document.activeElement?.getAttribute('aria-checked')).toBe('true');
        await tabUntil((element) => element.textContent?.trim() === 'save');
        await press('Enter');

        expect(api.create).toHaveBeenCalledWith(expect.objectContaining({
            code: 'PARTNER_ONLY',
            label: 'Partners only',
            requiresNote: true,
        }));
    });

    it('uses the real dialog and select primitives from stage choice through save', async () => {
        const lifecycle: ContactLifecycle = {
            personId: 30,
            stage: null,
            changedAt: null,
            disqualifiedReason: null,
            reasonLabel: null,
            qualificationNotes: null,
            allowedTransitions: ['DISQUALIFIED'],
        };
        await render(
            <ContactStageDialog
                contactId={30}
                lifecycle={lifecycle}
                hasLinkedDeal={false}
                open
                onOpenChange={vi.fn()}
            />,
        );

        await tabUntil((element) => element.id === 'contact-lifecycle-stage');
        await press('Enter');
        await press('ArrowDown');
        await press('Enter');
        await tabUntil((element) => element.id === 'contact-lifecycle-reason');
        await press('Enter');
        await press('ArrowDown');
        await press('Enter');
        await tabUntil((element) => element.id === 'contact-lifecycle-note');
        await typeText('Territory is not supported');
        await tabUntil((element) => element.textContent?.trim() === 'save');
        await press('Enter');

        expect(api.updateLifecycle).toHaveBeenCalledWith(30, {
            stage: 'DISQUALIFIED',
            reason: ACTIVE_REASON.code,
            note: 'Territory is not supported',
        });
    });
});
