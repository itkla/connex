'use client';

import { useCallback, useEffect, useId, useRef, useState, useSyncExternalStore } from 'react';
import { createPortal } from 'react-dom';
import { useTranslations } from 'next-intl';
import { AnimatePresence, motion, useReducedMotion } from 'motion/react';
import { PlusIcon } from '@heroicons/react/16/solid';
import { XMarkIcon } from '@heroicons/react/24/outline';

import {
    Drawer,
    DrawerContent,
    DrawerHeader,
    DrawerTitle,
    DrawerDescription,
} from '@/components/ui/drawer';
import { useIsMobile } from '@/app/hooks/useIsMobile';
import { useActions, useAvailableActions } from '@/app/hooks/useActions';
import { easeOut, instant, springJiggle } from '@/app/lib/motion';
import type { AppAction } from '@/app/lib/actions/types';

const PANEL_WIDTH = 380;
const PANEL_GAP = 12;
const VIEWPORT_MARGIN = 16;
const MOBILE_HANDOFF_DELAY_MS = 300;

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
    const { run } = useActions();
    const createActions = useAvailableActions('create');
    const reduceMotion = useReducedMotion() ?? false;

    const mounted = useIsClient();
    const isMobile = useIsMobile();
    const [open, setOpen] = useState(false);
    const [anchor, setAnchor] = useState<Anchor | null>(null);

    const triggerRef = useRef<HTMLButtonElement>(null);
    const rootRef = useRef<HTMLDivElement>(null);
    const panelRef = useRef<HTMLDivElement>(null);
    const titleId = useId();

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
        setOpen(false);
        const trigger = triggerRef.current;
        if (trigger) requestAnimationFrame(() => trigger.focus());
    }, []);

    const openLauncher = useCallback(() => {
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
                <Drawer open={open} onOpenChange={(next) => (next ? openLauncher() : closeLauncher())} swipeDirection="down">
                    <DrawerContent showCloseButton={false} className="max-h-[85dvh] gap-0 rounded-t-2xl p-0">
                        <DrawerHeader className="flex-row items-center gap-2 border-b border-border px-5 py-4">
                            <DrawerTitle className="flex-1">{t('quickCreate.title')}</DrawerTitle>
                            <DrawerDescription className="sr-only">{t('quickCreate.description')}</DrawerDescription>
                            <button
                                type="button"
                                onClick={closeLauncher}
                                aria-label={t('quickCreate.close')}
                                className="grid size-7 shrink-0 place-items-center rounded-md text-muted-foreground transition-colors hover:bg-muted hover:text-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-brand"
                            >
                                <XMarkIcon className="size-4" />
                            </button>
                        </DrawerHeader>
                        <div className="overflow-y-auto px-5 pb-6 pt-4">{body}</div>
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
