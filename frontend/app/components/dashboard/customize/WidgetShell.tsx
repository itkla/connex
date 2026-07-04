'use client';

import { type ReactNode } from 'react';
import {
    ArrowsPointingInIcon,
    ArrowsPointingOutIcon,
    XMarkIcon,
} from '@heroicons/react/24/outline';

import { cn } from '@/lib/utils';
import type { DashboardWidgetSpan } from '@/app/lib/types';

/**
 * The frame around a single dashboard widget: an uppercase section header (matching the static
 * dashboard's `SectionHeader`) above the widget body. In read mode the header shows the widget's
 * optional "view all" action; in edit mode it shows the drag handle plus width and remove controls,
 * and the whole widget gets a dashed outline to signal it is arrangeable. Presentational only — the
 * drag handle node and control callbacks are supplied by the grid.
 */
export default function WidgetShell({
    title,
    action,
    editMode,
    isDragging,
    dragHandle,
    span,
    canToggleWidth,
    widthLabel,
    removeLabel,
    onToggleWidth,
    onRemove,
    children,
}: {
    title: string;
    action?: ReactNode;
    editMode: boolean;
    isDragging?: boolean;
    dragHandle?: ReactNode;
    span: DashboardWidgetSpan;
    canToggleWidth: boolean;
    widthLabel: string;
    removeLabel: string;
    onToggleWidth?: () => void;
    onRemove?: () => void;
    children: ReactNode;
}) {
    return (
        <div
            className={cn(
                'flex h-full flex-col rounded-2xl outline-1 outline-dashed outline-offset-4 outline-transparent transition-[outline-color,opacity] duration-200 motion-reduce:transition-none',
                editMode && 'outline-border hover:outline-brand/40',
                isDragging && 'opacity-40',
            )}
        >
            <div className="mb-3 flex h-8 items-center justify-between gap-2">
                <div className="flex min-w-0 items-center gap-1.5 px-6">
                    {editMode && dragHandle}
                    <h2 className="truncate text-xs font-medium uppercase tracking-[0.12em] text-muted-foreground">
                        {title}
                    </h2>
                </div>
                <div className="flex shrink-0 items-center gap-1 px-1">
                    {editMode ? (
                        <>
                            {canToggleWidth && (
                                <button
                                    type="button"
                                    onClick={onToggleWidth}
                                    aria-pressed={span === 2}
                                    aria-label={widthLabel}
                                    title={widthLabel}
                                    className="rounded-md p-1.5 text-muted-foreground transition-[background-color,color,transform] duration-150 hover:bg-muted hover:text-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring active:translate-y-px motion-reduce:transition-none"
                                >
                                    {span === 2 ? (
                                        <ArrowsPointingInIcon className="size-4" />
                                    ) : (
                                        <ArrowsPointingOutIcon className="size-4" />
                                    )}
                                </button>
                            )}
                            <button
                                type="button"
                                onClick={onRemove}
                                aria-label={removeLabel}
                                title={removeLabel}
                                className="rounded-md p-1.5 text-muted-foreground transition-[background-color,color,transform] duration-150 hover:bg-destructive/10 hover:text-destructive focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring active:translate-y-px motion-reduce:transition-none"
                            >
                                <XMarkIcon className="size-4" />
                            </button>
                        </>
                    ) : (
                        action
                    )}
                </div>
            </div>
            <div className="min-h-0 flex-1">{children}</div>
        </div>
    );
}
