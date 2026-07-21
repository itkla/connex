"use client";

import {
    createContext,
    useCallback,
    useContext,
    useEffect,
    useLayoutEffect,
    useMemo,
    useRef,
    useState,
    type ReactNode,
} from "react";
import { usePathname, useRouter } from "next/navigation";
import { useLocale, useTranslations } from "next-intl";

import type { User } from "@/app/lib/types";
import { ApiError } from "@/app/lib/api";
import { toastError } from "@/app/lib/toast";
import { useWorkspace } from "@/app/hooks/useWorkspace";
import ActionOverlayHost from "@/app/components/actions/ActionOverlayHost";

import {
    compareActions,
    type ActionContext,
    type ActionGroup,
    type ActionId,
    type ActionRunResult,
    type ActionSource,
    type ActionsContextValue,
    type ActiveRecordRef,
    type ActiveSelection,
    type AppAction,
    type OverlayRequest,
} from "@/app/lib/actions/types";
import { devInvariant } from "@/app/lib/actions/devInvariant";
import { normalizeShortcut, RESERVED_CHORDS } from "@/app/lib/actions/shortcut";
import { resolveCan } from "@/app/lib/actions/permissions";
import { SEED_ACTIONS } from "@/app/lib/actions/seedActions";

type RegistrationToken = symbol;

type ScopedOverlay = {
    request: OverlayRequest;
    userId: number | null;
    workspaceId: number | null;
};

const EMPTY_ACTIONS: readonly AppAction[] = [];
const EMPTY_PENDING: ReadonlySet<ActionId> = new Set();

const ActionsContext = createContext<ActionsContextValue | null>(null);

/**
 * Internal channel through which registration and contributor hooks feed the provider. Kept separate
 * from the public {@link ActionsContext} so consumers cannot mutate the registry directly.
 */
type ActionContributorValue = {
    register: (token: RegistrationToken, actions: readonly AppAction[]) => void;
    unregister: (token: RegistrationToken) => void;
    setRecord: (token: RegistrationToken, record: ActiveRecordRef | null) => void;
    clearRecord: (token: RegistrationToken) => void;
    setSelection: (token: RegistrationToken, selection: ActiveSelection | null) => void;
    clearSelection: (token: RegistrationToken) => void;
};

const ActionContributorContext = createContext<ActionContributorValue | null>(null);

function someLiveAction(
    registrations: Map<RegistrationToken, readonly AppAction[]>,
    exceptToken: RegistrationToken,
    predicate: (action: AppAction) => boolean,
): boolean {
    for (const [token, actions] of registrations) {
        if (token === exceptToken) continue;
        for (const action of actions) if (predicate(action)) return true;
    }
    return false;
}

function acceptRegistration(
    registrations: Map<RegistrationToken, readonly AppAction[]>,
    token: RegistrationToken,
    incoming: readonly AppAction[],
): AppAction[] {
    const accepted: AppAction[] = [];
    for (const action of incoming) {
        const idTaken =
            accepted.some((existing) => existing.id === action.id) ||
            someLiveAction(registrations, token, (existing) => existing.id === action.id);
        devInvariant(!idTaken, `Duplicate action id "${action.id}".`);
        if (idTaken) continue;

        let shortcut = action.shortcut;
        if (shortcut) {
            const chord = normalizeShortcut(shortcut);
            const reserved = RESERVED_CHORDS.has(chord);
            devInvariant(!reserved, `Action "${action.id}" uses reserved shortcut "${shortcut}".`);
            const conflict =
                !reserved &&
                (accepted.some((existing) => existing.shortcut && normalizeShortcut(existing.shortcut) === chord) ||
                    someLiveAction(
                        registrations,
                        token,
                        (existing) => !!existing.shortcut && normalizeShortcut(existing.shortcut) === chord,
                    ));
            devInvariant(!conflict, `Shortcut "${shortcut}" for "${action.id}" conflicts with another action.`);
            if (reserved || conflict) shortcut = undefined;
        }
        accepted.push(shortcut === action.shortcut ? action : { ...action, shortcut });
    }
    return accepted;
}

/**
 * Provides the shared application action registry to the authenticated app shell. One action
 * definition can be surfaced from menus, the command palette, record menus, empty states, and
 * shortcuts without duplicating execution logic. Place it as the innermost app-shell provider so it
 * can read workspace context and be consumed by the sidebar, search bar, and pages beneath it.
 */
export function ActionProvider({ user, children }: { user: User | null; children: ReactNode }) {
    const router = useRouter();
    const pathname = usePathname() ?? "";
    const locale = useLocale();
    const t = useTranslations("Actions");
    const { activeWorkspace, switching } = useWorkspace();
    const activeUserId = user?.id ?? null;
    const activeWorkspaceId = activeWorkspace?.id ?? null;
    const activeIdentity = `${activeUserId ?? "anon"}:${switching ? "switching" : (activeWorkspaceId ?? "none")}`;

    const registrationsRef = useRef<Map<RegistrationToken, readonly AppAction[]>>(new Map());
    const registryMapRef = useRef<Map<ActionId, AppAction>>(new Map());
    const [actions, setActions] = useState<readonly AppAction[]>(EMPTY_ACTIONS);

    const [record, setRecordState] = useState<ActiveRecordRef | null>(null);
    const [selection, setSelectionState] = useState<ActiveSelection | null>(null);
    const recordOwnerRef = useRef<RegistrationToken | null>(null);
    const selectionOwnerRef = useRef<RegistrationToken | null>(null);

    const [overlay, setOverlay] = useState<ScopedOverlay | null>(null);
    const [overlayIdentity, setOverlayIdentity] = useState(activeIdentity);
    const lastInvokerRef = useRef<HTMLElement | null>(null);

    if (overlayIdentity !== activeIdentity) {
        setOverlayIdentity(activeIdentity);
        setOverlay(null);
    }

    const pendingRef = useRef<Set<ActionId>>(new Set());
    const [pendingIds, setPendingIds] = useState<ReadonlySet<ActionId>>(EMPTY_PENDING);

    const recompute = useCallback(() => {
        const flat: AppAction[] = [];
        for (const list of registrationsRef.current.values()) flat.push(...list);
        flat.sort(compareActions);
        registryMapRef.current = new Map(flat.map((action) => [action.id, action]));
        setActions(flat);
    }, []);

    const register = useCallback(
        (token: RegistrationToken, incoming: readonly AppAction[]) => {
            registrationsRef.current.set(token, acceptRegistration(registrationsRef.current, token, incoming));
            recompute();
        },
        [recompute],
    );

    const unregister = useCallback(
        (token: RegistrationToken) => {
            if (registrationsRef.current.delete(token)) recompute();
        },
        [recompute],
    );

    const setRecord = useCallback((token: RegistrationToken, next: ActiveRecordRef | null) => {
        recordOwnerRef.current = token;
        setRecordState(next);
    }, []);
    const clearRecord = useCallback((token: RegistrationToken) => {
        if (recordOwnerRef.current !== token) return;
        recordOwnerRef.current = null;
        setRecordState(null);
    }, []);
    const setSelection = useCallback((token: RegistrationToken, next: ActiveSelection | null) => {
        selectionOwnerRef.current = token;
        setSelectionState(next);
    }, []);
    const clearSelection = useCallback((token: RegistrationToken) => {
        if (selectionOwnerRef.current !== token) return;
        selectionOwnerRef.current = null;
        setSelectionState(null);
    }, []);

    const openOverlay = useCallback((request: OverlayRequest) => {
        const active = document.activeElement;
        lastInvokerRef.current = active instanceof HTMLElement ? active : null;
        setOverlay({ request, userId: activeUserId, workspaceId: activeWorkspaceId });
    }, [activeUserId, activeWorkspaceId]);
    const closeOverlay = useCallback(() => {
        setOverlay(null);
        const invoker = lastInvokerRef.current;
        lastInvokerRef.current = null;
        if (invoker && invoker.isConnected) {
            requestAnimationFrame(() => invoker.focus());
        }
    }, []);

    const context = useMemo<ActionContext>(
        () => ({
            workspace: activeWorkspace,
            user,
            route: { pathname },
            locale,
            record,
            selection,
            can: resolveCan(activeWorkspace),
        }),
        [activeWorkspace, user, pathname, locale, record, selection],
    );
    const contextRef = useRef(context);
    useLayoutEffect(() => {
        contextRef.current = context;
    }, [context]);

    const translate = useCallback(
        (key: string, values?: Record<string, string | number>) => t(key, values),
        [t],
    );

    const run = useCallback(
        async (
            id: ActionId,
            options?: { source?: ActionSource; record?: ActiveRecordRef | null },
        ): Promise<ActionRunResult> => {
            const action = registryMapRef.current.get(id);
            if (!action) {
                if (process.env.NODE_ENV !== "production") {
                    console.error(`[actions] run() called with unknown action id "${id}".`);
                }
                return { status: "skipped", reason: "unknown-id" };
            }
            const currentContext =
                options && "record" in options
                    ? { ...contextRef.current, record: options.record ?? null }
                    : contextRef.current;
            if (action.isAvailable && !action.isAvailable(currentContext)) {
                return { status: "skipped", reason: "unavailable" };
            }
            if (pendingRef.current.has(id)) {
                return { status: "skipped", reason: "pending" };
            }

            pendingRef.current.add(id);
            setPendingIds(new Set(pendingRef.current));
            try {
                await action.execute(currentContext, { router, openOverlay, closeOverlay, translate });
                return { status: "completed" };
            } catch (error) {
                const message = error instanceof ApiError ? error.message : translate("feedback.runFailed");
                toastError(message);
                return { status: "failed", error };
            } finally {
                pendingRef.current.delete(id);
                setPendingIds(new Set(pendingRef.current));
            }
        },
        [router, openOverlay, closeOverlay, translate],
    );

    const getAction = useCallback((id: ActionId) => registryMapRef.current.get(id), []);

    const isAvailableForRecord = useCallback((id: ActionId, record: ActiveRecordRef) => {
        const action = registryMapRef.current.get(id);
        if (!action) return false;
        if (!action.isAvailable) return true;
        return action.isAvailable({ ...contextRef.current, record });
    }, []);

    const seedTokenRef = useRef<RegistrationToken>(Symbol("actions:seed"));
    useEffect(() => {
        const token = seedTokenRef.current;
        register(token, SEED_ACTIONS);
        return () => unregister(token);
    }, [register, unregister]);

    const value = useMemo<ActionsContextValue>(
        () => ({ actions, context, pendingIds, run, getAction, isAvailableForRecord, openOverlay }),
        [actions, context, pendingIds, run, getAction, isAvailableForRecord, openOverlay],
    );
    const contributorValue = useMemo<ActionContributorValue>(
        () => ({ register, unregister, setRecord, clearRecord, setSelection, clearSelection }),
        [register, unregister, setRecord, clearRecord, setSelection, clearSelection],
    );
    const visibleOverlay =
        !switching && overlay?.userId === activeUserId && overlay.workspaceId === activeWorkspaceId
            ? overlay.request
            : null;

    return (
        <ActionsContext.Provider value={value}>
            <ActionContributorContext.Provider value={contributorValue}>
                {children}
                <ActionOverlayHost
                    key={activeIdentity}
                    overlay={visibleOverlay}
                    user={user}
                    onClose={closeOverlay}
                />
            </ActionContributorContext.Provider>
        </ActionsContext.Provider>
    );
}

/** The action registry for the current app shell. Throws if used outside {@link ActionProvider}. */
export function useActions(): ActionsContextValue {
    const value = useContext(ActionsContext);
    if (!value) throw new Error("useActions must be used within ActionProvider");
    return value;
}

function useContributor(): ActionContributorValue {
    const value = useContext(ActionContributorContext);
    if (!value) throw new Error("Action contributor hooks must be used within ActionProvider");
    return value;
}

/**
 * Registers page- or feature-scoped actions on mount and removes them on unmount, so navigating away
 * never leaves stale actions behind. The `actions` array must be stable (memoize it) because the
 * effect re-registers whenever its identity changes; anything an action's `execute` closes over must
 * therefore be part of that memo's dependencies.
 *
 * @param actions - the actions to contribute while the calling component is mounted
 */
export function useRegisterActions(actions: readonly AppAction[]): void {
    const { register, unregister } = useContributor();
    const tokenRef = useRef<RegistrationToken>(Symbol("actions:registration"));
    useEffect(() => {
        const token = tokenRef.current;
        register(token, actions);
        return () => unregister(token);
    }, [register, unregister, actions]);
}

/**
 * The live actions the current context makes available, optionally filtered to one group, in
 * canonical order. Recomputes when the registry or context changes.
 *
 * @param group - restrict the result to a single {@link ActionGroup}
 * @returns the available actions
 */
export function useAvailableActions(group?: ActionGroup): readonly AppAction[] {
    const { actions, context } = useActions();
    return useMemo(
        () =>
            actions.filter(
                (action) => (group ? action.group === group : true) && (!action.isAvailable || action.isAvailable(context)),
            ),
        [actions, context, group],
    );
}

/**
 * Publishes the record the current page is focused on so record-scoped actions become available, and
 * clears it on unmount. The slot is single-owner and last-writer-wins, which tolerates the brief
 * overlap when one record page mounts before the previous one unmounts.
 *
 * @param record - the active record, or null when the page has none
 */
export function useActionRecord(record: ActiveRecordRef | null): void {
    const { setRecord, clearRecord } = useContributor();
    const tokenRef = useRef<RegistrationToken>(Symbol("actions:record"));
    useEffect(() => {
        const token = tokenRef.current;
        setRecord(token, record);
        return () => clearRecord(token);
    }, [setRecord, clearRecord, record]);
}

/**
 * Publishes the current list selection so selection-scoped actions become available, and clears it on
 * unmount. Single-owner and last-writer-wins, mirroring {@link useActionRecord}.
 *
 * @param selection - the active selection, or null when nothing is selected
 */
export function useActionSelection(selection: ActiveSelection | null): void {
    const { setSelection, clearSelection } = useContributor();
    const tokenRef = useRef<RegistrationToken>(Symbol("actions:selection"));
    useEffect(() => {
        const token = tokenRef.current;
        setSelection(token, selection);
        return () => clearSelection(token);
    }, [setSelection, clearSelection, selection]);
}
