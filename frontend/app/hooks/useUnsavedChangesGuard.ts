'use client';

import { useCallback, useLayoutEffect, useRef, useState } from 'react';

type NavigationAction = () => void | Promise<void>;
type PendingNavigation = { navigate: NavigationAction; onCancel?: () => void };
type NavigationGuard = {
    request: (navigate: NavigationAction, onCancel?: () => void) => void;
    onPopState: (event: PopStateEvent) => void;
};
const navigationGuards = new Set<NavigationGuard>();
let historyListenerInstalled = false;

/**
 * Installs the shared guard's dispatcher before hydration registers Next's listener.
 * Native Window popstate delivery can invoke target listeners in registration order,
 * irrespective of capture; a later editor listener cannot prevent an earlier router restore.
 */
export function initializeUnsavedChangesHistoryGuard(): void {
    if (typeof window === 'undefined' || historyListenerInstalled) return;
    historyListenerInstalled = true;
    window.addEventListener('popstate', (event) => {
        [...navigationGuards].at(-1)?.onPopState(event);
    }, true);
}
type HistoryPosition = { key: string; index: number };

function currentHistoryPosition(): HistoryPosition | null {
    const entry = window.navigation?.currentEntry;
    return entry?.key && Number.isSafeInteger(entry.index) && entry.index >= 0
        ? { key: entry.key, index: entry.index } : null;
}

/**
 * Routes a shell operation through the editor's discard choice before its side effects run.
 * `onCancel` settles owners waiting for a decision. Async operations own their error feedback;
 * protection stays active whenever a dirty editor is still mounted.
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
        /** Path and search from the rendered Next route, independent of a browser traversal in progress. */
        renderedHref: string;
        /** Reconciles browser traversal with Next, including entries whose state Next ignores. */
        replace?: (href: string) => void;
        /** Synchronously invalidates editor continuations before any accepted departure. */
        onBeforeLeave?: () => void;
        /** Disposes the discarded draft, including when navigation retains this editor route. */
        onDiscard: () => void;
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
 * Back/Forward by restoring the editor's history entry before asking and explicitly routing the
 * accepted destination. Native entry indices restore a known cursor without replacing its destination;
 * unknown distances restore the URL in the reached slot. No indices are added to history state.
 * Boundary subscriptions update in layout effects so committed edits are protected before passive
 * effects or the next native browser event. Dirty checks remain active
 * for the entire mounted lifetime. `allowNavigation` is versioned so a late save cannot redirect after
 * a newer departure; successful saves must update their saved draft baseline. Discard disposes the draft
 * through its owner, after any known traversal replay reconciles the router. Restoration uses the
 * native history prototype so Next does not interpret restoring the address bar as a new route.
 */
export function useUnsavedChangesGuard({ isDirty, onClose, enabled = true, navigation }: GuardOptions): Guard {
    const [confirmOpen, setConfirmOpen] = useState(false);
    const [navigationVersion, setNavigationVersion] = useState(0);
    const pendingNavigation = useRef<PendingNavigation | null>(null);
    const currentNavigationVersion = useRef(0);
    const editorEntry = useRef<{ url: string; state: unknown; position: HistoryPosition | null } | null>(null);
    const restoringTraversal = useRef<{ sourceKey: string; navigate: NavigationAction } | null>(null);
    const acceptedTraversal = useRef<{ key: string; complete: () => void } | null>(null);
    const mounted = useRef(false);
    const readsDirtyState = typeof isDirty === 'function';
    const active = enabled && (readsDirtyState || isDirty);
    const hasUnsavedChanges = useCallback(
        () => enabled && (typeof isDirty === 'function' ? isDirty() : isDirty),
        [enabled, isDirty],
    );

    useLayoutEffect(() => {
        if (!navigation?.push || !window.location?.href || !window.history) return;
        const rendered = new URL(navigation.renderedHref, window.location.origin);
        const visible = new URL(window.location.href);
        const matches = rendered.pathname === visible.pathname && rendered.search === visible.search;
        const previous = editorEntry.current;
        const previousUrl = previous ? new URL(previous.url) : null;
        const previousMatches = previousUrl?.pathname === rendered.pathname && previousUrl.search === rendered.search;
        const state: unknown = window.history.state;
        editorEntry.current = {
            url: rendered.href + (matches ? visible.hash : previousMatches ? previousUrl.hash : ''),
            state: matches ? state ?? previous?.state ?? null : previous?.state ?? null,
            position: matches ? currentHistoryPosition() : previous?.position ?? null,
        };
    });

    useLayoutEffect(() => {
        mounted.current = true;
        return () => {
            mounted.current = false;
            pendingNavigation.current?.onCancel?.();
            pendingNavigation.current = null;
        };
    }, []);

    useLayoutEffect(() => {
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
    const replace = navigation?.replace ?? push;
    const onBeforeLeave = navigation?.onBeforeLeave;
    const discardDraft = navigation?.onDiscard;
    const allowNavigation = useCallback((navigate: NavigationAction) => {
        if (!mounted.current || navigationVersion !== currentNavigationVersion.current) return false;
        currentNavigationVersion.current += 1;
        setNavigationVersion(currentNavigationVersion.current);
        pendingNavigation.current = null;
        setConfirmOpen(false);
        onBeforeLeave?.();
        const pending = navigate();
        if (pending) void pending.catch(() => undefined);
        return true;
    }, [navigationVersion, onBeforeLeave]);

    const requestNavigation = useCallback((navigate: NavigationAction, onCancel?: () => void) => {
        pendingNavigation.current?.onCancel?.();
        if (hasUnsavedChanges()) {
            pendingNavigation.current = { navigate, onCancel };
            setConfirmOpen(true);
        } else if (push) {
            if (!allowNavigation(navigate)) onCancel?.();
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

    useLayoutEffect(() => {
        if (!push) return;
        initializeUnsavedChangesHistoryGuard();
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
        const onPopState = (event: PopStateEvent) => {
            const entry = editorEntry.current;
            if (!entry || !replace) return;
            const destination = new URL(window.location.href);
            const source = new URL(entry.url);
            const href = destination.pathname + destination.search + destination.hash;
            const position = currentHistoryPosition();
            event.stopImmediatePropagation();
            const restoring = restoringTraversal.current;
            restoringTraversal.current = null;
            if (restoring && position?.key === restoring.sourceKey) {
                requestNavigation(restoring.navigate);
                return;
            }
            const accepted = acceptedTraversal.current;
            acceptedTraversal.current = null;
            if (accepted && position?.key === accepted.key) {
                replace(href);
                accepted.complete();
                return;
            }
            if (destination.pathname === source.pathname && destination.search === source.search) {
                editorEntry.current = { ...entry, url: destination.href, position };
                replace(href);
                return;
            }
            if (entry.position && position && entry.position.key !== position.key) {
                const delta = position.index - entry.position.index;
                if (delta !== 0) {
                    restoringTraversal.current = {
                        sourceKey: entry.position.key,
                        navigate: () => new Promise<void>((complete) => {
                            acceptedTraversal.current = { key: position.key, complete };
                            window.history.go(delta);
                        }),
                    };
                    window.history.go(-delta);
                    return;
                }
            }
            window.History.prototype.replaceState.call(window.history, entry.state, '', entry.url);
            requestNavigation(() => replace(href));
        };
        const navigationGuard = { request: requestNavigation, onPopState };
        navigationGuards.add(navigationGuard);
        document.addEventListener('click', onClick, true);
        return () => {
            navigationGuards.delete(navigationGuard);
            document.removeEventListener('click', onClick, true);
        };
    }, [hasUnsavedChanges, push, replace, requestNavigation]);

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
        if (push) allowNavigation(() => {
            const pending = navigate();
            if (pending && acceptedTraversal.current) return pending.finally(() => discardDraft?.());
            discardDraft?.();
            return pending;
        });
        else {
            setConfirmOpen(false);
            navigate();
        }
    }, [allowNavigation, discardDraft, onClose, push]);

    return {
        onOpenChange,
        requestClose,
        allowNavigation,
        confirm: { open: confirmOpen, onKeepEditing, onDiscard },
    };
}
