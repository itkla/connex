'use client';

import { useCallback, useEffect, useRef, useState } from 'react';

type NavigationAction = () => void | Promise<void>;
type PendingNavigation = { navigate: NavigationAction; onCancel?: () => void };
type NavigationGuard = {
    request: (navigate: NavigationAction, onCancel?: () => void) => void;
    isDirty: () => boolean;
    leave: () => void;
};
const navigationGuards = new Set<NavigationGuard>();
const HISTORY_INDEX = '__connexEditorHistoryIndex';
let historyTrackingActive = false;

function historyIndex(state: unknown): number | null {
    if (!state || typeof state !== 'object' || !(HISTORY_INDEX in state)) return null;
    const index: unknown = state[HISTORY_INDEX];
    return typeof index === 'number' && Number.isSafeInteger(index) ? index : null;
}

function indexedHistoryState(state: unknown, index: number): object {
    return { ...(state && typeof state === 'object' ? state : {}), [HISTORY_INDEX]: index };
}

/**
 * Tracks existing entries for browsers without the Navigation API, preserving Next's state. Tracking
 * lives for the document so repeated shell mounts cannot reset indices after a public-route visit.
 * The returned cleanup disposes the document's tracker; repeated initialization is inert.
 */
export function trackEditorNavigationHistory(): (() => void) | undefined {
    if (window.navigation || historyTrackingActive) return;
    historyTrackingActive = true;
    const originalPush = window.history.pushState;
    const originalReplace = window.history.replaceState;
    let running = true;
    let index: number | null = historyIndex(window.history.state) ?? 0;
    let url = window.location.href;
    let restoring: { delta: number; guard: NavigationGuard } | null = null;
    originalReplace.call(window.history, indexedHistoryState(window.history.state, index), '', url);

    const push: History['pushState'] = (state: unknown, unused: string, destination?: string | URL | null) => {
        if (!running) return originalPush.call(window.history, state, unused, destination);
        originalPush.call(window.history, index === null ? state : indexedHistoryState(state, index + 1), unused, destination);
        if (index !== null) index += 1;
        url = window.location.href;
    };
    const replace: History['replaceState'] = (state: unknown, unused: string, destination?: string | URL | null) => {
        originalReplace.call(window.history, running && index !== null ? indexedHistoryState(state, index) : state, unused, destination);
        if (running) url = window.location.href;
    };
    const onPopState = (event: PopStateEvent) => {
        const targetIndex = historyIndex(event.state);
        const guard = [...navigationGuards].at(-1);
        const destination = new URL(window.location.href);
        const current = new URL(url);
        const changesRoute = destination.pathname !== current.pathname || destination.search !== current.search;
        if (targetIndex === null || index === null) {
            if (changesRoute) guard?.leave();
            restoring = null;
            index = targetIndex;
            url = window.location.href;
            return;
        }
        if (restoring) {
            event.stopImmediatePropagation();
            if (targetIndex !== index) {
                window.history.go(index - targetIndex);
                return;
            }
            const { delta, guard } = restoring;
            restoring = null;
            guard.request(() => window.history.go(delta));
            return;
        }
        if (guard?.isDirty() && changesRoute) {
            const delta = targetIndex - index;
            if (delta !== 0) {
                event.stopImmediatePropagation();
                restoring = { delta, guard };
                window.history.go(-delta);
                return;
            }
            guard.leave();
        }
        index = targetIndex;
        url = window.location.href;
    };
    window.history.pushState = push;
    window.history.replaceState = replace;
    window.addEventListener('popstate', onPopState, true);
    return () => {
        historyTrackingActive = false;
        running = false;
        window.removeEventListener('popstate', onPopState, true);
        if (window.history.pushState === push) window.history.pushState = originalPush;
        if (window.history.replaceState === replace) window.history.replaceState = originalReplace;
    };
}

/**
 * Routes a shell operation through the editor's discard choice before its side effects run.
 * `onCancel` settles owners waiting for a decision. Async operations own their error feedback;
 * the guard rearms after they settle so a refused workspace selection leaves the draft protected.
 * Route callers supply `destination` so same-path/query requests remain inert without disabling protection.
 */
export function requestGuardedNavigation(navigate: NavigationAction, { destination, onCancel }: {
    destination?: string;
    onCancel?: () => void;
} = {}): void {
    if (destination && typeof window !== 'undefined' && window.location?.href) {
        const url = new URL(destination, window.location.href);
        if (url.origin === window.location.origin && url.pathname === window.location.pathname
            && url.search === window.location.search) {
            onCancel?.();
            return;
        }
    }
    const guard = [...navigationGuards].at(-1);
    if (guard) guard.request(navigate, onCancel);
    else void navigate();
}

type GuardOptions = {
    /** Whether the form currently holds unsaved edits, or a synchronous reader for imperative owners. */
    isDirty: boolean | (() => boolean);
    /** Performs the real close (e.g. `() => onOpenChange(false)`). */
    onClose: () => void;
    /** When false the guard is inert and dismissals close immediately (e.g. while submitting). */
    enabled?: boolean;
    /** Full-page editors also protect links, shell navigation and browser history traversal. */
    navigation?: {
        push: (href: string) => void;
        /** Synchronously invalidates editor continuations before any accepted departure. */
        onBeforeLeave?: () => void;
    };
};

type Guard = {
    /** Wrap a surface's `onOpenChange`; a dirty dismissal opens the confirm instead of closing. */
    onOpenChange: (open: boolean) => void;
    /** For dismiss paths that bypass `onOpenChange` (a Cancel/back button). */
    requestClose: () => void;
    /** Performs an intentional navigation after a successful save without prompting again. */
    allowNavigation: (navigate: NavigationAction) => void;
    /** Drives the confirm-discard dialog. */
    confirm: { open: boolean; onKeepEditing: () => void; onDiscard: () => void };
};

/**
 * Protects a form from accidental discard. While `isDirty && enabled`, any dismissal (the dialog X,
 * Escape, outside-click, or drawer swipe routed through {@link Guard.onOpenChange}, or a button routed
 * through {@link Guard.requestClose}) opens an in-app confirm instead of closing, and a `beforeunload`
 * listener catches refresh / tab-close / external navigation. Once the user confirms, {@link onClose}
 * runs. Full-page editors opt into `navigation` to guard links, shell navigation and same-document
 * Back/Forward through the Navigation API or the shell's indexed history, without inserting entries. Document departures
 * retain the browser's native warning. Use `allowNavigation` only after a successful save; a pending
 * or failed save must stay guarded. Dialogs can disable the guard during their post-success close.
 */
export function useUnsavedChangesGuard({ isDirty, onClose, enabled = true, navigation }: GuardOptions): Guard {
    const [confirmOpen, setConfirmOpen] = useState(false);
    const pendingNavigation = useRef<PendingNavigation | null>(null);
    const leaving = useRef(false);
    const mounted = useRef(false);
    const readsDirtyState = typeof isDirty === 'function';
    const active = enabled && (readsDirtyState || isDirty);
    const hasUnsavedChanges = useCallback(
        () => !leaving.current && enabled && (typeof isDirty === 'function' ? isDirty() : isDirty),
        [enabled, isDirty],
    );

    useEffect(() => {
        mounted.current = true;
        return () => {
            mounted.current = false;
            pendingNavigation.current?.onCancel?.();
            pendingNavigation.current = null;
        };
    }, []);

    useEffect(() => {
        if (!active) return;
        const onBeforeUnload = (event: BeforeUnloadEvent) => {
            if (!hasUnsavedChanges()) return;
            event.preventDefault();
            event.returnValue = '';
        };
        window.addEventListener('beforeunload', onBeforeUnload);
        return () => window.removeEventListener('beforeunload', onBeforeUnload);
    }, [active, hasUnsavedChanges]);

    const push = navigation?.push;
    const onBeforeLeave = navigation?.onBeforeLeave;
    const allowNavigation = useCallback((navigate: NavigationAction) => {
        if (!mounted.current || leaving.current) return;
        leaving.current = true;
        pendingNavigation.current = null;
        setConfirmOpen(false);
        onBeforeLeave?.();
        try {
            const pending = navigate();
            if (pending) {
                void pending.then(
                    () => { leaving.current = false; },
                    () => { leaving.current = false; },
                );
            }
        } catch (error) {
            leaving.current = false;
            throw error;
        }
    }, [onBeforeLeave]);

    const requestNavigation = useCallback((navigate: NavigationAction, onCancel?: () => void) => {
        pendingNavigation.current?.onCancel?.();
        if (hasUnsavedChanges()) {
            pendingNavigation.current = { navigate, onCancel };
            setConfirmOpen(true);
        } else if (push) {
            allowNavigation(navigate);
        } else {
            void navigate();
        }
    }, [allowNavigation, hasUnsavedChanges, push]);

    const requestClose = useCallback(() => {
        pendingNavigation.current?.onCancel?.();
        pendingNavigation.current = null;
        if (hasUnsavedChanges()) setConfirmOpen(true);
        else if (push) allowNavigation(onClose);
        else onClose();
    }, [allowNavigation, hasUnsavedChanges, onClose, push]);

    useEffect(() => {
        if (!push) return;
        const navigationGuard = {
            request: requestNavigation,
            isDirty: hasUnsavedChanges,
            leave: () => {
                pendingNavigation.current?.onCancel?.();
                allowNavigation(() => {});
            },
        };
        navigationGuards.add(navigationGuard);
        const onClick = (event: MouseEvent) => {
            if (event.defaultPrevented || event.button !== 0 || event.metaKey || event.ctrlKey
                || event.shiftKey || event.altKey || !hasUnsavedChanges()) return;
            const link = event.target instanceof Element ? event.target.closest('a[href]') : null;
            if (!(link instanceof HTMLAnchorElement) || link.hasAttribute('download') || (link.target && link.target !== '_self')) return;
            const url = new URL(link.href, window.location.href);
            if (url.origin !== window.location.origin || (url.pathname === window.location.pathname
                && url.search === window.location.search)) return;
            event.preventDefault();
            event.stopPropagation();
            requestNavigation(() => push(url.pathname + url.search + url.hash));
        };
        const onNavigate = (event: NavigateEvent) => {
            if (event.navigationType !== 'traverse' || !event.destination.sameDocument
                || !event.cancelable || !hasUnsavedChanges()) return;
            const url = new URL(event.destination.url);
            if (url.pathname === window.location.pathname && url.search === window.location.search) return;
            event.preventDefault();
            const key = event.destination.key;
            requestNavigation(() => {
                const result = window.navigation.traverseTo(key);
                void Promise.allSettled([result.committed, result.finished]).then((results) => {
                    if (results.some((result) => result.status === 'rejected')) leaving.current = false;
                });
            });
        };
        document.addEventListener('click', onClick, true);
        window.navigation?.addEventListener('navigate', onNavigate);
        return () => {
            navigationGuards.delete(navigationGuard);
            document.removeEventListener('click', onClick, true);
            window.navigation?.removeEventListener('navigate', onNavigate);
        };
    }, [allowNavigation, hasUnsavedChanges, push, requestNavigation]);

    const onOpenChange = useCallback(
        (open: boolean) => {
            if (open) return;
            requestClose();
        },
        [requestClose],
    );

    const onKeepEditing = useCallback(() => {
        pendingNavigation.current?.onCancel?.();
        pendingNavigation.current = null;
        setConfirmOpen(false);
    }, []);
    const onDiscard = useCallback(() => {
        const navigate = pendingNavigation.current?.navigate ?? onClose;
        pendingNavigation.current = null;
        if (push) allowNavigation(navigate);
        else {
            setConfirmOpen(false);
            navigate();
        }
    }, [allowNavigation, onClose, push]);

    return {
        onOpenChange,
        requestClose,
        allowNavigation,
        confirm: { open: confirmOpen, onKeepEditing, onDiscard },
    };
}
