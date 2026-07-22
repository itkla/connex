'use client';

import Link from 'next/link';
import { type ReactNode, useCallback, useEffect, useRef, useState } from 'react';
import { useTranslations } from 'next-intl';
import { AnimatePresence, motion, useReducedMotion } from 'motion/react';
import { ArrowPathIcon, PlusIcon, Squares2X2Icon } from '@heroicons/react/24/outline';
import {
    type SortableGridMessages,
} from '@/app/components/layout/SortableGrid';
import SortableGrid from '@/app/components/layout/SortableGrid';
import { Button } from '@/components/ui/button';
import {
    Drawer,
    DrawerContent,
    DrawerDescription,
    DrawerHeader,
    DrawerTitle,
} from '@/components/ui/drawer';
import { toastError } from '@/app/lib/toast';
import { resetDashboardLayout, saveDashboardLayout } from '@/app/lib/api';
import type { DashboardWidgetInstance, DashboardWidgetType } from '@/app/lib/types';

import WidgetShell from './WidgetShell';
import { ALL_WIDGET_TYPES, WIDGET_META, defaultWidgets, newWidgetId } from './dashboardWidgets';

const PERSIST_DEBOUNCE_MS = 400;
const EASE_OUT: [number, number, number, number] = [0.23, 1, 0.32, 1];

type WidgetNodes = Record<DashboardWidgetType, ReactNode>;
type SaveJob = { kind: 'save'; widgets: DashboardWidgetInstance[] } | { kind: 'reset' };

/**
 * The customizable dashboard: renders the user's widgets in a draggable 2-column grid and, in edit
 * mode, lets them reorder (drag), resize (1↔2 columns), remove, add (tray), and reset. Widget
 * bodies are rendered by the server component and passed in via `nodes`, so no data fetching lives
 * here. Layout changes persist optimistically (debounced) and roll back to the last saved state on
 * failure.
 */
export default function DashboardGrid({
    initialWidgets,
    nodes,
    layoutErrored = false,
}: {
    initialWidgets: DashboardWidgetInstance[];
    nodes: WidgetNodes;
    layoutErrored?: boolean;
}) {
    const t = useTranslations('DashboardCustomize');
    const tp = useTranslations('DashboardPage');
    const reduce = useReducedMotion() ?? false;

    const [widgets, setWidgets] = useState<DashboardWidgetInstance[]>(initialWidgets);
    const [editMode, setEditMode] = useState(false);
    const [trayOpen, setTrayOpen] = useState(false);
    const [activeId, setActiveId] = useState<string | null>(null);

    const incomingKey = JSON.stringify(initialWidgets);
    const [syncedKey, setSyncedKey] = useState(incomingKey);
    if (syncedKey !== incomingKey && activeId == null && !layoutErrored) {
        setSyncedKey(incomingKey);
        setWidgets(initialWidgets);
    }

    const lastSavedRef = useRef<DashboardWidgetInstance[]>(initialWidgets);
    const desiredRef = useRef<SaveJob | null>(null);
    const savingRef = useRef(false);
    const saveTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
    const customizeButtonRef = useRef<HTMLButtonElement>(null);

    const runQueue = useCallback(async () => {
        if (savingRef.current) return;
        savingRef.current = true;
        try {
            while (desiredRef.current) {
                const job = desiredRef.current;
                desiredRef.current = null;
                try {
                    if (job.kind === 'reset') {
                        await resetDashboardLayout();
                        lastSavedRef.current = defaultWidgets();
                    } else {
                        await saveDashboardLayout({ version: 1, widgets: job.widgets });
                        lastSavedRef.current = job.widgets;
                    }
                } catch {
                    if (!desiredRef.current) {
                        setWidgets(lastSavedRef.current);
                        toastError(t('saveFailed'));
                    }
                }
            }
        } finally {
            savingRef.current = false;
        }
    }, [t]);

    const schedule = useCallback(
        (job: SaveJob) => {
            desiredRef.current = job;
            if (saveTimer.current) clearTimeout(saveTimer.current);
            saveTimer.current = setTimeout(() => void runQueue(), PERSIST_DEBOUNCE_MS);
        },
        [runQueue],
    );

    const mutate = useCallback(
        (next: DashboardWidgetInstance[]) => {
            setWidgets(next);
            schedule({ kind: 'save', widgets: next });
        },
        [schedule],
    );

    useEffect(
        () => () => {
            if (saveTimer.current) clearTimeout(saveTimer.current);
        },
        [],
    );

    const titleFor = useCallback(
        (type: DashboardWidgetType) => tp(WIDGET_META[type].titleKey),
        [tp],
    );
    const nameFor = useCallback(
        (widget: DashboardWidgetInstance) => {
            return titleFor(widget.type);
        },
        [titleFor],
    );

    const toggleWidth = (id: string) => {
        mutate(
            widgets.map((w) => {
                if (w.id !== id) return w;
                const next = WIDGET_META[w.type].allowedSpans.find((s) => s !== w.span) ?? w.span;
                return { ...w, span: next };
            }),
        );
    };
    const removeWidget = (id: string) => {
        mutate(widgets.filter((w) => w.id !== id));
        customizeButtonRef.current?.focus();
    };
    const addWidget = (type: DashboardWidgetType) =>
        mutate([...widgets, { id: newWidgetId(type), type, span: WIDGET_META[type].defaultSpan }]);

    const resetLayout = () => {
        setWidgets(defaultWidgets());
        schedule({ kind: 'reset' });
    };

    const presentTypes = new Set(widgets.map((w) => w.type));
    const availableTypes = ALL_WIDGET_TYPES.filter((type) => !presentTypes.has(type));
    const actionFor = (type: DashboardWidgetType): ReactNode => {
        const meta = WIDGET_META[type];
        if (!meta.actionHref || !meta.actionLabelKey) return null;
        return (
            <Link href={meta.actionHref} className="text-xs text-brand hover:text-brand-hover">
                {tp(meta.actionLabelKey)}
            </Link>
        );
    };

    const messages: SortableGridMessages = {
        instructions: t('a11yInstructions'),
        handleLabel: (name) => t('dragHandle', { name }),
        lifted: (name) => t('a11yLifted', { name }),
        over: (name, target) => t('a11yOver', { name, target }),
        dropped: (name, target) => t('a11yDropped', { name, target }),
        cancelled: (name) => t('a11yCancelled', { name }),
    };

    return (
        <div className="flex flex-col gap-4">
            <div className="flex items-center justify-end gap-2 px-1">
                <AnimatePresence>
                    {editMode && (
                        <motion.div
                            className="flex items-center gap-2"
                            initial={reduce ? false : { opacity: 0, x: 8 }}
                            animate={reduce ? undefined : { opacity: 1, x: 0 }}
                            exit={reduce ? { opacity: 0 } : { opacity: 0, x: 8 }}
                            transition={{ duration: 0.2, ease: EASE_OUT }}
                        >
                            <Button variant="ghost" size="sm" onClick={resetLayout}>
                                <ArrowPathIcon />
                                {t('reset')}
                            </Button>
                            <Button
                                variant="outline"
                                size="sm"
                                onClick={() => setTrayOpen(true)}
                                disabled={availableTypes.length === 0}
                            >
                                <PlusIcon />
                                {t('addWidget')}
                            </Button>
                        </motion.div>
                    )}
                </AnimatePresence>
                <Button
                    ref={customizeButtonRef}
                    variant={editMode ? 'default' : 'outline'}
                    size="sm"
                    onClick={() => setEditMode((v) => !v)}
                    aria-pressed={editMode}
                >
                    {!editMode && <Squares2X2Icon />}
                    {editMode ? t('done') : t('customize')}
                </Button>
            </div>

            {widgets.length === 0 ? (
                <div className="flex flex-col items-center justify-center gap-3 rounded-2xl border border-dashed border-border bg-card/40 px-6 py-16 text-center">
                    <Squares2X2Icon className="size-6 text-muted-foreground" />
                    <div>
                        <p className="text-sm font-medium text-foreground">{t('emptyTitle')}</p>
                        <p className="mt-1 text-sm text-muted-foreground">{t('emptyBody')}</p>
                    </div>
                    <Button
                        variant="outline"
                        size="sm"
                        onClick={() => {
                            setEditMode(true);
                            setTrayOpen(true);
                        }}
                    >
                        <PlusIcon />
                        {t('addWidget')}
                    </Button>
                </div>
            ) : (
                <SortableGrid
                    items={widgets}
                    getLabel={nameFor}
                    onChange={mutate}
                    onActiveIdChange={setActiveId}
                    messages={messages}
                    reduceMotion={reduce}
                    gridClassName="grid grid-cols-1 gap-6 lg:grid-cols-2"
                    itemClassName={(widget) => widget.span === 2 ? 'lg:col-span-2' : undefined}
                    renderItem={(widget, { dragHandle, isDragging }) => (
                        <WidgetShell
                            title={titleFor(widget.type)}
                            action={actionFor(widget.type)}
                            editMode={editMode}
                            isDragging={isDragging}
                            dragHandle={editMode ? dragHandle : null}
                            span={widget.span}
                            canToggleWidth={WIDGET_META[widget.type].allowedSpans.length > 1}
                            widthLabel={widget.span === 2 ? t('collapse') : t('expand')}
                            removeLabel={t('remove', { name: titleFor(widget.type) })}
                            onToggleWidth={() => toggleWidth(widget.id)}
                            onRemove={() => removeWidget(widget.id)}
                        >
                            {nodes[widget.type]}
                        </WidgetShell>
                    )}
                    renderOverlay={(widget) => (
                            <div className={reduce ? 'cursor-grabbing' : 'cursor-grabbing scale-[1.02] shadow-2xl'}>
                                <WidgetShell
                                    title={titleFor(widget.type)}
                                    editMode={false}
                                    span={widget.span}
                                    canToggleWidth={false}
                                    widthLabel=""
                                    removeLabel=""
                                >
                                    {nodes[widget.type]}
                                </WidgetShell>
                            </div>
                        )}
                />
            )}

            <Drawer open={trayOpen} onOpenChange={setTrayOpen} swipeDirection="right">
                <DrawerContent className="w-full gap-0 sm:max-w-sm">
                    <DrawerHeader>
                        <DrawerTitle>{t('trayTitle')}</DrawerTitle>
                        <DrawerDescription>{t('trayDescription')}</DrawerDescription>
                    </DrawerHeader>
                    <div className="flex flex-col gap-2 overflow-y-auto p-4">
                        {availableTypes.length === 0 ? (
                            <p className="rounded-xl border border-dashed border-border px-4 py-8 text-center text-sm text-muted-foreground">
                                {t('trayEmpty')}
                            </p>
                        ) : (
                            availableTypes.map((type) => (
                                <div
                                    key={type}
                                    className="flex items-center justify-between gap-3 rounded-xl border border-border bg-card px-4 py-3"
                                >
                                    <span className="truncate text-sm font-medium text-foreground">
                                        {titleFor(type)}
                                    </span>
                                    <Button variant="outline" size="sm" onClick={() => addWidget(type)}>
                                        <PlusIcon />
                                        {t('add')}
                                    </Button>
                                </div>
                            ))
                        )}
                    </div>
                </DrawerContent>
            </Drawer>
        </div>
    );
}
