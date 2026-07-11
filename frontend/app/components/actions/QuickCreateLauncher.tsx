'use client';

import { useCallback, useEffect, useId, useRef, useState, useSyncExternalStore } from 'react';
import { createPortal } from 'react-dom';
import dynamic from 'next/dynamic';
import { useTranslations } from 'next-intl';
import { AnimatePresence, motion, useReducedMotion } from 'motion/react';
import { PlusIcon } from '@heroicons/react/16/solid';
import { ArrowLeftIcon, XMarkIcon } from '@heroicons/react/24/outline';

import {
    Sheet,
    SheetContent,
    SheetHeader,
    SheetTitle,
    SheetDescription,
} from '@/components/ui/sheet';
import { useActions, useAvailableActions } from '@/app/hooks/useActions';
import { deriveCreateDefaults } from '@/app/lib/actions/createDefaults';
import type { AppAction, RecordType } from '@/app/lib/actions/types';

const TaskQuickForm = dynamic(() => import('@/app/components/actions/create/TaskQuickForm'));
const NoteQuickForm = dynamic(() => import('@/app/components/actions/create/NoteQuickForm'));
const ActivityQuickForm = dynamic(() => import('@/app/components/actions/create/ActivityQuickForm'));

type FormKind = 'task' | 'note' | 'activity';
type View = 'selector' | FormKind;

/** Create action ids that render a compact form inline; all others hand off to their full dialog. */
const IN_PANEL_FORMS: Record<string, FormKind> = {
    'create.task': 'task',
    'create.note': 'note',
    'create.activity': 'activity',
};

const FORM_TO_RECORD_TYPE: Record<FormKind, RecordType> = {
    task: 'task',
    note: 'note',
    activity: 'activity',
};

const PANEL_WIDTH = 380;
const PANEL_GAP = 12;
const VIEWPORT_MARGIN = 16;

type Anchor = { top: number; left: number; maxHeight: number };

const MOBILE_QUERY = '(max-width: 767px)';

/** True once mounted on the client; false during SSR so portals/sheets only render after hydration. */
function useIsClient(): boolean {
    return useSyncExternalStore(
        () => () => {},
        () => true,
        () => false,
    );
}

/** Tracks the mobile viewport breakpoint via `matchMedia`, SSR-safe (server snapshot is desktop). */
function useIsMobile(): boolean {
    return useSyncExternalStore(
        (onChange) => {
            const mql = window.matchMedia(MOBILE_QUERY);
            mql.addEventListener('change', onChange);
            return () => mql.removeEventListener('change', onChange);
        },
        () => window.matchMedia(MOBILE_QUERY).matches,
        () => false,
    );
}

/**
 * The global Quick Create launcher. It replaces the sidebar's actions menu with a two-step surface:
 * a registry-driven type selector that morphs open from the trigger, then a compact in-panel form for
 * the self-contained record types or a hand-off to the full dialog for company/person/deal. On desktop
 * it renders as a portal'd panel anchored beside the sidebar (no navigation reflow); on mobile it uses
 * a bottom sheet. Focus is trapped while open and restored to the trigger on close.
 */
export default function QuickCreateLauncher() {
    const t = useTranslations('Actions');
    const { context, openOverlay, run } = useActions();
    const createActions = useAvailableActions('create');
    const reduceMotion = useReducedMotion() ?? false;

    const mounted = useIsClient();
    const isMobile = useIsMobile();
    const [open, setOpen] = useState(false);
    const [view, setView] = useState<View>('selector');
    const [anchor, setAnchor] = useState<Anchor | null>(null);

    const triggerRef = useRef<HTMLButtonElement>(null);
    const panelRef = useRef<HTMLDivElement>(null);
    const titleId = useId();

    const currentUserId = context.user?.id ?? null;

    const computeAnchor = useCallback((): Anchor | null => {
        const rect = triggerRef.current?.getBoundingClientRect();
        if (!rect) return null;
        const top = rect.top;
        return {
            top,
            left: rect.right + PANEL_GAP,
            maxHeight: window.innerHeight - top - VIEWPORT_MARGIN,
        };
    }, []);

    const closeLauncher = useCallback(() => {
        setOpen(false);
        setView('selector');
        const trigger = triggerRef.current;
        if (trigger) requestAnimationFrame(() => trigger.focus());
    }, []);

    const openLauncher = useCallback(() => {
        setView('selector');
        setAnchor(computeAnchor());
        setOpen(true);
    }, [computeAnchor]);

    useEffect(() => {
        if (!open || isMobile) return;
        const reflow = () => setAnchor(computeAnchor());
        window.addEventListener('resize', reflow);
        window.addEventListener('scroll', reflow, true);
        return () => {
            window.removeEventListener('resize', reflow);
            window.removeEventListener('scroll', reflow, true);
        };
    }, [open, isMobile, computeAnchor]);

    useEffect(() => {
        if (!open || isMobile) return;
        const raf = requestAnimationFrame(() => {
            const target = panelRef.current?.querySelector<HTMLElement>('[data-autofocus]');
            target?.focus();
        });
        return () => cancelAnimationFrame(raf);
    }, [open, view, isMobile]);

    const selectAction = useCallback(
        (action: AppAction) => {
            const formKind = IN_PANEL_FORMS[action.id];
            if (formKind) {
                setView(formKind);
                return;
            }
            closeLauncher();
            void run(action.id, { source: 'menu' });
        },
        [closeLauncher, run],
    );

    const escalateToDialog = useCallback(
        (kind: FormKind, draft: Record<string, string>) => {
            const recordType = FORM_TO_RECORD_TYPE[kind];
            const defaults = deriveCreateDefaults(context, recordType);
            if (kind === 'task')
                openOverlay({ kind: 'create-task', defaults, draft: { description: draft.description, dueDate: draft.dueDate } });
            else if (kind === 'note') openOverlay({ kind: 'create-note', defaults, draft: { content: draft.content } });
            else openOverlay({ kind: 'create-activity', defaults, draft });
            closeLauncher();
        },
        [context, openOverlay, closeLauncher],
    );

    const handlePanelKeyDown = useCallback(
        (event: React.KeyboardEvent<HTMLDivElement>) => {
            if (event.key === 'Escape') {
                event.preventDefault();
                if (view === 'selector') closeLauncher();
                else setView('selector');
                return;
            }
            if (event.key !== 'Tab' || !panelRef.current) return;
            const focusables = panelRef.current.querySelectorAll<HTMLElement>(
                'a[href], button:not([disabled]), textarea, input, select, [tabindex]:not([tabindex="-1"])',
            );
            if (focusables.length === 0) return;
            const first = focusables[0];
            const last = focusables[focusables.length - 1];
            if (event.shiftKey && document.activeElement === first) {
                event.preventDefault();
                last.focus();
            } else if (!event.shiftKey && document.activeElement === last) {
                event.preventDefault();
                first.focus();
            }
        },
        [view, closeLauncher],
    );

    const body = (
        <QuickCreatePanelBody
            view={view}
            actions={createActions}
            defaults={view === 'selector' ? undefined : deriveCreateDefaults(context, FORM_TO_RECORD_TYPE[view])}
            currentUserId={currentUserId}
            titleId={titleId}
            onSelect={selectAction}
            onBack={() => setView('selector')}
            onClose={closeLauncher}
            onCreated={closeLauncher}
            onMoreDetails={escalateToDialog}
            showChrome={!isMobile}
        />
    );

    return (
        <div className="mb-5 shrink-0">
            <button
                ref={triggerRef}
                type="button"
                aria-haspopup="dialog"
                aria-expanded={open}
                aria-label={t('quickCreate.trigger')}
                onClick={() => (open ? closeLauncher() : openLauncher())}
                className="inline-flex w-full items-center gap-2 rounded-lg bg-brand px-3 py-2 text-sm font-semibold text-brand-foreground transition-[transform,background-color] duration-150 ease-out hover:bg-brand-hover active:scale-[0.99] motion-reduce:transition-none motion-reduce:active:scale-100"
            >
                <PlusIcon className="size-4" />
                {t('quickCreate.trigger')}
            </button>

            {mounted && isMobile ? (
                <Sheet open={open} onOpenChange={(next) => (next ? openLauncher() : closeLauncher())}>
                    <SheetContent side="bottom" className="max-h-[85dvh] gap-0 rounded-t-2xl p-0">
                        <SheetHeader className="flex-row items-center gap-2 border-b border-border px-5 py-4">
                            {view !== 'selector' ? (
                                <button
                                    type="button"
                                    onClick={() => setView('selector')}
                                    aria-label={t('quickCreate.back')}
                                    className="grid size-7 shrink-0 place-items-center rounded-md text-muted-foreground transition-colors hover:bg-muted hover:text-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-brand"
                                >
                                    <ArrowLeftIcon className="size-4" />
                                </button>
                            ) : null}
                            <SheetTitle className="flex-1">
                                {view === 'selector' ? t('quickCreate.title') : t(`create.${view}`)}
                            </SheetTitle>
                            <SheetDescription className="sr-only">{t('quickCreate.description')}</SheetDescription>
                        </SheetHeader>
                        <div className="overflow-y-auto px-5 pb-6 pt-4">{body}</div>
                    </SheetContent>
                </Sheet>
            ) : null}

            {mounted && !isMobile
                ? createPortal(
                      <AnimatePresence>
                          {open ? (
                              <>
                                  <button
                                      type="button"
                                      aria-hidden
                                      tabIndex={-1}
                                      onClick={closeLauncher}
                                      className="fixed inset-0 z-40 cursor-default"
                                  />
                                  <motion.div
                                      ref={panelRef}
                                      role="dialog"
                                      aria-modal="false"
                                      aria-labelledby={titleId}
                                      onKeyDown={handlePanelKeyDown}
                                      initial={reduceMotion ? { opacity: 0 } : { opacity: 0, scale: 0.96, y: -6 }}
                                      animate={reduceMotion ? { opacity: 1 } : { opacity: 1, scale: 1, y: 0 }}
                                      exit={reduceMotion ? { opacity: 0 } : { opacity: 0, scale: 0.97, y: -4 }}
                                      transition={
                                          reduceMotion
                                              ? { duration: 0.12 }
                                              : { type: 'spring', stiffness: 520, damping: 38, mass: 0.8 }
                                      }
                                      style={{
                                          top: anchor?.top ?? 0,
                                          left: anchor?.left ?? 0,
                                          width: PANEL_WIDTH,
                                          maxHeight: anchor?.maxHeight,
                                          transformOrigin: 'top left',
                                      }}
                                      className="fixed z-50 flex flex-col overflow-hidden rounded-xl border border-border bg-popover text-popover-foreground shadow-xl ring-1 ring-foreground/5"
                                  >
                                      {body}
                                  </motion.div>
                              </>
                          ) : null}
                      </AnimatePresence>,
                      document.body,
                  )
                : null}
        </div>
    );
}

type PanelBodyProps = {
    view: View;
    actions: readonly AppAction[];
    defaults: ReturnType<typeof deriveCreateDefaults>;
    currentUserId: number | null;
    titleId: string;
    onSelect: (action: AppAction) => void;
    onBack: () => void;
    onClose: () => void;
    onCreated: () => void;
    onMoreDetails: (kind: FormKind, draft: Record<string, string>) => void;
    showChrome: boolean;
};

/**
 * The shared inner content of the launcher — the selector or a compact form — rendered inside both the
 * desktop panel and the mobile sheet. On desktop it draws its own header chrome (back/title/close); the
 * sheet supplies its own header, so chrome is suppressed there.
 */
function QuickCreatePanelBody({
    view,
    actions,
    defaults,
    currentUserId,
    titleId,
    onSelect,
    onBack,
    onClose,
    onCreated,
    onMoreDetails,
    showChrome,
}: PanelBodyProps) {
    const t = useTranslations('Actions');
    const isForm = view !== 'selector';

    return (
        <div className="flex min-h-0 flex-col">
            {showChrome ? (
                <header className="flex shrink-0 items-center gap-2 border-b border-border px-4 py-3">
                    {isForm ? (
                        <button
                            type="button"
                            onClick={onBack}
                            aria-label={t('quickCreate.back')}
                            className="grid size-7 place-items-center rounded-md text-muted-foreground transition-colors hover:bg-muted hover:text-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-brand"
                        >
                            <ArrowLeftIcon className="size-4" />
                        </button>
                    ) : null}
                    <h2 id={titleId} className="flex-1 text-sm font-semibold tracking-tight">
                        {isForm ? t(`create.${view}`) : t('quickCreate.title')}
                    </h2>
                    <button
                        type="button"
                        onClick={onClose}
                        aria-label={t('quickCreate.close')}
                        className="grid size-7 place-items-center rounded-md text-muted-foreground transition-colors hover:bg-muted hover:text-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-brand"
                    >
                        <XMarkIcon className="size-4" />
                    </button>
                </header>
            ) : null}

            <div className="min-h-0 overflow-y-auto p-4">
                {view === 'selector' ? (
                    <TypeSelector actions={actions} onSelect={onSelect} />
                ) : currentUserId == null ? null : view === 'task' ? (
                    <TaskQuickForm
                        defaults={defaults}
                        currentUserId={currentUserId}
                        onCreated={onCreated}
                        onMoreDetails={(draft) => onMoreDetails('task', draft)}
                    />
                ) : view === 'note' ? (
                    <NoteQuickForm
                        defaults={defaults}
                        currentUserId={currentUserId}
                        onCreated={onCreated}
                        onMoreDetails={(draft) => onMoreDetails('note', draft)}
                    />
                ) : (
                    <ActivityQuickForm
                        defaults={defaults}
                        currentUserId={currentUserId}
                        onCreated={onCreated}
                        onMoreDetails={(draft) => onMoreDetails('activity', draft)}
                    />
                )}
            </div>
        </div>
    );
}

/**
 * The registry-driven type selector. Renders the available create actions as a roving-focus option
 * list: Arrow keys move between rows, Enter or click chooses one. The first row is auto-focused when the
 * launcher opens.
 */
function TypeSelector({
    actions,
    onSelect,
}: {
    actions: readonly AppAction[];
    onSelect: (action: AppAction) => void;
}) {
    const t = useTranslations('Actions');
    const listRef = useRef<HTMLDivElement>(null);

    const handleKeyDown = (event: React.KeyboardEvent<HTMLDivElement>) => {
        if (event.key !== 'ArrowDown' && event.key !== 'ArrowUp') return;
        event.preventDefault();
        const items = Array.from(listRef.current?.querySelectorAll<HTMLElement>('[role="option"]') ?? []);
        if (items.length === 0) return;
        const index = items.indexOf(document.activeElement as HTMLElement);
        const nextIndex =
            event.key === 'ArrowDown'
                ? (index + 1) % items.length
                : (index - 1 + items.length) % items.length;
        items[nextIndex]?.focus();
    };

    return (
        <div ref={listRef} role="listbox" aria-label={t('quickCreate.title')} onKeyDown={handleKeyDown} className="grid gap-1">
            {actions.map((action, index) => {
                const Icon = action.icon;
                return (
                    <button
                        key={action.id}
                        type="button"
                        role="option"
                        aria-selected={false}
                        tabIndex={index === 0 ? 0 : -1}
                        data-autofocus={index === 0 ? '' : undefined}
                        onClick={() => onSelect(action)}
                        className="group flex items-center gap-3 rounded-lg px-2.5 py-2 text-left transition-colors hover:bg-muted focus-visible:bg-muted focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-brand"
                    >
                        <span className="grid size-8 shrink-0 place-items-center rounded-lg bg-muted text-muted-foreground ring-1 ring-border transition-colors group-hover:bg-brand-light group-hover:text-brand-dark group-hover:ring-transparent group-focus-visible:bg-brand-light group-focus-visible:text-brand-dark">
                            {Icon ? <Icon className="size-4" /> : null}
                        </span>
                        <span className="flex-1 text-sm font-medium text-foreground">{t(action.labelKey)}</span>
                    </button>
                );
            })}
        </div>
    );
}
