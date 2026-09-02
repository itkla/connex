import {
    act,
    createRef,
    createElement,
    Fragment,
    type ButtonHTMLAttributes,
    type ReactNode,
} from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import DealCreateContainer from '@/app/components/actions/create/DealCreateContainer';
import MobileCreateFlow, {
    type MobileCreateFlowHandle,
} from '@/app/components/actions/MobileCreateFlow';
import { isDealPayloadDirty } from '@/app/components/records/deals/NewDealDialog';
import DraftResumeBridge from '@/app/components/DraftResumeBridge';
import { DRAFT_DEBOUNCE_MS } from '@/app/hooks/useFormDraft';
import {
    DRAFT_VERSIONS,
    advanceDraftKeyGeneration,
    clearAllDrafts,
    draftKey,
    getDraftKeyGeneration,
    isDealDraft,
    listFreshDrafts,
    readDraft,
    writeDraft,
    type DealDraft,
} from '@/app/lib/formDrafts';
import type { Company, CreateDealPayload, Page, Pipeline, Stage } from '@/app/lib/types';
import type { ActionContext, AppAction, OverlayRequest } from '@/app/lib/actions/types';
import {
    installInteractiveDocument,
    type InteractiveElement,
} from '@/test/unit/helpers/interactiveDocument';

type ToastAction = { onClick: () => void };
type ToastOptions = { action?: ToastAction; cancel?: ToastAction };
type ConfirmCapture = {
    open: boolean;
    onKeepEditing: () => void;
    onDiscard: () => void;
};

const state = vi.hoisted(() => ({ activeWorkspaceId: 17, switching: false, reducedMotion: true }));
const actions = vi.hoisted(() => ({ openOverlay: vi.fn<(request: OverlayRequest) => void>() }));
const translate = vi.hoisted(() => (key: string, values?: Record<string, string | number>) => (
    values?.label === undefined ? key : `${key}:${values.label}`
));
const toasts = vi.hoisted(() => ({
    dismiss: vi.fn(),
    error: vi.fn(),
    info: vi.fn<(message: string, options: ToastOptions) => void>(),
    success: vi.fn(),
    warn: vi.fn(),
}));
const dialog = vi.hoisted(() => ({
    confirms: [] as ConfirmCapture[],
    dismissals: [] as Array<(open: boolean) => void>,
    shells: [] as Array<{ open?: boolean }>,
    contents: [] as Array<{ showCloseButton?: boolean }>,
}));
const duplicatePreflight = vi.hoisted(() => ({
    acknowledged: false,
    blocked: false,
    response: null,
    retry: vi.fn(),
    reviewNow: vi.fn(async () => ({
        allowed: true,
        duplicateReviewToken: 'review-token',
        reviewSignature: 'review-signature',
        response: null,
    })),
    setAcknowledged: vi.fn(),
    status: 'idle' as const,
}));
const api = vi.hoisted(() => ({
    createDeal: vi.fn(async () => undefined),
    getCompaniesByIds: vi.fn(async () => [] as Company[]),
    getCompaniesPage: vi.fn(async (): Promise<Page<Company>> => ({
        items: [],
        total: 0,
    })),
    getPipelines: vi.fn(async () => [] as Pipeline[]),
    getStagesByPipelineId: vi.fn<(pipelineId: number) => Promise<Stage[]>>(),
}));

vi.mock('next/navigation', () => ({
    useRouter: () => ({ refresh: vi.fn() }),
}));

vi.mock('next/dynamic', async () => {
    const React = await import('react');
    type DynamicComponent = React.ComponentType<Record<string, unknown>>;
    return {
        default: (loader: () => Promise<{ default: DynamicComponent }>) => function Dynamic(
            props: Record<string, unknown>,
        ) {
            const [Component, setComponent] = React.useState<DynamicComponent | null>(null);
            React.useEffect(() => {
                let active = true;
                void loader().then((loaded) => {
                    if (active) setComponent(() => loaded.default);
                });
                return () => {
                    active = false;
                };
            }, []);
            return Component ? React.createElement(Component, props) : null;
        },
    };
});

vi.mock('next-intl', () => ({
    useTranslations: () => translate,
}));

vi.mock('motion/react', async () => {
    const React = await import('react');
    const element = (tag: 'button' | 'div') => {
        function MotionElement({ children, ...props }: {
            children?: ReactNode;
            [key: string]: unknown;
        }) {
            const motionProps = new Set(['animate', 'custom', 'exit', 'initial', 'layout', 'transition', 'variants', 'whileTap']);
            const domProps = Object.fromEntries(Object.entries(props).filter(([key]) => !motionProps.has(key)));
            if (props.transition !== undefined) {
                domProps['data-motion-transition'] = JSON.stringify(props.transition);
            }
            return React.createElement(tag, domProps, children);
        }
        return MotionElement;
    };
    return {
        AnimatePresence: ({ children }: { children?: ReactNode }) => React.createElement(Fragment, null, children),
        motion: { button: element('button'), div: element('div') },
        useReducedMotion: () => state.reducedMotion,
    };
});

vi.mock('@/app/hooks/useActions', () => ({
    useActions: () => ({
        openOverlay: actions.openOverlay,
        context: { user: { id: 7 } },
    }),
}));

vi.mock('@/app/hooks/useWorkspace', () => ({
    useWorkspace: () => state,
}));

vi.mock('@/app/hooks/useApiErrorToast', () => ({
    useApiErrorToast: () => vi.fn(),
}));

vi.mock('@/app/hooks/useDuplicatePreflight', () => ({
    useDuplicatePreflight: () => duplicatePreflight,
}));

vi.mock('@/app/lib/toast', () => ({
    toastDismiss: toasts.dismiss,
    toastError: toasts.error,
    toastInfo: toasts.info,
    toastSuccess: toasts.success,
    toastWarn: toasts.warn,
}));

vi.mock('@/app/lib/api', async () => {
    const actual = await vi.importActual<typeof import('@/app/lib/api')>('@/app/lib/api');
    return {
        ...actual,
        createDeal: api.createDeal,
        getCompaniesByIds: api.getCompaniesByIds,
        getCompaniesPage: api.getCompaniesPage,
        getPipelines: api.getPipelines,
        getStagesByPipelineId: api.getStagesByPipelineId,
    };
});

vi.mock('@/components/ui/responsive-dialog', async () => {
    const React = await import('react');
    const Passthrough = ({ children }: { children?: ReactNode }) => React.createElement(Fragment, null, children);
    return {
        ResponsiveDialog: ({
            children,
            onOpenChange,
            open,
        }: {
            children?: ReactNode;
            onOpenChange?: (open: boolean) => void;
            open?: boolean;
        }) => {
            if (onOpenChange) dialog.dismissals.push(onOpenChange);
            dialog.shells.push({ open });
            return React.createElement(Fragment, null, children);
        },
        ResponsiveDialogContent: ({
            children,
            showCloseButton,
        }: {
            children?: ReactNode;
            showCloseButton?: boolean;
        }) => {
            dialog.contents.push({ showCloseButton });
            return React.createElement(Fragment, null, children);
        },
        ResponsiveDialogDescription: Passthrough,
        ResponsiveDialogTitle: Passthrough,
    };
});

vi.mock('@/app/components/ConfirmDiscardDialog', () => ({
    default: (props: ConfirmCapture) => {
        dialog.confirms.push(props);
        return null;
    },
}));

vi.mock('@/components/ui/drawer', async () => {
    const React = await import('react');
    const Passthrough = ({ children }: { children?: ReactNode }) => React.createElement(Fragment, null, children);
    const element = (tag: 'header' | 'h2' | 'p') => {
        function DrawerElement({ children, ...props }: {
            children?: ReactNode;
            [key: string]: unknown;
        }) {
            return React.createElement(tag, props, children);
        }
        return DrawerElement;
    };
    return {
        Drawer: Passthrough,
        DrawerContent: Passthrough,
        DrawerDescription: element('p'),
        DrawerHeader: element('header'),
        DrawerTitle: element('h2'),
    };
});

vi.mock('@/components/ui/button', async () => {
    const React = await import('react');
    return {
        Button: (props: ButtonHTMLAttributes<HTMLButtonElement> & {
            variant?: string;
            size?: string;
            press?: string;
            asChild?: boolean;
            menu?: boolean;
        }) => React.createElement('button', {
            autoFocus: props.autoFocus,
            className: props.className,
            disabled: props.disabled,
            onClick: props.onClick,
            type: props.type,
        }, props.children),
    };
});

vi.mock('@/components/ui/combobox', async () => {
    const React = await import('react');
    const Passthrough = ({ children }: { children?: ReactNode }) => React.createElement(Fragment, null, children);
    const selectedLabel = (value: unknown) => (
        typeof value === 'object' && value !== null && 'name' in value && typeof value.name === 'string'
            ? value.name
            : ''
    );
    return {
        Combobox: ({ children, value }: { children?: ReactNode; value?: unknown }) => React.createElement(
            'div',
            { 'data-selected-label': selectedLabel(value) },
            children,
        ),
        ComboboxContent: Passthrough,
        ComboboxEmpty: Passthrough,
        ComboboxInput: ({ children, id }: { children?: ReactNode; id?: string }) => React.createElement(
            'div',
            { 'data-combobox-input': id },
            children,
        ),
        ComboboxItem: Passthrough,
        ComboboxList: Passthrough,
    };
});

vi.mock('@/components/ui/input-group', async () => {
    const React = await import('react');
    return {
        InputGroupAddon: ({ children }: { children?: ReactNode }) => React.createElement(Fragment, null, children),
    };
});

vi.mock('@/components/ui/dialog-status-cover', () => ({
    DialogStatusCover: () => null,
    fieldErrorClass: 'field-error',
    fieldInputClass: 'field-input',
    fieldLeadIconClass: 'field-icon',
    resolveDialogStatus: () => 'idle',
}));

vi.mock('@/app/components/records/DuplicatePreflightWarning', () => ({
    default: () => null,
}));

vi.mock('@/app/components/activity/notes/MentionEditor', async () => {
    const React = await import('react');
    return {
        default: ({
            id,
            value,
            onChange,
        }: {
            id: string;
            value: string;
            onChange: (value: string) => void;
        }) => React.createElement('textarea', {
            id,
            value,
            onChange: (event: { target: { value: string } }) => onChange(event.target.value),
        }),
    };
});

function createStorage(): Storage {
    const values: Record<string, string> = {};
    const storage: Storage = {
        get length() {
            return Object.keys(values).length;
        },
        clear() {
            for (const key of Object.keys(values)) delete values[key];
        },
        getItem(key) {
            return values[key] ?? null;
        },
        key(index) {
            return Object.keys(values)[index] ?? null;
        },
        removeItem(key) {
            delete values[key];
        },
        setItem(key, value) {
            values[key] = value;
        },
    };
    return new Proxy(storage, {
        ownKeys: () => Object.keys(values),
        getOwnPropertyDescriptor: (_, key) => (
            typeof key === 'string' && key in values
                ? { configurable: true, enumerable: true, value: values[key], writable: true }
                : undefined
        ),
    });
}

const VALID_DEAL_DRAFT: DealDraft = {
    name: 'Renewal',
    value: 12500,
    currency: 'USD',
    pipeline: 4,
    stage: 9,
    company: 22,
    expectedCloseDate: '2026-10-01',
};
const PIPELINE: Pipeline = {
    id: 4,
    name: 'Sales',
    createdAt: '2026-08-01T00:00:00Z',
    updatedAt: '2026-08-01T00:00:00Z',
};
const OTHER_PIPELINE: Pipeline = {
    id: 5,
    name: 'Expansion',
    createdAt: '2026-08-01T00:00:00Z',
    updatedAt: '2026-08-01T00:00:00Z',
};
const STAGE: Stage = {
    id: 9,
    name: 'Qualified',
    pipeline: 4,
    position: 0,
    success: false,
    failure: false,
};
const COMPANY: Company = {
    id: 22,
    name: 'Northstar Labs',
    website: '',
    industry: '',
    phone: '',
    address: '',
    logoUrl: '',
    createdAt: '2026-08-01T00:00:00Z',
    updatedAt: '2026-08-01T00:00:00Z',
};
const KEY_PARTS = { userId: 7, workspaceId: 17, formType: 'deal', scope: 'global' };
const DEAL_ACTION = {
    id: 'create.deal',
    group: 'create',
    labelKey: 'create.deal',
    execute: () => undefined,
} satisfies AppAction;
const ACTION_CONTEXT = {
    workspace: null,
    user: null,
    route: { pathname: '/' },
    locale: 'en',
    record: null,
    selection: null,
    can: () => true,
} satisfies ActionContext;

type Mounted = ReturnType<typeof installInteractiveDocument> & {
    dispatchWindow: (type: string) => void;
    rerender: (next: ReactNode) => Promise<void>;
    unmount: () => Promise<void>;
};

async function settle(): Promise<void> {
    await Promise.resolve();
    await Promise.resolve();
    await Promise.resolve();
}

async function render(component: ReactNode, storage = createStorage()): Promise<Mounted> {
    const { createRoot } = await import('react-dom/client');
    const installed = installInteractiveDocument();
    const windowListeners = new Map<string, Set<EventListenerOrEventListenerObject>>();
    Object.defineProperties(window, {
        sessionStorage: { configurable: true, value: storage },
        setTimeout: { configurable: true, value: globalThis.setTimeout.bind(globalThis) },
        clearTimeout: { configurable: true, value: globalThis.clearTimeout.bind(globalThis) },
        addEventListener: {
            configurable: true,
            value: (type: string, listener: EventListenerOrEventListenerObject) => {
                const listeners = windowListeners.get(type) ?? new Set<EventListenerOrEventListenerObject>();
                listeners.add(listener);
                windowListeners.set(type, listeners);
            },
        },
        removeEventListener: {
            configurable: true,
            value: (type: string, listener: EventListenerOrEventListenerObject) => {
                windowListeners.get(type)?.delete(listener);
            },
        },
    });
    const root = createRoot(installed.container, { onCaughtError: vi.fn() });
    await act(async () => {
        root.render(component);
        await settle();
    });
    return {
        ...installed,
        dispatchWindow: (type) => {
            const event = new Event(type);
            for (const listener of windowListeners.get(type) ?? []) {
                if (typeof listener === 'function') listener.call(window, event);
                else listener.handleEvent(event);
            }
        },
        rerender: async (next) => {
            await act(async () => {
                root.render(next);
                await settle();
            });
        },
        unmount: async () => {
            await act(async () => root.unmount());
        },
    };
}

function latestToast(): ToastOptions {
    const call = toasts.info.mock.calls.at(-1);
    if (!call) throw new Error('Expected a draft toast');
    return call[1];
}

function connectedElements(mounted: Mounted, tagName?: string): InteractiveElement[] {
    const body = mounted.elements.find((element) => element.tagName === 'BODY');
    return mounted.elements.filter((element) => (
        (tagName === undefined || element.tagName === tagName) &&
        body?.contains(element)
    ));
}

function requiredElement(mounted: Mounted, id: string): InteractiveElement {
    const element = connectedElements(mounted).findLast((candidate) => candidate.id === id);
    if (!element) throw new Error(`Expected #${id}`);
    return element;
}

function requiredButton(mounted: Mounted, predicate: (button: InteractiveElement) => boolean): InteractiveElement {
    const button = connectedElements(mounted, 'BUTTON').find(predicate);
    if (!button) throw new Error('Expected a matching button');
    return button;
}

function dealNameInputs(mounted: Mounted): InteractiveElement[] {
    return connectedElements(mounted, 'INPUT').filter((element) => element.id === 'deal-name');
}

async function changeInput(mounted: Mounted, input: InteractiveElement, value: string): Promise<void> {
    await act(async () => {
        if (!mounted.elements.includes(input)) throw new Error('Expected a mounted input');
        input.value = value;
        const reactPropsKey = Reflect.ownKeys(input).find((key) => (
            typeof key === 'string' && key.startsWith('__reactProps$')
        ));
        const reactProps: unknown = reactPropsKey === undefined ? null : Reflect.get(input, reactPropsKey);
        if (
            typeof reactProps !== 'object' ||
            reactProps === null ||
            !('onChange' in reactProps) ||
            typeof reactProps.onChange !== 'function'
        ) {
            throw new Error('Expected a React change handler');
        }
        Reflect.apply(reactProps.onChange, undefined, [{ target: { value } }]);
        await settle();
    });
}

function ancestor(element: InteractiveElement, tagName: string): InteractiveElement {
    let current = element.parentNode;
    while (current !== null) {
        if (current.tagName === tagName) return current;
        current = current.parentNode;
    }
    throw new Error(`Expected ${tagName} ancestor`);
}

function descendants(element: InteractiveElement): InteractiveElement[] {
    const found: InteractiveElement[] = [];
    for (const child of element.childNodes) {
        if (child.nodeType !== 1) continue;
        found.push(child, ...descendants(child));
    }
    return found;
}

function latestOpenConfirm(): ConfirmCapture {
    const capture = dialog.confirms.findLast((candidate) => candidate.open);
    if (!capture) throw new Error('Expected an open discard confirmation');
    return capture;
}

function readDealDraft() {
    return readDraft(KEY_PARTS, { version: DRAFT_VERSIONS.deal });
}

function dealContainer(
    props: Partial<Parameters<typeof DealCreateContainer>[0]> = {},
    key?: string,
) {
    return createElement(DealCreateContainer, {
        key,
        open: true,
        onOpenChange: vi.fn(),
        currentUserId: 7,
        draftPersistence: true,
        ...props,
    });
}

describe('deal draft schema', () => {
    it('accepts the exact scalar schema and rejects malformed or object-bearing payloads', () => {
        class File {}

        expect(isDealDraft(VALID_DEAL_DRAFT)).toBe(true);
        expect(isDealDraft({ ...VALID_DEAL_DRAFT, value: Number.NaN })).toBe(false);
        expect(isDealDraft({ ...VALID_DEAL_DRAFT, stage: 9, pipeline: null })).toBe(false);
        expect(isDealDraft({ ...VALID_DEAL_DRAFT, expectedCloseDate: '2026-02-30' })).toBe(false);
        expect(isDealDraft({ ...VALID_DEAL_DRAFT, name: new File() })).toBe(false);
        expect(isDealDraft({ ...VALID_DEAL_DRAFT, company: { id: 22 } })).toBe(false);
        expect(isDealDraft({ ...VALID_DEAL_DRAFT, attachment: new File() })).toBe(false);
        expect(isDealDraft({ ...VALID_DEAL_DRAFT, owner: { id: 7, name: 'Owner' } })).toBe(false);
        expect(isDealDraft({ ...VALID_DEAL_DRAFT, credential: 'secret' })).toBe(false);
    });

    it('rejects foreign metadata and versions while preserving user and workspace scope', async () => {
        const mounted = await render(null);
        const currentKey = draftKey(KEY_PARTS);
        writeDraft(currentKey, {
            version: DRAFT_VERSIONS.deal,
            scope: 'global',
            formType: 'deal',
            data: VALID_DEAL_DRAFT,
        });
        writeDraft(draftKey({ ...KEY_PARTS, workspaceId: 18 }), {
            version: DRAFT_VERSIONS.deal,
            scope: 'global',
            formType: 'deal',
            data: { ...VALID_DEAL_DRAFT, name: 'Other workspace' },
        });
        writeDraft(draftKey({ ...KEY_PARTS, userId: 8 }), {
            version: DRAFT_VERSIONS.deal,
            scope: 'global',
            formType: 'deal',
            data: { ...VALID_DEAL_DRAFT, name: 'Other user' },
        });

        expect(listFreshDrafts({ userId: 7, workspaceId: 17 })).toHaveLength(1);
        expect(listFreshDrafts({ userId: 7, workspaceId: 18 })[0]?.data).toMatchObject({ name: 'Other workspace' });
        expect(listFreshDrafts({ userId: 8, workspaceId: 17 })[0]?.data).toMatchObject({ name: 'Other user' });

        window.sessionStorage.setItem(currentKey, JSON.stringify({
            v: DRAFT_VERSIONS.deal,
            savedAt: Date.now(),
            scope: 'global',
            formType: 'task',
            data: VALID_DEAL_DRAFT,
        }));
        expect(readDealDraft()).toBeNull();

        window.sessionStorage.setItem(currentKey, JSON.stringify({
            v: DRAFT_VERSIONS.deal + 1,
            savedAt: Date.now(),
            scope: 'global',
            formType: 'deal',
            data: VALID_DEAL_DRAFT,
        }));
        expect(readDealDraft()).toBeNull();
        await mounted.unmount();
    });
});

describe('deal payload dirtiness', () => {
    it('treats null and zero optional ids as the same empty selections in either direction', () => {
        const zeroIds: CreateDealPayload = {
            name: '',
            value: 0,
            actualValue: 0,
            currency: 'USD',
            pipeline: 0,
            stage: 0,
            company: 0,
            ownerId: 0,
        };
        const nullIds: CreateDealPayload = {
            ...zeroIds,
            pipeline: null,
            stage: null,
            company: null,
            ownerId: null,
        };

        expect(isDealPayloadDirty(nullIds, zeroIds)).toBe(false);
        expect(isDealPayloadDirty(zeroIds, nullIds)).toBe(false);
    });
});

describe('deal draft resume bridge', () => {
    it('offers one explicit resume or discard choice and resumes the stored ids', async () => {
        const mounted = await render(null);
        const key = draftKey(KEY_PARTS);
        writeDraft(key, {
            version: DRAFT_VERSIONS.deal,
            scope: 'global',
            formType: 'deal',
            data: VALID_DEAL_DRAFT,
        });
        await mounted.rerender(createElement(DraftResumeBridge));
        await act(async () => vi.advanceTimersByTime(300));

        expect(toasts.info).toHaveBeenCalledTimes(1);
        await act(async () => latestToast().action?.onClick());
        expect(actions.openOverlay).toHaveBeenCalledWith({
            kind: 'create-deal',
            draft: VALID_DEAL_DRAFT,
            restoredDraftGeneration: getDraftKeyGeneration(key),
        });
        await mounted.unmount();
    });

    it('discards only the generation offered by the toast', async () => {
        const mounted = await render(null);
        writeDraft(draftKey(KEY_PARTS), {
            version: DRAFT_VERSIONS.deal,
            scope: 'global',
            formType: 'deal',
            data: VALID_DEAL_DRAFT,
        });
        await mounted.rerender(createElement(DraftResumeBridge));
        await act(async () => vi.advanceTimersByTime(300));
        await act(async () => latestToast().cancel?.onClick());
        expect(readDealDraft()).toBeNull();
        await mounted.unmount();
    });

    it('refuses stale toast actions after a competing writer advances the key generation', async () => {
        const mounted = await render(null);
        const key = draftKey(KEY_PARTS);
        writeDraft(key, {
            version: DRAFT_VERSIONS.deal,
            scope: 'global',
            formType: 'deal',
            data: VALID_DEAL_DRAFT,
        });
        await mounted.rerender(createElement(DraftResumeBridge));
        await act(async () => vi.advanceTimersByTime(300));
        const offered = latestToast();
        advanceDraftKeyGeneration(key);
        const newerDraft = { ...VALID_DEAL_DRAFT, name: 'Newer writer' };
        writeDraft(key, {
            version: DRAFT_VERSIONS.deal,
            scope: 'global',
            formType: 'deal',
            data: newerDraft,
        });
        await act(async () => offered.action?.onClick());
        await act(async () => offered.cancel?.onClick());
        expect(actions.openOverlay).not.toHaveBeenCalled();
        expect(readDealDraft()?.data).toEqual(newerDraft);
        await mounted.unmount();
    });

    it('preserves the previous workspace deal draft and offers it again when that workspace returns', async () => {
        const mounted = await render(null);
        const key = draftKey(KEY_PARTS);
        writeDraft(key, {
            version: DRAFT_VERSIONS.deal,
            scope: 'global',
            formType: 'deal',
            data: VALID_DEAL_DRAFT,
        });
        await mounted.rerender(createElement(DraftResumeBridge));

        state.activeWorkspaceId = 18;
        await mounted.rerender(createElement(DraftResumeBridge));

        expect(readDraft(KEY_PARTS, { version: DRAFT_VERSIONS.deal })?.data).toEqual(VALID_DEAL_DRAFT);
        expect(actions.openOverlay).not.toHaveBeenCalled();

        state.activeWorkspaceId = 17;
        await mounted.rerender(createElement(DraftResumeBridge));
        await act(async () => vi.advanceTimersByTime(300));
        expect(toasts.info).toHaveBeenCalledTimes(1);
        await act(async () => latestToast().action?.onClick());
        expect(actions.openOverlay).toHaveBeenCalledWith({
            kind: 'create-deal',
            draft: VALID_DEAL_DRAFT,
            restoredDraftGeneration: getDraftKeyGeneration(key),
        });
        await mounted.unmount();
    });
});

describe('deal draft persistence', () => {
    it('persists dirty edits after the debounce and flushes the latest edit on pagehide', async () => {
        const mounted = await render(dealContainer());
        const name = requiredElement(mounted, 'deal-name');
        await changeInput(mounted, name, 'Renewal');

        await act(async () => vi.advanceTimersByTime(DRAFT_DEBOUNCE_MS - 1));
        expect(readDealDraft()).toBeNull();
        await act(async () => vi.advanceTimersByTime(1));
        expect(readDealDraft()?.data).toMatchObject({ name: 'Renewal', pipeline: null, stage: null });

        await changeInput(mounted, name, 'Renewal updated');
        await act(async () => mounted.dispatchWindow('pagehide'));
        expect(readDealDraft()?.data).toMatchObject({ name: 'Renewal updated' });
        await mounted.unmount();
    });

    it('flushes a pending dirty edit on unmount', async () => {
        const mounted = await render(dealContainer());
        await changeInput(mounted, requiredElement(mounted, 'deal-name'), 'Route change');
        expect(readDealDraft()).toBeNull();
        await mounted.unmount();
        expect(readDealDraft()?.data).toMatchObject({ name: 'Route change' });
    });

    it('clears the persisted draft after a successful create', async () => {
        const mounted = await render(dealContainer());
        await changeInput(mounted, requiredElement(mounted, 'deal-name'), 'Won renewal');
        await act(async () => vi.advanceTimersByTime(DRAFT_DEBOUNCE_MS));
        expect(readDealDraft()).not.toBeNull();

        const form = connectedElements(mounted, 'FORM').at(-1);
        if (!form) throw new Error('Expected the deal form');
        await act(async () => {
            mounted.dispatch('submit', form);
            await settle();
        });

        expect(api.createDeal).toHaveBeenCalledWith(expect.objectContaining({
            name: 'Won renewal',
            duplicateReviewToken: 'review-token',
        }), undefined);
        expect(readDealDraft()).toBeNull();
        await mounted.unmount();
    });

    it('clears the persisted draft only after discard is confirmed', async () => {
        const onOpenChange = vi.fn();
        const mounted = await render(dealContainer({ onOpenChange }));
        const name = requiredElement(mounted, 'deal-name');
        await changeInput(mounted, name, 'Discard me');
        await act(async () => vi.advanceTimersByTime(DRAFT_DEBOUNCE_MS));
        expect(readDealDraft()).not.toBeNull();

        const form = ancestor(name, 'FORM');
        const cancel = descendants(form).find((element) => (
            element.tagName === 'BUTTON' && element.textContent === 'cancel'
        ));
        if (!cancel) throw new Error('Expected the deal cancel button');
        await act(async () => mounted.dispatch('click', cancel));
        expect(readDealDraft()).not.toBeNull();
        expect(onOpenChange).not.toHaveBeenCalled();

        await act(async () => latestOpenConfirm().onDiscard());
        expect(readDealDraft()).toBeNull();
        expect(onOpenChange).toHaveBeenCalledWith(false);
        await mounted.unmount();
    });

    it('returns a resumed name-only draft to pristine when its name is cleared', async () => {
        const storage = createStorage();
        const mountedStorage = await render(null, storage);
        const draft = { ...VALID_DEAL_DRAFT, name: 'Name only', value: 0, pipeline: null, stage: null, company: null, expectedCloseDate: '' };
        const key = draftKey(KEY_PARTS);
        writeDraft(key, {
            version: DRAFT_VERSIONS.deal,
            scope: 'global',
            formType: 'deal',
            data: draft,
        });
        const generation = getDraftKeyGeneration(key);
        await mountedStorage.unmount();

        const onOpenChange = vi.fn();
        const mounted = await render(dealContainer({
            initialDraft: draft,
            initialDraftGeneration: generation,
            onOpenChange,
        }), storage);
        const name = requiredElement(mounted, 'deal-name');
        expect(name.value).toBe('Name only');

        await changeInput(mounted, name, '');
        expect(readDealDraft()).toBeNull();
        const currentDialog = dialog.dismissals.at(-1);
        if (!currentDialog) throw new Error('Expected the deal dismissal boundary');
        await act(async () => currentDialog(false));
        expect(dialog.confirms.some((capture) => capture.open)).toBe(false);
        expect(onOpenChange).toHaveBeenCalledWith(false);
        await mounted.unmount();
    });

    it('keeps a restored null pipeline and stage pristine against the container zero baseline', async () => {
        const draft: DealDraft = {
            name: '',
            value: 0,
            currency: 'USD',
            pipeline: null,
            stage: null,
            company: null,
            expectedCloseDate: '',
        };
        const onOpenChange = vi.fn();
        const mounted = await render(dealContainer({
            initialDraft: draft,
            initialDraftGeneration: 0,
            onOpenChange,
        }));

        const currentDialog = dialog.dismissals.at(-1);
        if (!currentDialog) throw new Error('Expected the deal dismissal boundary');
        await act(async () => currentDialog(false));

        expect(dialog.confirms.some((capture) => capture.open)).toBe(false);
        expect(onOpenChange).toHaveBeenCalledWith(false);
        await mounted.unmount();
    });

    it('waits for reference revalidation, then preselects the resolved company immediately', async () => {
        let resolvePipelines: ((pipelines: Pipeline[]) => void) | undefined;
        api.getPipelines.mockReturnValueOnce(new Promise((resolve) => {
            resolvePipelines = resolve;
        }));
        api.getStagesByPipelineId.mockResolvedValueOnce([STAGE]);
        api.getCompaniesByIds.mockResolvedValueOnce([COMPANY]);
        const onDraftMounted = vi.fn();
        const mounted = await render(dealContainer({
            initialDraft: VALID_DEAL_DRAFT,
            initialDraftGeneration: 0,
            onDraftMounted,
        }));

        expect(dealNameInputs(mounted)).toHaveLength(0);
        expect(onDraftMounted).not.toHaveBeenCalled();
        const restoreStatus = connectedElements(mounted).find((element) => (
            element.getAttribute('role') === 'status'
        ));
        expect(restoreStatus?.getAttribute('aria-busy')).toBe('true');
        expect(restoreStatus?.textContent).toBe('restoringDraft');
        expect(dialog.shells.at(-1)).toEqual({ open: true });
        expect(dialog.contents.at(-1)).toEqual({ showCloseButton: false });
        await act(async () => {
            resolvePipelines?.([PIPELINE]);
            await settle();
        });
        expect(connectedElements(mounted).some((element) => element.getAttribute('role') === 'status')).toBe(false);
        expect(onDraftMounted).toHaveBeenCalledOnce();
        expect(requiredElement(mounted, 'deal-name').value).toBe(VALID_DEAL_DRAFT.name);
        expect(connectedElements(mounted).some((element) => (
            element.getAttribute('data-selected-label') === COMPANY.name
        ))).toBe(true);
        await mounted.unmount();
    });

    it('keeps the restored composer open when an unrelated pipeline stage request fails', async () => {
        api.getPipelines.mockResolvedValueOnce([PIPELINE, OTHER_PIPELINE]);
        api.getStagesByPipelineId.mockImplementationOnce(async (pipelineId) => {
            if (pipelineId === PIPELINE.id) return [STAGE];
            throw new Error('unrelated pipeline unavailable');
        }).mockImplementationOnce(async (pipelineId) => {
            if (pipelineId === PIPELINE.id) return [STAGE];
            throw new Error('unrelated pipeline unavailable');
        });
        api.getCompaniesByIds.mockResolvedValueOnce([COMPANY]);
        const onOpenChange = vi.fn();
        const mounted = await render(dealContainer({
            initialDraft: VALID_DEAL_DRAFT,
            initialDraftGeneration: 0,
            onOpenChange,
        }));

        expect(requiredElement(mounted, 'deal-name').value).toBe(VALID_DEAL_DRAFT.name);
        expect(onOpenChange).not.toHaveBeenCalled();
        expect(toasts.error).not.toHaveBeenCalled();
        expect(toasts.warn).not.toHaveBeenCalled();

        const form = connectedElements(mounted, 'FORM').at(-1);
        if (!form) throw new Error('Expected the restored deal form');
        await act(async () => {
            mounted.dispatch('submit', form);
            await settle();
        });
        expect(api.createDeal).toHaveBeenCalledWith(expect.objectContaining({
            pipeline: PIPELINE.id,
            stage: STAGE.id,
            company: COMPANY.id,
        }), undefined);
        await mounted.unmount();
    });

    it('drops a saved stage with a localized warning when its selected pipeline stages cannot load', async () => {
        api.getPipelines.mockResolvedValueOnce([PIPELINE]);
        api.getStagesByPipelineId.mockRejectedValueOnce(new Error('selected pipeline unavailable'));
        api.getCompaniesByIds.mockResolvedValueOnce([COMPANY]);
        const onOpenChange = vi.fn();
        const mounted = await render(dealContainer({
            initialDraft: VALID_DEAL_DRAFT,
            initialDraftGeneration: 0,
            onOpenChange,
        }));

        expect(requiredElement(mounted, 'deal-name').value).toBe(VALID_DEAL_DRAFT.name);
        expect(onOpenChange).not.toHaveBeenCalled();
        expect(toasts.warn).toHaveBeenCalledWith('feedback.restoredDealReferenceUnavailable');

        const form = connectedElements(mounted, 'FORM').at(-1);
        if (!form) throw new Error('Expected the degraded restored deal form');
        await act(async () => {
            mounted.dispatch('submit', form);
            await settle();
        });
        expect(api.createDeal).toHaveBeenCalledWith(expect.objectContaining({
            pipeline: PIPELINE.id,
            stage: null,
            company: COMPANY.id,
        }), undefined);
        await mounted.unmount();
    });

    it('drops unresolved pipeline, stage, and company ids before the form can submit', async () => {
        const mounted = await render(dealContainer({
            initialDraft: VALID_DEAL_DRAFT,
            initialDraftGeneration: 0,
        }));
        const form = connectedElements(mounted, 'FORM').at(-1);
        if (!form) throw new Error('Expected the restored deal form');

        await act(async () => {
            mounted.dispatch('submit', form);
            await settle();
        });

        expect(api.createDeal).toHaveBeenCalledWith(expect.objectContaining({
            pipeline: null,
            stage: null,
            company: null,
        }), undefined);
        expect(toasts.warn).toHaveBeenCalledWith('feedback.restoredDealReferenceUnavailable');
        await mounted.unmount();
    });

    it('keeps the embedded Quick Create host persistence-free and leaves the overlay draft untouched', async () => {
        const storage = createStorage();
        const overlay = await render(dealContainer(), storage);
        await changeInput(overlay, requiredElement(overlay, 'deal-name'), 'Overlay draft');
        await act(async () => vi.advanceTimersByTime(DRAFT_DEBOUNCE_MS));
        expect(readDealDraft()?.data).toMatchObject({ name: 'Overlay draft' });
        await overlay.unmount();

        const embedded = await render(dealContainer({
            embedded: true,
            draftPersistence: false,
        }), storage);
        await changeInput(embedded, requiredElement(embedded, 'deal-name'), 'Embedded edit');
        await act(async () => vi.advanceTimersByTime(DRAFT_DEBOUNCE_MS));
        await act(async () => embedded.dispatchWindow('pagehide'));

        expect(readDealDraft()?.data).toMatchObject({ name: 'Overlay draft' });
        await embedded.unmount();
        expect(readDealDraft()?.data).toMatchObject({ name: 'Overlay draft' });
    });

    it('renders the origin/main mobile Quick Create classes and morph durations after extraction', async () => {
        state.reducedMotion = false;
        const mounted = await render(createElement(MobileCreateFlow, {
            actions: [DEAL_ACTION],
            context: ACTION_CONTEXT,
            currentUserId: 7,
            onFallback: vi.fn(),
            onClose: vi.fn(),
            onPendingChange: vi.fn(),
        }));
        const option = requiredButton(mounted, (button) => button.getAttribute('role') === 'option');
        const close = requiredButton(mounted, (button) => (
            button.getAttribute('aria-label') === 'quickCreate.close'
        ));
        const header = ancestor(close, 'HEADER');
        const selector = ancestor(option, 'DIV');
        const optionSpans = descendants(option).filter((element) => element.tagName === 'SPAN');

        expect({
            root: ancestor(header, 'DIV').getAttribute('class'),
            header: header.getAttribute('class'),
            close: close.getAttribute('class'),
            selector: selector.getAttribute('class'),
            option: option.getAttribute('class'),
            optionIcon: optionSpans[0]?.getAttribute('class'),
            optionLabel: optionSpans[1]?.getAttribute('class'),
        }).toEqual({
            root: 'flex min-h-0 flex-1 flex-col',
            header: 'flex-row items-center gap-2 border-b border-border px-4 py-3.5',
            close: 'grid size-7 shrink-0 place-items-center rounded-md text-muted-foreground transition-colors hover:bg-muted hover:text-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-brand disabled:opacity-50',
            selector: 'grid gap-1',
            option: 'group flex items-center gap-3 rounded-xl px-2.5 py-2.5 text-left transition-colors duration-(--motion-micro) hover:bg-muted focus-visible:bg-muted focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-brand',
            optionIcon: 'grid size-8 shrink-0 place-items-center rounded-lg bg-muted text-muted-foreground ring-1 ring-border transition-colors group-hover:bg-brand-light group-hover:text-brand-dark group-hover:ring-transparent group-focus-visible:bg-brand-light group-focus-visible:text-brand-dark',
            optionLabel: 'flex-1 text-sm font-medium text-foreground',
        });

        const fullMotionTransitions = connectedElements(mounted, 'DIV')
            .map((element) => element.getAttribute('data-motion-transition'))
            .filter((value): value is string => value !== null)
            .map((value) => JSON.parse(value) as Record<string, unknown>);
        expect(fullMotionTransitions).toContainEqual(expect.objectContaining({
            opacity: expect.objectContaining({ duration: 0.16 }),
            filter: expect.objectContaining({ duration: 0.16 }),
        }));

        await act(async () => mounted.dispatch('click', option));
        const back = requiredButton(mounted, (button) => (
            button.getAttribute('aria-label') === 'quickCreate.back'
        ));
        expect(back.getAttribute('class')).toBe(close.getAttribute('class'));
        await mounted.unmount();

        state.reducedMotion = true;
        const reduced = await render(createElement(MobileCreateFlow, {
            actions: [DEAL_ACTION],
            context: ACTION_CONTEXT,
            currentUserId: 7,
            onFallback: vi.fn(),
            onClose: vi.fn(),
            onPendingChange: vi.fn(),
        }));
        const reducedTransitions = connectedElements(reduced, 'DIV')
            .map((element) => element.getAttribute('data-motion-transition'))
            .filter((value): value is string => value !== null)
            .map((value) => JSON.parse(value));
        expect(reducedTransitions).toContainEqual({ duration: 0.12 });
        await reduced.unmount();
    });

    it('guards a dirty embedded Quick Create deal across Close, Back, and drawer dismissal', async () => {
        const onClose = vi.fn();
        const flowRef = createRef<MobileCreateFlowHandle>();
        const mounted = await render(createElement(MobileCreateFlow, {
            ref: flowRef,
            actions: [DEAL_ACTION],
            context: ACTION_CONTEXT,
            currentUserId: 7,
            onFallback: vi.fn(),
            onClose,
            onPendingChange: vi.fn(),
        }));

        await act(async () => mounted.dispatch('click', requiredButton(mounted, (button) => (
            button.getAttribute('role') === 'option'
        ))));
        const name = requiredElement(mounted, 'deal-name');
        await changeInput(mounted, name, 'Embedded renewal');

        await act(async () => mounted.dispatch('click', requiredButton(mounted, (button) => (
            button.getAttribute('aria-label') === 'quickCreate.close'
        ))));
        expect(onClose).not.toHaveBeenCalled();
        await act(async () => latestOpenConfirm().onKeepEditing());
        expect(requiredElement(mounted, 'deal-name').value).toBe('Embedded renewal');

        await act(async () => mounted.dispatch('click', requiredButton(mounted, (button) => (
            button.getAttribute('aria-label') === 'quickCreate.back'
        ))));
        expect(dealNameInputs(mounted)).toHaveLength(1);
        await act(async () => latestOpenConfirm().onDiscard());
        expect(dealNameInputs(mounted)).toHaveLength(0);

        await act(async () => mounted.dispatch('click', requiredButton(mounted, (button) => (
            button.getAttribute('role') === 'option'
        ))));
        expect(requiredElement(mounted, 'deal-name').value).toBe('');
        await changeInput(mounted, requiredElement(mounted, 'deal-name'), 'Drawer dismissal');

        await act(async () => flowRef.current?.requestClose());
        expect(onClose).not.toHaveBeenCalled();
        await act(async () => latestOpenConfirm().onKeepEditing());
        expect(requiredElement(mounted, 'deal-name').value).toBe('Drawer dismissal');

        await act(async () => flowRef.current?.requestClose());
        await act(async () => latestOpenConfirm().onDiscard());
        expect(onClose).toHaveBeenCalledOnce();
        expect(readDealDraft()).toBeNull();
        await mounted.unmount();
    });
});

beforeEach(() => {
    vi.useFakeTimers();
    vi.clearAllMocks();
    clearAllDrafts();
    state.activeWorkspaceId = 17;
    state.switching = false;
    state.reducedMotion = true;
    dialog.confirms.length = 0;
    dialog.dismissals.length = 0;
    dialog.shells.length = 0;
    dialog.contents.length = 0;
    api.createDeal.mockResolvedValue(undefined);
    api.getCompaniesByIds.mockResolvedValue([]);
    api.getCompaniesPage.mockResolvedValue({ items: [], total: 0 });
    api.getPipelines.mockResolvedValue([]);
    api.getStagesByPipelineId.mockResolvedValue([]);
    vi.stubGlobal('ResizeObserver', class {
        observe() {}
        unobserve() {}
        disconnect() {}
    });
});

afterEach(() => {
    vi.useRealTimers();
    vi.unstubAllGlobals();
});
