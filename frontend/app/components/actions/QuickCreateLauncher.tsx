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
} from '@/components/ui/drawer';
import MobileCreateFlow, {
    type MobileCreateFlowHandle,
} from '@/app/components/actions/MobileCreateFlow';
import QuickCreateTypeSelector from '@/app/components/actions/QuickCreateTypeSelector';
import { useIsMobile } from '@/app/hooks/useIsMobile';
import { cn } from '@/lib/utils';
import { Tooltip, TooltipTrigger, TooltipContent } from '@/components/ui/tooltip';
import { useActions, useAvailableActions } from '@/app/hooks/useActions';
import { easeOut, instant, springJiggle } from '@/app/lib/motion';
import type { AppAction } from '@/app/lib/actions/types';

const PANEL_WIDTH = 380;
const PANEL_GAP = 12;
const VIEWPORT_MARGIN = 16;
const MOBILE_HANDOFF_DELAY_MS = 300;

type Anchor = { top: number; left: number; width: number; maxHeight: number };

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
export default function QuickCreateLauncher({ compact = false }: { compact?: boolean } = {}) {
    const t = useTranslations('Actions');
    const { run, context } = useActions();
    const createActions = useAvailableActions('create');
    const reduceMotion = useReducedMotion() ?? false;

    const mounted = useIsClient();
    const isMobile = useIsMobile();
    const [open, setOpen] = useState(false);
    const [anchor, setAnchor] = useState<Anchor | null>(null);
    const [openCount, setOpenCount] = useState(0);
    const [expanded, setExpanded] = useState(false);
    const [presentationIsMobile, setPresentationIsMobile] = useState(isMobile);

    const triggerRef = useRef<HTMLButtonElement>(null);
    const rootRef = useRef<HTMLDivElement>(null);
    const panelRef = useRef<HTMLDivElement>(null);
    const pendingRef = useRef(false);
    const mobileCreateFlowRef = useRef<MobileCreateFlowHandle>(null);
    const titleId = useId();

    const currentUserId = context.user?.id ?? null;

    const computeAnchor = useCallback((): Anchor | null => {
        const rect = rootRef.current?.getBoundingClientRect();
        if (!rect) return null;
        const width = Math.min(PANEL_WIDTH, window.innerWidth - (VIEWPORT_MARGIN * 2));
        const top = Math.max(VIEWPORT_MARGIN, rect.top);
        const preferredLeft = rect.right + PANEL_GAP;
        const left = Math.min(
            Math.max(VIEWPORT_MARGIN, preferredLeft),
            window.innerWidth - VIEWPORT_MARGIN - width,
        );
        return {
            top,
            left,
            width,
            maxHeight: window.innerHeight - top - VIEWPORT_MARGIN,
        };
    }, []);

    const closeLauncher = useCallback(() => {
        if (pendingRef.current) return;
        setOpen(false);
        const trigger = triggerRef.current;
        if (trigger) requestAnimationFrame(() => trigger.focus());
    }, []);

    const requestMobileClose = useCallback(() => {
        if (mobileCreateFlowRef.current) {
            mobileCreateFlowRef.current.requestClose();
            return;
        }
        closeLauncher();
    }, [closeLauncher]);

    const openLauncher = useCallback(() => {
        pendingRef.current = false;
        setExpanded(false);
        setPresentationIsMobile(isMobile);
        setOpenCount((count) => count + 1);
        setAnchor(computeAnchor());
        setOpen(true);
    }, [computeAnchor, isMobile]);

    const activeIsMobile = presentationIsMobile;

    useEffect(() => {
        const onOpenRequest = () => openLauncher();
        window.addEventListener('connex:open-quick-create', onOpenRequest);
        return () => window.removeEventListener('connex:open-quick-create', onOpenRequest);
    }, [openLauncher]);

    const handlePendingChange = useCallback((next: boolean) => {
        pendingRef.current = next;
    }, []);

    useEffect(() => {
        if (!open || activeIsMobile) return;
        const reflow = () => setAnchor(computeAnchor());
        window.addEventListener('resize', reflow);
        window.addEventListener('scroll', reflow, true);
        return () => {
            window.removeEventListener('resize', reflow);
            window.removeEventListener('scroll', reflow, true);
        };
    }, [open, activeIsMobile, computeAnchor]);

    useEffect(() => {
        if (!open || activeIsMobile) return;
        const raf = requestAnimationFrame(() => {
            const target = panelRef.current?.querySelector<HTMLElement>('[data-autofocus]');
            target?.focus();
        });
        return () => cancelAnimationFrame(raf);
    }, [open, activeIsMobile]);

    const selectAction = useCallback(
        (action: AppAction) => {
            closeLauncher();
            if (activeIsMobile) {
                window.setTimeout(() => {
                    void run(action.id, { source: 'menu' });
                }, MOBILE_HANDOFF_DELAY_MS);
                return;
            }
            void run(action.id, { source: 'menu' });
        },
        [closeLauncher, run, activeIsMobile],
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
            showChrome={!activeIsMobile}
        />
    );

    const launchButton = (
        <motion.button
            ref={triggerRef}
            type="button"
            aria-haspopup="dialog"
            aria-expanded={open}
            aria-label={t('quickCreate.trigger')}
            onClick={() => (open ? (activeIsMobile ? requestMobileClose() : closeLauncher()) : openLauncher())}
            whileTap={reduceMotion ? undefined : { scale: 0.95 }}
            transition={reduceMotion ? instant : springJiggle}
            className={cn(
                'inline-flex items-center justify-center rounded-xl bg-brand text-brand-foreground shadow-sm transition-colors duration-(--motion-micro) hover:bg-brand-hover hover:shadow',
                compact ? 'mx-auto size-9' : 'w-full gap-2 px-3 py-2.5 text-sm font-semibold',
            )}
        >
            <PlusIcon className="size-4" />
            {!compact && t('quickCreate.trigger')}
        </motion.button>
    );

    return (
        <div ref={rootRef} className="mb-5 shrink-0">
            {compact ? (
                <Tooltip>
                    <TooltipTrigger asChild>{launchButton}</TooltipTrigger>
                    <TooltipContent side="right">{t('quickCreate.trigger')}</TooltipContent>
                </Tooltip>
            ) : (
                launchButton
            )}

            {mounted && activeIsMobile ? (
                <Drawer
                    open={open}
                    onOpenChange={(next) => (next ? openLauncher() : requestMobileClose())}
                    swipeDirection="down"
                >
                    <DrawerContent
                        showCloseButton={false}
                        className={cn(
                            'gap-0 p-0 transition-[transform,max-height,width,margin,border-radius] duration-(--motion-standard) ease-calm',
                            expanded
                                ? 'h-[100dvh] max-h-[100dvh] w-full rounded-t-2xl data-ending-style:duration-(--motion-standard)'
                                : 'mb-3 max-h-[82dvh] w-[calc(100%-1.5rem)] rounded-3xl',
                        )}
                    >
                        <DrawerPullBar
                            expanded={expanded}
                            onExpand={() => setExpanded(true)}
                            onCollapse={() => setExpanded(false)}
                            onDismiss={requestMobileClose}
                        />
                        <MobileCreateFlow
                            ref={mobileCreateFlowRef}
                            key={openCount}
                            actions={createActions}
                            context={context}
                            currentUserId={currentUserId}
                            onFallback={selectAction}
                            onClose={closeLauncher}
                            onPendingChange={handlePendingChange}
                        />
                    </DrawerContent>
                </Drawer>
            ) : null}

            {mounted && !activeIsMobile
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
                                          width: anchor?.width ?? PANEL_WIDTH,
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
                <QuickCreateTypeSelector actions={actions} onSelect={onSelect} />
            </div>
        </div>
    );
}

const PULL_BAR_DRAG_THRESHOLD = 36;

/**
 * The grab bar pinned to the top of the mobile Quick Create drawer. Drag it up (or tap) to expand the
 * drawer to full screen; drag it down (or tap while expanded) to collapse back to the floating sheet;
 * dragging down from the collapsed state dismisses the drawer. Its pointer handling is self-contained
 * (captured + stopped) so it never competes with the drawer's own swipe-to-dismiss.
 */
function DrawerPullBar({
    expanded,
    onExpand,
    onCollapse,
    onDismiss,
}: {
    expanded: boolean;
    onExpand: () => void;
    onCollapse: () => void;
    onDismiss: () => void;
}) {
    const startY = useRef<number | null>(null);

    const handlePointerDown = (event: React.PointerEvent<HTMLButtonElement>) => {
        event.stopPropagation();
        startY.current = event.clientY;
        event.currentTarget.setPointerCapture(event.pointerId);
    };

    const handlePointerUp = (event: React.PointerEvent<HTMLButtonElement>) => {
        if (startY.current == null) return;
        const dy = event.clientY - startY.current;
        startY.current = null;
        if (dy < -PULL_BAR_DRAG_THRESHOLD) {
            onExpand();
        } else if (dy > PULL_BAR_DRAG_THRESHOLD) {
            if (expanded) onCollapse();
            else onDismiss();
        } else if (expanded) {
            onCollapse();
        } else {
            onExpand();
        }
    };

    return (
        <button
            type="button"
            aria-label={expanded ? 'Collapse' : 'Expand'}
            onPointerDown={handlePointerDown}
            onPointerUp={handlePointerUp}
            className="flex shrink-0 touch-none cursor-grab justify-center py-2.5 outline-none active:cursor-grabbing"
        >
            <span aria-hidden className="h-1.5 w-10 rounded-full bg-border transition-colors" />
        </button>
    );
}
