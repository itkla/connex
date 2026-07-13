'use client';

import { useCallback, useEffect, useId, useLayoutEffect, useRef, useState, useSyncExternalStore } from 'react';
import { createPortal } from 'react-dom';
import dynamic from 'next/dynamic';
import { useTranslations } from 'next-intl';
import { AnimatePresence, motion, useReducedMotion } from 'motion/react';
import { PlusIcon } from '@heroicons/react/16/solid';
import { ArrowLeftIcon, XMarkIcon } from '@heroicons/react/24/outline';
import { Loader2Icon } from 'lucide-react';

import {
    Drawer,
    DrawerContent,
    DrawerHeader,
    DrawerTitle,
    DrawerDescription,
} from '@/components/ui/drawer';
import { useIsMobile } from '@/app/hooks/useIsMobile';
import { useActions, useAvailableActions } from '@/app/hooks/useActions';
import { deriveCreateDefaults } from '@/app/lib/actions/createDefaults';
import { getContacts, getDeals, getUsers } from '@/app/lib/api';
import { easeOut, instant, springJiggle, springSmooth, springSnappy } from '@/app/lib/motion';
import type { AppAction, ActionContext } from '@/app/lib/actions/types';
import type { Contact, Deal, User } from '@/app/lib/types';

const TaskDialogForm = dynamic(() =>
    import('@/app/components/activity/tasks/TaskDialog').then((m) => ({ default: m.TaskDialogForm })),
);
const NoteDialogForm = dynamic(() =>
    import('@/app/components/activity/notes/NoteDialog').then((m) => ({ default: m.NoteDialogForm })),
);

const PANEL_WIDTH = 380;
const PANEL_GAP = 12;
const VIEWPORT_MARGIN = 16;
const MOBILE_HANDOFF_DELAY_MS = 300;

/** Create actions that morph into an embedded form inside the mobile launcher drawer (vs. handing off to a separate dialog). */
const EMBEDDED_FORM_BY_ACTION: Record<string, 'task' | 'note'> = {
    'create.task': 'task',
    'create.note': 'note',
};

type Anchor = { top: number; left: number; maxHeight: number };

/** True once mounted on the client; false during SSR so portals/sheets only render after hydration. */
function useIsClient(): boolean {
    return useSyncExternalStore(
        () => () => {},
        () => true,
        () => false,
    );
}

/**
 * The global Quick Create launcher. The sidebar's "New" button morphs open a registry-driven type
 * selector; choosing a record type hands off to that type's full creation dialog (shell-owned overlay).
 * On desktop it renders as a portal'd panel anchored beside the sidebar (no navigation reflow); on
 * mobile it uses a bottom sheet. Focus is trapped while open and restored to the trigger on close.
 * On mobile the selector and every create dialog are Base UI drawers, so the hand-off waits for this
 * drawer to finish closing before opening the target one — two drawers transitioning at once desyncs
 * the shared backdrop/dismiss handling and flicks the just-opened dialog straight back down.
 */
export default function QuickCreateLauncher() {
    const t = useTranslations('Actions');
    const { run, context } = useActions();
    const createActions = useAvailableActions('create');
    const reduceMotion = useReducedMotion() ?? false;

    const mounted = useIsClient();
    const isMobile = useIsMobile();
    const [open, setOpen] = useState(false);
    const [anchor, setAnchor] = useState<Anchor | null>(null);
    const [pending, setPending] = useState(false);
    const [openCount, setOpenCount] = useState(0);

    const triggerRef = useRef<HTMLButtonElement>(null);
    const rootRef = useRef<HTMLDivElement>(null);
    const panelRef = useRef<HTMLDivElement>(null);
    const titleId = useId();

    const currentUserId = context.user?.id ?? null;

    const computeAnchor = useCallback((): Anchor | null => {
        const rect = rootRef.current?.getBoundingClientRect();
        if (!rect) return null;
        const top = rect.top;
        return {
            top,
            left: rect.right + PANEL_GAP,
            maxHeight: window.innerHeight - top - VIEWPORT_MARGIN,
        };
    }, []);

    const closeLauncher = useCallback(() => {
        if (pending) return;
        setOpen(false);
        const trigger = triggerRef.current;
        if (trigger) requestAnimationFrame(() => trigger.focus());
    }, [pending]);

    const openLauncher = useCallback(() => {
        setPending(false);
        setOpenCount((count) => count + 1);
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
    }, [open, isMobile]);

    const selectAction = useCallback(
        (action: AppAction) => {
            closeLauncher();
            if (isMobile) {
                window.setTimeout(() => {
                    void run(action.id, { source: 'menu' });
                }, MOBILE_HANDOFF_DELAY_MS);
                return;
            }
            void run(action.id, { source: 'menu' });
        },
        [closeLauncher, run, isMobile],
    );

    const handlePanelKeyDown = useCallback(
        (event: React.KeyboardEvent<HTMLDivElement>) => {
            if (event.key === 'Escape') {
                event.preventDefault();
                closeLauncher();
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
        [closeLauncher],
    );

    const body = (
        <QuickCreatePanelBody
            actions={createActions}
            titleId={titleId}
            onSelect={selectAction}
            onClose={closeLauncher}
            showChrome={!isMobile}
        />
    );

    return (
        <div ref={rootRef} className="mb-5 shrink-0">
            <motion.button
                ref={triggerRef}
                type="button"
                aria-haspopup="dialog"
                aria-expanded={open}
                aria-label={t('quickCreate.trigger')}
                onClick={() => (open ? closeLauncher() : openLauncher())}
                whileTap={reduceMotion ? undefined : { scale: 0.95 }}
                transition={reduceMotion ? instant : springJiggle}
                className="inline-flex w-full items-center justify-center gap-2 rounded-xl bg-brand px-3 py-2.5 text-sm font-semibold text-brand-foreground shadow-sm transition-colors duration-150 hover:bg-brand-hover hover:shadow"
            >
                <PlusIcon className="size-4" />
                {t('quickCreate.trigger')}
            </motion.button>

            {mounted && isMobile ? (
                <Drawer
                    open={open}
                    onOpenChange={(next) => (next ? openLauncher() : closeLauncher())}
                    swipeDirection="down"
                >
                    <DrawerContent showCloseButton={false} className="max-h-[85dvh] gap-0 rounded-t-2xl p-0">
                        <MobileCreateFlow
                            key={openCount}
                            actions={createActions}
                            context={context}
                            currentUserId={currentUserId}
                            onFallback={selectAction}
                            onClose={closeLauncher}
                            onPendingChange={setPending}
                        />
                    </DrawerContent>
                </Drawer>
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
                                      initial={reduceMotion ? { opacity: 0 } : { opacity: 0, scale: 0.9, y: -8 }}
                                      animate={reduceMotion ? { opacity: 1 } : { opacity: 1, scale: 1, y: 0 }}
                                      exit={
                                          reduceMotion
                                              ? { opacity: 0, transition: { duration: 0.1, ease: easeOut } }
                                              : { opacity: 0, scale: 0.97, y: -4, transition: { duration: 0.13, ease: easeOut } }
                                      }
                                      transition={reduceMotion ? { duration: 0.12, ease: easeOut } : springJiggle}
                                      style={{
                                          top: anchor?.top ?? 0,
                                          left: anchor?.left ?? 0,
                                          width: PANEL_WIDTH,
                                          maxHeight: anchor?.maxHeight,
                                          transformOrigin: 'top left',
                                      }}
                                      className="fixed z-50 flex flex-col overflow-hidden rounded-2xl border border-border bg-popover text-popover-foreground shadow-2xl ring-1 ring-foreground/5"
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
    actions: readonly AppAction[];
    titleId: string;
    onSelect: (action: AppAction) => void;
    onClose: () => void;
    showChrome: boolean;
};

/**
 * The shared inner content of the launcher — the type selector — rendered inside both the desktop panel
 * and the mobile sheet. On desktop it draws its own header chrome (title/close); the sheet supplies its
 * own header, so chrome is suppressed there.
 */
function QuickCreatePanelBody({ actions, titleId, onSelect, onClose, showChrome }: PanelBodyProps) {
    const t = useTranslations('Actions');

    return (
        <div className="flex min-h-0 flex-col">
            {showChrome ? (
                <header className="flex shrink-0 items-center gap-2 border-b border-border px-4 py-3">
                    <h2 id={titleId} className="flex-1 text-sm font-semibold tracking-tight">
                        {t('quickCreate.title')}
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
                <TypeSelector actions={actions} onSelect={onSelect} />
            </div>
        </div>
    );
}

/**
 * The registry-driven type selector. Renders the available create actions as a roving-focus option
 * list: Arrow keys move between rows, Enter or click chooses one. The first row is auto-focused when the
 * launcher opens.
 */
const SELECTOR_LIST_VARIANTS = { hidden: {}, show: { transition: { staggerChildren: 0.035, delayChildren: 0.03 } } };
const SELECTOR_ITEM_VARIANTS = {
    hidden: { opacity: 0, y: 8, scale: 0.96 },
    show: { opacity: 1, y: 0, scale: 1, transition: springJiggle },
};

function TypeSelector({
    actions,
    onSelect,
}: {
    actions: readonly AppAction[];
    onSelect: (action: AppAction) => void;
}) {
    const t = useTranslations('Actions');
    const reduceMotion = useReducedMotion() ?? false;
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
        <motion.div
            ref={listRef}
            role="listbox"
            aria-label={t('quickCreate.title')}
            onKeyDown={handleKeyDown}
            variants={reduceMotion ? undefined : SELECTOR_LIST_VARIANTS}
            initial={reduceMotion ? undefined : 'hidden'}
            animate={reduceMotion ? undefined : 'show'}
            className="grid gap-1"
        >
            {actions.map((action, index) => {
                const Icon = action.icon;
                return (
                    <motion.button
                        key={action.id}
                        type="button"
                        role="option"
                        aria-selected={false}
                        tabIndex={index === 0 ? 0 : -1}
                        data-autofocus={index === 0 ? '' : undefined}
                        onClick={() => onSelect(action)}
                        variants={reduceMotion ? undefined : SELECTOR_ITEM_VARIANTS}
                        whileTap={reduceMotion ? undefined : { scale: 0.97 }}
                        transition={reduceMotion ? instant : springJiggle}
                        className="group flex items-center gap-3 rounded-xl px-2.5 py-2.5 text-left transition-colors duration-150 hover:bg-muted focus-visible:bg-muted focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-brand"
                    >
                        <span className="grid size-8 shrink-0 place-items-center rounded-lg bg-muted text-muted-foreground ring-1 ring-border transition-colors group-hover:bg-brand-light group-hover:text-brand-dark group-hover:ring-transparent group-focus-visible:bg-brand-light group-focus-visible:text-brand-dark">
                            {Icon ? <Icon className="size-4" /> : null}
                        </span>
                        <span className="flex-1 text-sm font-medium text-foreground">{t(action.labelKey)}</span>
                    </motion.button>
                );
            })}
        </motion.div>
    );
}

type FlowView = 'selector' | 'task' | 'note';
type FlowRefs = { persons: Contact[]; deals: Deal[]; users: User[] };

type MobileCreateFlowProps = {
    actions: readonly AppAction[];
    context: ActionContext;
    currentUserId: number | null;
    onFallback: (action: AppAction) => void;
    onClose: () => void;
    onPendingChange: (pending: boolean) => void;
};

/**
 * The mobile Quick Create surface. It keeps the launcher's bottom drawer open and morphs its content
 * between the type selector and an embedded create form (task/note) — no close/reopen, so the drawer
 * never flashes and never overlaps a second drawer. Record types without an embedded form fall back to
 * the sequenced dialog hand-off. The create-form reference data (person/deal/user rosters) loads once
 * on the first form view and is reused across forms.
 */
function MobileCreateFlow({ actions, context, currentUserId, onFallback, onClose, onPendingChange }: MobileCreateFlowProps) {
    const t = useTranslations('Actions');
    const reduceMotion = useReducedMotion() ?? false;
    const [view, setView] = useState<FlowView>('selector');
    const [direction, setDirection] = useState(1);
    const [refs, setRefs] = useState<FlowRefs | null>(null);
    const [submitting, setSubmitting] = useState(false);

    useEffect(() => {
        onPendingChange(submitting);
    }, [submitting, onPendingChange]);

    useEffect(() => {
        if (view === 'selector' || refs) return;
        let cancelled = false;
        Promise.all([getContacts({}), getDeals(), getUsers()])
            .then(([persons, deals, users]) => {
                if (!cancelled) setRefs({ persons, deals, users });
            })
            .catch(() => {
                if (!cancelled) setRefs({ persons: [], deals: [], users: [] });
            });
        return () => {
            cancelled = true;
        };
    }, [view, refs]);

    const select = useCallback(
        (action: AppAction) => {
            const kind = EMBEDDED_FORM_BY_ACTION[action.id];
            if (kind && currentUserId != null) {
                setDirection(1);
                setView(kind);
                return;
            }
            onFallback(action);
        },
        [currentUserId, onFallback],
    );

    const back = useCallback(() => {
        if (submitting) return;
        setDirection(-1);
        setView('selector');
    }, [submitting]);

    const defaults = view === 'selector' ? undefined : deriveCreateDefaults(context, view);
    const defaultPerson = refs?.persons.find((p) => p.id === defaults?.personId) ?? null;
    const defaultDeal = refs?.deals.find((d) => d.id === defaults?.dealId) ?? null;

    return (
        <div className="flex min-h-0 flex-col">
            <DrawerHeader className="flex-row items-center gap-2 border-b border-border px-4 py-3.5">
                {view !== 'selector' ? (
                    <button
                        type="button"
                        onClick={back}
                        disabled={submitting}
                        aria-label={t('quickCreate.back')}
                        className="grid size-7 shrink-0 place-items-center rounded-md text-muted-foreground transition-colors hover:bg-muted hover:text-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-brand disabled:opacity-50"
                    >
                        <ArrowLeftIcon className="size-4" />
                    </button>
                ) : null}
                {view === 'selector' ? (
                    <DrawerTitle className="flex-1 text-sm font-semibold tracking-tight">{t('quickCreate.title')}</DrawerTitle>
                ) : (
                    <>
                        <DrawerTitle className="sr-only">{t(`create.${view}`)}</DrawerTitle>
                        <span aria-hidden className="flex-1" />
                    </>
                )}
                <DrawerDescription className="sr-only">{t('quickCreate.description')}</DrawerDescription>
                <button
                    type="button"
                    onClick={onClose}
                    disabled={submitting}
                    aria-label={t('quickCreate.close')}
                    className="grid size-7 shrink-0 place-items-center rounded-md text-muted-foreground transition-colors hover:bg-muted hover:text-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-brand disabled:opacity-50"
                >
                    <XMarkIcon className="size-4" />
                </button>
            </DrawerHeader>

            <div className="min-h-0 overflow-y-auto">
                <MorphingBody viewKey={view} direction={direction} reduceMotion={reduceMotion}>
                    {view === 'selector' ? (
                        <div className="px-4 pb-6 pt-4">
                            <TypeSelector actions={actions} onSelect={select} />
                        </div>
                    ) : refs === null || currentUserId == null ? (
                        <div className="grid min-h-[28rem] place-items-center">
                            <Loader2Icon className="size-5 animate-spin text-muted-foreground" />
                        </div>
                    ) : view === 'task' ? (
                        <TaskDialogForm
                            persons={refs.persons}
                            deals={refs.deals}
                            users={refs.users}
                            currentUserId={currentUserId}
                            defaultPerson={defaultPerson}
                            defaultDeal={defaultDeal}
                            defaultDueDate=""
                            defaultDescription=""
                            onSubmittingChange={setSubmitting}
                            onCancel={back}
                            onClose={onClose}
                        />
                    ) : (
                        <NoteDialogForm
                            note={null}
                            persons={refs.persons}
                            deals={refs.deals}
                            currentUserId={currentUserId}
                            defaultPerson={defaultPerson}
                            defaultDeal={defaultDeal}
                            defaultContent=""
                            onSubmittingChange={setSubmitting}
                            onCancel={back}
                            onClose={onClose}
                        />
                    )}
                </MorphingBody>
            </div>
        </div>
    );
}

const MORPH_VARIANTS = {
    enter: (d: number) => ({ opacity: 0, x: d >= 0 ? 12 : -12, filter: 'blur(3px)' }),
    center: { opacity: 1, x: 0, filter: 'blur(0px)' },
    exit: (d: number) => ({ opacity: 0, x: d >= 0 ? -12 : 12, filter: 'blur(3px)' }),
    still: { opacity: 0, x: 0, filter: 'blur(0px)' },
};

const MORPH_CONTENT_TRANSITION = {
    x: springSnappy,
    opacity: { duration: 0.16, ease: easeOut },
    filter: { duration: 0.16, ease: easeOut },
};

/**
 * Wraps the morphing drawer content: crossfades/slides between the selector and a form while the drawer's
 * height springs to the measured height of the active view, so the swap reads as one surface reshaping
 * rather than two panels appearing. The height stays observed (later growth from comboboxes, field errors
 * or the editor keeps the sheet sized), and the very first sizing is instant so it never competes with the
 * drawer's own slide-up.
 */
function MorphingBody({
    viewKey,
    direction,
    reduceMotion,
    children,
}: {
    viewKey: string;
    direction: number;
    reduceMotion: boolean;
    children: React.ReactNode;
}) {
    const measureRef = useRef<HTMLDivElement | null>(null);
    const [height, setHeight] = useState<number | 'auto'>('auto');
    const [animateHeight, setAnimateHeight] = useState(false);

    useLayoutEffect(() => {
        const node = measureRef.current;
        if (!node) return;
        const measure = () => setHeight(node.offsetHeight);
        measure();
        const raf = requestAnimationFrame(() => setAnimateHeight(true));
        const observer = new ResizeObserver(measure);
        observer.observe(node);
        return () => {
            cancelAnimationFrame(raf);
            observer.disconnect();
        };
    }, []);

    return (
        <motion.div
            animate={reduceMotion ? undefined : { height }}
            transition={animateHeight ? springSmooth : instant}
            style={{ overflow: 'hidden' }}
        >
            <div ref={measureRef} className="relative">
                <AnimatePresence mode="popLayout" initial={false} custom={direction}>
                    <motion.div
                        key={viewKey}
                        custom={direction}
                        variants={MORPH_VARIANTS}
                        initial={reduceMotion ? 'still' : 'enter'}
                        animate="center"
                        exit={reduceMotion ? 'still' : 'exit'}
                        transition={reduceMotion ? { duration: 0.12 } : MORPH_CONTENT_TRANSITION}
                    >
                        {children}
                    </motion.div>
                </AnimatePresence>
            </div>
        </motion.div>
    );
}
