import { act, createElement, useState, type ReactNode } from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import ActionOverlayHost from '@/app/components/actions/ActionOverlayHost';
import RadarBoard from '@/app/components/radar/RadarBoard';
import type { OverlayRequest, RadarTaskInvocation } from '@/app/lib/actions/types';
import {
    createRadarTaskSignalStore,
    releaseActiveRadarTask,
    type RadarTaskSignalStore,
} from '@/app/lib/radar';
import type { CreateTaskPayload, RadarPayload, RadarSignal, User } from '@/app/lib/types';
import {
    createCloseCompletionGate,
    reduceOverlayRetention,
} from '@/lib/overlay-lifecycle';

type CapturedProps = Record<string, unknown>;

type CaptureState = {
    nextDynamicIndex: number;
    dynamicProps: Map<number, CapturedProps>;
    dynamicUnmounts: Map<number, number>;
    failedDynamicIndices: Set<number>;
    importProps: Map<string, CapturedProps>;
    importUnmounts: Map<string, number>;
    radarCardProps: Map<number, CapturedProps>;
    radarCardRenders: number;
};

const captures = vi.hoisted<CaptureState>(() => ({
    nextDynamicIndex: 0,
    dynamicProps: new Map(),
    dynamicUnmounts: new Map(),
    failedDynamicIndices: new Set(),
    importProps: new Map(),
    importUnmounts: new Map(),
    radarCardProps: new Map(),
    radarCardRenders: 0,
}));

const api = vi.hoisted(() => ({
    createRadarTask: vi.fn(),
    createTask: vi.fn(),
    dismissRadarSignal: vi.fn(),
    followRadarSignal: vi.fn(),
    getContactById: vi.fn(),
    getContacts: vi.fn(),
    getDealById: vi.fn(),
    getDeals: vi.fn(),
    getRadar: vi.fn(),
    getRadarContext: vi.fn(),
    getUsers: vi.fn(),
    isFieldError: vi.fn(() => false),
    snoozeRadarSignal: vi.fn(),
}));

const actions = vi.hoisted(() => ({ run: vi.fn() }));
const navigation = vi.hoisted(() => ({
    refresh: vi.fn(),
    searchParams: { get: vi.fn(() => null) },
}));
const translate = vi.hoisted(() => (
    key: string,
    values?: Record<string, string | number>,
) => values?.subject === undefined ? key : `${key}:${values.subject}`);

vi.mock('next/dynamic', async () => {
    const React = await import('react');
    return {
        default: () => {
            const index = captures.nextDynamicIndex;
            captures.nextDynamicIndex += 1;
            return function CapturedDynamic(props: CapturedProps) {
                captures.dynamicProps.set(index, props);
                React.useEffect(() => () => {
                    captures.dynamicUnmounts.set(
                        index,
                        (captures.dynamicUnmounts.get(index) ?? 0) + 1,
                    );
                }, []);
                if (captures.failedDynamicIndices.has(index)) {
                    throw new Error(`chunk ${index} failed`);
                }
                return null;
            };
        },
    };
});

vi.mock('next/navigation', () => ({
    useRouter: () => navigation,
    useSearchParams: () => navigation.searchParams,
}));

vi.mock('next-intl', () => ({
    useLocale: () => 'en',
    useTranslations: () => translate,
}));

vi.mock('@/app/lib/api', () => {
    class ApiError extends Error {
        readonly status: number;

        constructor(status: number) {
            super(`API ${status}`);
            this.status = status;
        }
    }

    return { ApiError, ...api };
});

vi.mock('@/app/hooks/useActions', () => ({
    useActions: () => actions,
}));

vi.mock('@/app/hooks/useWorkspace', () => ({
    useWorkspace: () => ({ activeWorkspaceId: 17 }),
}));

vi.mock('@/app/hooks/useFormDraft', () => ({
    useFormDraft: () => ({ persist: vi.fn(), clear: vi.fn() }),
}));

vi.mock('@/app/hooks/useUnsavedChangesGuard', () => ({
    useUnsavedChangesGuard: ({ onClose }: { onClose: () => void }) => ({
        onOpenChange: (open: boolean) => {
            if (!open) onClose();
        },
        requestClose: onClose,
        confirm: {
            open: false,
            onKeepEditing: vi.fn(),
            onDiscard: onClose,
        },
    }),
}));

vi.mock('@/app/hooks/useOwnedUrlParams', () => ({
    useOwnedUrlParams: () => undefined,
}));

vi.mock('@/app/hooks/usePermissions', () => ({
    usePermissionCheck: () => 'granted',
    usePermissionsRefresh: () => vi.fn(),
}));

vi.mock('@/app/lib/record-mutation-events', () => ({
    publishRecordMutation: vi.fn(),
}));

vi.mock('@/app/lib/toast', () => ({
    toastError: vi.fn(),
    toastSuccess: vi.fn(),
    toastWarn: vi.fn(),
}));

vi.mock('@/app/components/SectionUnavailable', () => ({ default: () => null }));
vi.mock('@/app/components/EmptyState', () => ({ EmptyState: () => null }));
vi.mock('@/app/components/ConfirmDiscardDialog', () => ({ default: () => null }));
vi.mock('@/app/components/radar/RadarSnoozeDialog', () => ({ default: () => null }));
vi.mock('@/app/components/activity/notes/MentionEditor', () => ({ default: () => null }));
vi.mock('@/app/components/activity/notes/commands/slashCommandRegistry', () => ({
    ENTITY_COMMANDS: [],
}));
vi.mock('@/components/ui/dialog-status-cover', () => ({
    DialogStatusCover: () => null,
    resolveDialogStatus: () => 'idle',
    fieldInputClass: '',
    fieldLeadIconClass: '',
}));
vi.mock('@/components/ui/responsive-dialog', () => ({
    ResponsiveDialog: ({ children }: { children: ReactNode }) => children,
    ResponsiveDialogContent: ({ children }: { children: ReactNode }) => children,
    ResponsiveDialogTitle: ({ children }: { children: ReactNode }) => children,
    ResponsiveDialogDescription: ({ children }: { children: ReactNode }) => children,
}));
vi.mock('@/components/ui/input', () => ({ Input: () => null }));
vi.mock('@/components/ui/select', () => ({
    Select: () => null,
    SelectContent: () => null,
    SelectItem: () => null,
    SelectTrigger: () => null,
    SelectValue: () => null,
}));

vi.mock('@/app/components/radar/RadarSignalCard', () => ({
    default: (props: CapturedProps) => {
        const signal = props.signal;
        if (
            typeof signal === 'object'
            && signal !== null
            && 'id' in signal
            && typeof signal.id === 'number'
        ) {
            captures.radarCardProps.set(signal.id, props);
        }
        captures.radarCardRenders += 1;
        return null;
    },
}));

vi.mock('@/app/components/import/LazyImportDialog', async () => {
    const React = await import('react');
    return {
        default: function CapturedImportDialog(props: CapturedProps) {
            const entity = props.entity;
            const key = typeof entity === 'string' ? entity : 'unknown';
            captures.importProps.set(key, props);
            React.useEffect(() => () => {
                captures.importUnmounts.set(
                    key,
                    (captures.importUnmounts.get(key) ?? 0) + 1,
                );
            }, [key]);
            return null;
        },
    };
});

function installMinimalDocument() {
    class HtmlIFrameElement {}

    const setTimeoutSpy = vi.fn(() => 1);
    const clearTimeoutSpy = vi.fn();
    const documentTarget = {
        nodeType: 9,
        activeElement: null,
        addEventListener: vi.fn(),
        removeEventListener: vi.fn(),
        createElement: vi.fn(() => containerTarget),
        createElementNS: vi.fn(() => containerTarget),
        createTextNode: vi.fn((value: string) => ({
            nodeType: 3,
            nodeName: '#text',
            nodeValue: value,
            parentNode: null,
            ownerDocument: documentTarget,
        })),
        getElementById: vi.fn(() => null),
    };
    const windowTarget = {
        document: documentTarget,
        event: undefined,
        HTMLIFrameElement: HtmlIFrameElement,
        addEventListener: vi.fn(),
        removeEventListener: vi.fn(),
        setTimeout: setTimeoutSpy,
        clearTimeout: clearTimeoutSpy,
    };
    const containerTarget = {
        nodeType: 1,
        tagName: 'DIV',
        nodeName: 'DIV',
        namespaceURI: 'http://www.w3.org/1999/xhtml',
        ownerDocument: documentTarget,
        firstChild: null,
        lastChild: null,
        parentNode: null,
        textContent: '',
        style: {},
        addEventListener: vi.fn(),
        removeEventListener: vi.fn(),
        appendChild: vi.fn(),
        insertBefore: vi.fn(),
        removeChild: vi.fn(),
        setAttribute: vi.fn(),
        removeAttribute: vi.fn(),
    };
    Object.assign(documentTarget, {
        defaultView: windowTarget,
        documentElement: containerTarget,
        body: containerTarget,
    });
    vi.stubGlobal('window', windowTarget);
    vi.stubGlobal('document', documentTarget);
    vi.stubGlobal('requestAnimationFrame', vi.fn((callback: FrameRequestCallback) => {
        callback(0);
        return 1;
    }));
    vi.stubGlobal('IS_REACT_ACT_ENVIRONMENT', true);
    return {
        container: document.createElement('div'),
        setTimeoutSpy,
    };
}

type InteractiveListener = {
    callback: (event: unknown) => void;
    capture: boolean;
};

type InteractiveText = {
    nodeType: 3;
    nodeName: '#text';
    nodeValue: string;
    parentNode: InteractiveElement | null;
    ownerDocument: InteractiveDocument;
};

type InteractiveElement = {
    nodeType: 1;
    tagName: string;
    nodeName: string;
    namespaceURI: string;
    ownerDocument: InteractiveDocument;
    parentNode: InteractiveElement | null;
    childNodes: Array<InteractiveElement | InteractiveText>;
    attributes: Map<string, string>;
    listeners: Map<string, InteractiveListener[]>;
    style: Record<string, unknown>;
    disabled?: boolean;
    id?: string;
    type?: string;
    value?: string;
    addEventListener: (type: string, callback: (event: unknown) => void, options?: unknown) => void;
    removeEventListener: (type: string, callback: (event: unknown) => void) => void;
    appendChild: (child: InteractiveElement | InteractiveText) => InteractiveElement | InteractiveText;
    insertBefore: (
        child: InteractiveElement | InteractiveText,
        before: InteractiveElement | InteractiveText | null,
    ) => InteractiveElement | InteractiveText;
    removeChild: (child: InteractiveElement | InteractiveText) => InteractiveElement | InteractiveText;
    setAttribute: (name: string, value: string) => void;
    removeAttribute: (name: string) => void;
    getAttribute: (name: string) => string | null;
    focus: () => void;
};

type InteractiveDocument = {
    nodeType: 9;
    activeElement: InteractiveElement | null;
    defaultView?: object;
    documentElement?: InteractiveElement;
    body?: InteractiveElement;
    addEventListener: (type: string, callback: (event: unknown) => void, options?: unknown) => void;
    removeEventListener: (type: string, callback: (event: unknown) => void) => void;
    createElement: (tagName: string) => InteractiveElement;
    createElementNS: (namespace: string, tagName: string) => InteractiveElement;
    createTextNode: (value: string) => InteractiveText;
    getElementById: (id: string) => InteractiveElement | null;
};

function eventUsesCapture(options: unknown): boolean {
    if (typeof options === 'boolean') return options;
    if (typeof options === 'object' && options !== null && 'capture' in options) {
        return options.capture === true;
    }
    return false;
}

function installInteractiveDocument() {
    class HtmlIFrameElement {}

    const elements: InteractiveElement[] = [];
    const documentListeners = new Map<string, InteractiveListener[]>();

    function addListener(
        listeners: Map<string, InteractiveListener[]>,
        type: string,
        callback: (event: unknown) => void,
        options?: unknown,
    ) {
        const current = listeners.get(type) ?? [];
        current.push({ callback, capture: eventUsesCapture(options) });
        listeners.set(type, current);
    }

    function removeListener(
        listeners: Map<string, InteractiveListener[]>,
        type: string,
        callback: (event: unknown) => void,
    ) {
        listeners.set(type, (listeners.get(type) ?? []).filter(
            (listener) => listener.callback !== callback,
        ));
    }

    function createInteractiveElement(tagName: string, namespaceURI = 'http://www.w3.org/1999/xhtml') {
        const childNodes: Array<InteractiveElement | InteractiveText> = [];
        const attributes = new Map<string, string>();
        const listeners = new Map<string, InteractiveListener[]>();
        const element: InteractiveElement = {
            nodeType: 1,
            tagName: tagName.toUpperCase(),
            nodeName: tagName.toUpperCase(),
            namespaceURI,
            ownerDocument: documentTarget,
            parentNode: null,
            childNodes,
            attributes,
            listeners,
            style: {},
            addEventListener: (type, callback, options) => {
                addListener(listeners, type, callback, options);
            },
            removeEventListener: (type, callback) => {
                removeListener(listeners, type, callback);
            },
            appendChild: (child) => {
                child.parentNode = element;
                childNodes.push(child);
                return child;
            },
            insertBefore: (child, before) => {
                child.parentNode = element;
                const index = before === null ? -1 : childNodes.indexOf(before);
                if (index < 0) childNodes.push(child);
                else childNodes.splice(index, 0, child);
                return child;
            },
            removeChild: (child) => {
                const index = childNodes.indexOf(child);
                if (index >= 0) childNodes.splice(index, 1);
                child.parentNode = null;
                return child;
            },
            setAttribute: (name, value) => {
                attributes.set(name, String(value));
                if (name === 'id') element.id = String(value);
                if (name === 'type') element.type = String(value);
                if (name === 'disabled') element.disabled = true;
            },
            removeAttribute: (name) => {
                attributes.delete(name);
                if (name === 'disabled') element.disabled = false;
            },
            getAttribute: (name) => attributes.get(name) ?? null,
            focus: () => {
                documentTarget.activeElement = element;
            },
        };
        Object.defineProperties(element, {
            firstChild: { get: () => childNodes[0] ?? null },
            lastChild: { get: () => childNodes.at(-1) ?? null },
            textContent: {
                get: () => '',
                set: () => {
                    childNodes.forEach((child) => {
                        child.parentNode = null;
                    });
                    childNodes.length = 0;
                },
            },
        });
        elements.push(element);
        return element;
    }

    const documentTarget: InteractiveDocument = {
        nodeType: 9,
        activeElement: null,
        addEventListener: (type, callback, options) => {
            addListener(documentListeners, type, callback, options);
        },
        removeEventListener: (type, callback) => {
            removeListener(documentListeners, type, callback);
        },
        createElement: (tagName) => createInteractiveElement(tagName),
        createElementNS: (namespace, tagName) => createInteractiveElement(tagName, namespace),
        createTextNode: (value) => ({
            nodeType: 3,
            nodeName: '#text',
            nodeValue: value,
            parentNode: null,
            ownerDocument: documentTarget,
        }),
        getElementById: (id) => elements.find((element) => element.id === id) ?? null,
    };
    const documentElement = createInteractiveElement('html');
    const body = createInteractiveElement('body');
    documentElement.appendChild(body);
    const windowTarget = {
        document: documentTarget,
        event: undefined,
        HTMLIFrameElement: HtmlIFrameElement,
        addEventListener: vi.fn(),
        removeEventListener: vi.fn(),
        setTimeout: vi.fn(() => 1),
        clearTimeout: vi.fn(),
    };
    Object.assign(documentTarget, {
        defaultView: windowTarget,
        documentElement,
        body,
    });
    vi.stubGlobal('window', windowTarget);
    vi.stubGlobal('document', documentTarget);
    vi.stubGlobal('requestAnimationFrame', vi.fn((callback: FrameRequestCallback) => {
        callback(0);
        return 1;
    }));
    vi.stubGlobal('IS_REACT_ACT_ENVIRONMENT', true);
    const container = document.createElement('div');
    const containerNode = elements.at(-1);
    if (!containerNode) throw new Error('Interactive root was not created');
    body.appendChild(containerNode);

    return {
        container,
        elements,
        dispatch: (type: string, target: InteractiveElement) => {
            let defaultPrevented = false;
            let propagationStopped = false;
            const event = {
                type,
                target,
                currentTarget: containerNode,
                bubbles: true,
                cancelable: true,
                defaultPrevented,
                returnValue: true,
                cancelBubble: false,
                timeStamp: Date.now(),
                preventDefault: () => {
                    defaultPrevented = true;
                    event.defaultPrevented = true;
                    event.returnValue = false;
                },
                stopPropagation: () => {
                    propagationStopped = true;
                    event.cancelBubble = true;
                },
                stopImmediatePropagation: () => {
                    propagationStopped = true;
                    event.cancelBubble = true;
                },
                composedPath: () => {
                    const path: InteractiveElement[] = [];
                    let current: InteractiveElement | null = target;
                    while (current !== null) {
                        path.push(current);
                        current = current.parentNode;
                    }
                    return path;
                },
            };
            const listeners = containerNode.listeners.get(type) ?? [];
            for (const listener of listeners.filter((candidate) => candidate.capture)) {
                listener.callback(event);
                if (propagationStopped) return !defaultPrevented;
            }
            for (const listener of listeners.filter((candidate) => !candidate.capture)) {
                listener.callback(event);
                if (propagationStopped) break;
            }
            return !defaultPrevented;
        },
    };
}

function signal(overrides: Partial<RadarSignal> = {}): RadarSignal {
    return {
        id: 1,
        family: 'relationship_decay',
        subject: { type: 'person', id: 10, label: 'Ada Lovelace' },
        priority: 'cooling',
        state: 'active',
        snoozeUntil: null,
        taskId: null,
        version: '1:0',
        evidenceAsOf: '2026-08-08T12:00:00Z',
        stale: false,
        evidence: [{
            type: 'relationship_temperature',
            parameters: { trend: 'cooling' },
            references: [{ type: 'person', id: 10 }],
        }],
        rank: {
            position: 1,
            rule: 'priority_then_source_strength_then_subject',
            factors: [{ key: 'priority', direction: 'ascending', value: 'cooling' }],
        },
        ...overrides,
    };
}

function warmPathSignal(overrides: Partial<RadarSignal> = {}): RadarSignal {
    return signal({
        family: 'warm_path',
        priority: 'opportunity',
        evidence: [{
            type: 'warm_path',
            parameters: { bridgePersonId: 20, bridgeName: 'Grace Hopper' },
            references: [
                { type: 'person', id: 10 },
                { type: 'person', id: 20 },
            ],
        }],
        ...overrides,
    });
}

function payload(items: RadarSignal[]): RadarPayload {
    return {
        items,
        families: [
            { family: 'relationship_decay', status: 'available' },
            { family: 'deal_risk', status: 'available' },
            { family: 'warm_path', status: 'available' },
        ],
        counts: { total: items.length },
        asOf: '2026-08-08T12:00:00Z',
        partialFailure: false,
    };
}

function user(): User {
    return {
        id: 7,
        username: 'operator',
        displayName: 'Operator',
        email: 'operator@example.com',
        createdAt: '2026-08-08T00:00:00Z',
        updatedAt: '2026-08-08T00:00:00Z',
        timezone: 'UTC',
        locale: 'en',
    };
}

function taskSignalStore(): RadarTaskSignalStore {
    return {
        getSnapshot: () => ({ status: 'changed' }),
        subscribe: () => () => undefined,
        refresh: () => undefined,
    };
}

function requiredProps(value: CapturedProps | undefined, label: string): CapturedProps {
    if (!value) throw new Error(`${label} did not render`);
    return value;
}

function invoke(props: CapturedProps, name: string, ...args: unknown[]): unknown {
    const callback = props[name];
    if (typeof callback !== 'function') throw new Error(`${name} is not callable`);
    return callback(...args);
}

function deferred<T>() {
    let resolvePromise: ((value: T) => void) | undefined;
    const promise = new Promise<T>((resolve) => {
        resolvePromise = resolve;
    });
    return {
        promise,
        resolve: (value: T) => {
            if (!resolvePromise) throw new Error('Deferred promise is unavailable');
            resolvePromise(value);
        },
    };
}

async function flushUpdates() {
    await act(async () => {
        await Promise.resolve();
        await Promise.resolve();
    });
}

async function renderOverlayHost() {
    const { container } = installMinimalDocument();
    const { createRoot } = await import('react-dom/client');
    const root = createRoot(container, { onCaughtError: vi.fn() });
    const onClose = vi.fn();
    let update: ((next: { overlay: OverlayRequest | null; generation: number | null }) => void) | undefined;

    function Controller() {
        const [input, setInput] = useState<{
            overlay: OverlayRequest | null;
            generation: number | null;
        }>({ overlay: null, generation: null });
        update = setInput;
        return createElement(ActionOverlayHost, {
            overlay: input.overlay,
            overlayGeneration: input.generation,
            originWorkspaceId: 17,
            requestSignal: null,
            user: user(),
            onClose: () => {
                onClose();
                setInput({ overlay: null, generation: null });
            },
        });
    }

    await act(async () => {
        root.render(createElement(Controller));
    });

    return {
        onClose,
        show: async (overlay: OverlayRequest | null, generation: number | null) => {
            if (!update) throw new Error('Overlay controller did not render');
            await act(async () => {
                update?.({ overlay, generation });
                await Promise.resolve();
                await Promise.resolve();
            });
        },
        unmount: async () => act(async () => root.unmount()),
    };
}

async function renderRadarBoard(initialPayload: RadarPayload) {
    const installed = installMinimalDocument();
    const { createRoot } = await import('react-dom/client');
    const root = createRoot(installed.container);
    await act(async () => {
        root.render(createElement(RadarBoard, { initialPayload }));
        await Promise.resolve();
        await Promise.resolve();
    });
    return {
        ...installed,
        unmount: async () => act(async () => root.unmount()),
    };
}

beforeEach(() => {
    captures.dynamicProps.clear();
    captures.dynamicUnmounts.clear();
    captures.failedDynamicIndices.clear();
    captures.importProps.clear();
    captures.importUnmounts.clear();
    captures.radarCardProps.clear();
    captures.radarCardRenders = 0;
    vi.clearAllMocks();
    api.getContacts.mockResolvedValue([]);
    api.getDeals.mockResolvedValue([]);
    api.getUsers.mockResolvedValue([]);
});

afterEach(() => {
    vi.unstubAllGlobals();
});

describe('Radar action integration', () => {
    it('dispatches task creation and record opening from rendered Radar cards', async () => {
        const currentPayload = payload([warmPathSignal()]);
        api.getRadar.mockResolvedValue(currentPayload);
        api.getRadarContext.mockResolvedValue({
            type: 'person',
            id: 10,
            label: 'Ada Lovelace',
            href: '/records/people/10',
        });
        actions.run.mockResolvedValue({ status: 'completed' });
        const board = await renderRadarBoard(currentPayload);
        const card = requiredProps(captures.radarCardProps.get(1), 'Radar card');

        await act(async () => {
            invoke(card, 'onCreateTask');
            await Promise.resolve();
        });
        expect(actions.run).toHaveBeenCalledWith('create.task', expect.objectContaining({
            source: 'menu',
            record: { type: 'person', id: 10, label: 'Ada Lovelace' },
            radarTask: expect.objectContaining({
                signalId: 1,
                mode: 'warm_path',
                bridgePersonId: 20,
            }),
        }));

        await act(async () => {
            invoke(card, 'onOpenContext');
            await Promise.resolve();
            await Promise.resolve();
        });
        expect(actions.run).toHaveBeenCalledWith('record.open', {
            source: 'menu',
            record: { type: 'person', id: 10, label: 'Ada Lovelace' },
        });
        await board.unmount();
    });

    it('submits the rendered shared task composer through the Radar endpoint', async () => {
        const currentSignal = warmPathSignal();
        const createdSignal = warmPathSignal({ taskId: 91, version: '1:1' });
        const onCreated = vi.fn();
        const radarTask: RadarTaskInvocation = {
            signalId: currentSignal.id,
            draft: { description: 'Ask Grace for an introduction' },
            mode: 'warm_path',
            bridgePersonId: 20,
            signalState: createRadarTaskSignalStore(currentSignal),
            onRefresh: vi.fn(),
            onDraftChange: vi.fn(),
            onDraftClear: vi.fn(),
            onCreated,
            onClosed: vi.fn(),
        };
        api.createRadarTask.mockResolvedValue(createdSignal);
        const host = await renderOverlayHost();
        await host.show({ kind: 'create-task', radarTask }, 1);
        const task = requiredProps(captures.dynamicProps.get(0), 'Task dialog');
        const taskPayload: CreateTaskPayload = {
            description: 'Ask Grace for an introduction',
            assignedToId: 7,
        };

        const submission = invoke(task, 'createRequest', taskPayload);
        if (!(submission instanceof Promise)) throw new Error('Task submission was not asynchronous');
        await expect(submission).resolves.toEqual(createdSignal);
        expect(api.createRadarTask).toHaveBeenCalledWith(
            1,
            '1:0',
            { ...taskPayload, bridgePersonId: 20 },
            undefined,
        );
        expect(task.compact).toBe(true);
        expect(task.hideLinks).toBe(true);
        expect(task.draftPersistence).toBe(false);
        expect(task.preserveDraftOnClose).toBe(true);
        expect(onCreated).toHaveBeenCalledWith(createdSignal);
        await host.unmount();
    });

    it('uses the injected Radar request when the real task dialog is submitted', async () => {
        const taskModule = await vi.importActual<
            typeof import('@/app/components/activity/tasks/TaskDialog')
        >('@/app/components/activity/tasks/TaskDialog');
        const installed = installInteractiveDocument();
        const { createRoot } = await import('react-dom/client');
        const root = createRoot(installed.container, { onCaughtError: vi.fn() });
        const abortController = new AbortController();
        const createRequest = vi.fn(async () => {
            abortController.abort();
        });
        const onDraftMounted = vi.fn();

        await act(async () => {
            root.render(createElement(taskModule.default, {
                open: true,
                onOpenChange: vi.fn(),
                persons: [],
                deals: [],
                users: [user()],
                currentUserId: 7,
                defaultPerson: null,
                defaultDeal: null,
                defaultDueDate: '',
                defaultDescription: 'Ask Grace for an introduction',
                requestInit: { signal: abortController.signal },
                createRequest,
                compact: true,
                hideLinks: true,
                draftPersistence: false,
                onDraftMounted,
            }));
        });
        const form = installed.elements.find(
            (element) => element.tagName === 'FORM' && element.parentNode !== null,
        );
        if (!form) throw new Error('Task form did not render');

        await act(async () => {
            installed.dispatch('submit', form);
            await Promise.resolve();
            await Promise.resolve();
        });

        expect(createRequest).toHaveBeenCalledWith({
            description: 'Ask Grace for an introduction',
            dueDate: undefined,
            assignedToId: 7,
            personId: undefined,
            dealId: undefined,
        }, { signal: abortController.signal });
        expect(onDraftMounted).toHaveBeenCalledOnce();
        await act(async () => root.unmount());
    });

    it('disables every rendered card while a Radar mutation is pending', async () => {
        const first = signal();
        const second = signal({
            id: 2,
            subject: { type: 'person', id: 11, label: 'Grace Hopper' },
        });
        const currentPayload = payload([first, second]);
        const follow = deferred<RadarSignal>();
        api.getRadar.mockResolvedValue(currentPayload);
        api.followRadarSignal.mockReturnValue(follow.promise);
        const board = await renderRadarBoard(currentPayload);

        await act(async () => {
            invoke(requiredProps(captures.radarCardProps.get(1), 'First Radar card'), 'onFollow');
            await Promise.resolve();
        });
        expect(requiredProps(captures.radarCardProps.get(1), 'First Radar card').busy).toBe(true);
        expect(requiredProps(captures.radarCardProps.get(2), 'Second Radar card').busy).toBe(true);

        await act(async () => {
            follow.resolve({ ...first, state: 'followed', version: '1:1' });
            await follow.promise;
        });
        expect(requiredProps(captures.radarCardProps.get(1), 'First Radar card').busy).toBe(false);
        expect(requiredProps(captures.radarCardProps.get(2), 'Second Radar card').busy).toBe(false);
        await board.unmount();
    });

    it('disables every real Radar card action while the board-wide gate is occupied', async () => {
        const cardModule = await vi.importActual<
            typeof import('@/app/components/radar/RadarSignalCard')
        >('@/app/components/radar/RadarSignalCard');
        const installed = installInteractiveDocument();
        const { createRoot } = await import('react-dom/client');
        const root = createRoot(installed.container, { onCaughtError: vi.fn() });
        const callbacks = {
            onSnoozeOpenChange: vi.fn(),
            onFollow: vi.fn(),
            onSnooze: vi.fn(),
            onDismiss: vi.fn(),
            onCreateTask: vi.fn(),
            onRefreshEvidence: vi.fn(),
            onOpenContext: vi.fn(),
        };

        await act(async () => {
            root.render(createElement(cardModule.default, {
                signal: signal(),
                pageAsOf: '2026-08-08T12:00:00Z',
                freshnessStatus: 'current',
                busy: true,
                snoozeOpen: false,
                ...callbacks,
            }));
        });
        const actionButtons = installed.elements.filter(
            (element) => element.tagName === 'BUTTON' && element.parentNode !== null,
        );
        expect(actionButtons).toHaveLength(5);
        expect(actionButtons.every((button) => button.disabled === true)).toBe(true);

        await act(async () => root.unmount());
    });

    it('does not schedule refresh work when an in-flight rendered session resolves after teardown', async () => {
        const currentPayload = payload([signal()]);
        const refresh = deferred<RadarPayload>();
        api.getRadar.mockReturnValue(refresh.promise);
        const board = await renderRadarBoard(currentPayload);

        await board.unmount();
        refresh.resolve(currentPayload);
        await flushUpdates();

        expect(board.setTimeoutSpy).not.toHaveBeenCalled();
    });

    it('releases every overlay kind when cancelled before an unreported mount', async () => {
        const host = await renderOverlayHost();
        const cases: Array<{
            request: OverlayRequest;
            rendered: () => CapturedProps | undefined;
            unmounts: () => number;
        }> = [
            {
                request: { kind: 'create-task' },
                rendered: () => captures.dynamicProps.get(0),
                unmounts: () => captures.dynamicUnmounts.get(0) ?? 0,
            },
            {
                request: { kind: 'create-note' },
                rendered: () => captures.dynamicProps.get(1),
                unmounts: () => captures.dynamicUnmounts.get(1) ?? 0,
            },
            {
                request: { kind: 'create-activity' },
                rendered: () => captures.dynamicProps.get(2),
                unmounts: () => captures.dynamicUnmounts.get(2) ?? 0,
            },
            {
                request: { kind: 'create-company' },
                rendered: () => captures.dynamicProps.get(3),
                unmounts: () => captures.dynamicUnmounts.get(3) ?? 0,
            },
            {
                request: { kind: 'create-person' },
                rendered: () => captures.dynamicProps.get(4),
                unmounts: () => captures.dynamicUnmounts.get(4) ?? 0,
            },
            {
                request: { kind: 'create-deal' },
                rendered: () => captures.dynamicProps.get(5),
                unmounts: () => captures.dynamicUnmounts.get(5) ?? 0,
            },
            {
                request: { kind: 'import-companies' },
                rendered: () => captures.importProps.get('companies'),
                unmounts: () => captures.importUnmounts.get('companies') ?? 0,
            },
            {
                request: { kind: 'import-contacts' },
                rendered: () => captures.importProps.get('persons'),
                unmounts: () => captures.importUnmounts.get('persons') ?? 0,
            },
            {
                request: { kind: 'import-deals' },
                rendered: () => captures.importProps.get('deals'),
                unmounts: () => captures.importUnmounts.get('deals') ?? 0,
            },
            {
                request: {
                    kind: 'workflow-manual-run',
                    sourceSurface: 'record',
                    recordType: 'person',
                    scope: { kind: 'single_record', recordId: 10 },
                },
                rendered: () => captures.dynamicProps.get(6),
                unmounts: () => captures.dynamicUnmounts.get(6) ?? 0,
            },
        ];

        let generation = 1;
        for (const overlayCase of cases) {
            await host.show(overlayCase.request, generation);
            expect(requiredProps(overlayCase.rendered(), overlayCase.request.kind).open).toBe(true);
            const before = overlayCase.unmounts();
            await host.show(null, null);
            expect(overlayCase.unmounts()).toBe(before + 1);
            generation += 1;
        }
        await host.unmount();
    });

    it('retains a mounted task through cancellation and releases it on close completion', async () => {
        const host = await renderOverlayHost();
        await host.show({ kind: 'create-task' }, 11);
        const task = requiredProps(captures.dynamicProps.get(0), 'Task dialog');
        await act(async () => {
            invoke(task, 'onDraftMounted');
        });
        const before = captures.dynamicUnmounts.get(0) ?? 0;

        await host.show(null, null);
        const closingTask = requiredProps(captures.dynamicProps.get(0), 'Closing task dialog');
        expect(closingTask.open).toBe(false);
        expect(captures.dynamicUnmounts.get(0) ?? 0).toBe(before);

        await act(async () => {
            invoke(closingTask, 'onCloseComplete');
        });
        expect(captures.dynamicUnmounts.get(0) ?? 0).toBe(before + 1);
        await host.unmount();
    });

    it('ignores stale close completion after replacing an overlay generation', async () => {
        const host = await renderOverlayHost();
        await host.show({ kind: 'create-task' }, 21);
        const firstTask = requiredProps(captures.dynamicProps.get(0), 'First task dialog');
        await act(async () => {
            invoke(firstTask, 'onDraftMounted');
        });
        const staleCloseComplete = firstTask.onCloseComplete;
        if (typeof staleCloseComplete !== 'function') throw new Error('Close completion is not callable');

        await host.show({ kind: 'create-task', defaults: { personId: 11 } }, 22);
        const replacement = requiredProps(captures.dynamicProps.get(0), 'Replacement task dialog');
        expect(replacement.open).toBe(true);

        await act(async () => {
            staleCloseComplete();
        });
        expect(requiredProps(captures.dynamicProps.get(0), 'Replacement task dialog').open).toBe(true);
        await host.show(null, null);
        await host.unmount();
    });

    it('closes and releases an overlay when its dynamic chunk fails', async () => {
        captures.failedDynamicIndices.add(3);
        const host = await renderOverlayHost();

        await host.show({ kind: 'create-company' }, 31);
        await flushUpdates();

        expect(host.onClose).toHaveBeenCalledOnce();
        captures.failedDynamicIndices.delete(3);
        await host.show({ kind: 'create-person' }, 32);
        expect(requiredProps(captures.dynamicProps.get(4), 'Replacement overlay').open).toBe(true);
        await host.unmount();
    });

    it('releases a task request cancelled before its dialog mounts', () => {
        const request = { kind: 'create-task' };
        const opened = reduceOverlayRetention(null, {
            type: 'opened',
            generation: 7,
            value: request,
            capabilities: { reportsMount: true, reportsCloseCompletion: true },
        });

        const cancelled = reduceOverlayRetention(opened, {
            type: 'cancelled',
            generation: 7,
        });

        expect(cancelled).toBeNull();
    });

    it('keeps a mounted task request through exit and releases it on close completion', () => {
        const request = { kind: 'create-task' };
        const opened = reduceOverlayRetention(null, {
            type: 'opened',
            generation: 8,
            value: request,
            capabilities: { reportsMount: true, reportsCloseCompletion: true },
        });
        const mounted = reduceOverlayRetention(opened, {
            type: 'mounted',
            generation: 8,
        });
        const cancelled = reduceOverlayRetention(mounted, {
            type: 'cancelled',
            generation: 8,
        });

        expect(cancelled?.open).toBe(false);
        expect(cancelled?.value).toBe(request);
        expect(reduceOverlayRetention(cancelled, {
            type: 'close-completed',
            generation: 8,
        })).toBeNull();
    });

    it('completes close once only after an observed open-to-closed transition', () => {
        const gate = createCloseCompletionGate(true);

        expect(gate.consume()).toBe(false);
        gate.observe(true);
        expect(gate.consume()).toBe(false);
        gate.observe(false);
        expect(gate.consume()).toBe(true);
        expect(gate.consume()).toBe(false);
    });

    it('clears only the active Radar task that owns the closing signal store', () => {
        const activeSignalState = taskSignalStore();
        const otherSignalState = taskSignalStore();
        const activeTask = { signalId: 42, signalState: activeSignalState };

        expect(releaseActiveRadarTask(activeTask, otherSignalState)).toBe(activeTask);
        expect(releaseActiveRadarTask(activeTask, activeSignalState)).toBeNull();
    });
});
