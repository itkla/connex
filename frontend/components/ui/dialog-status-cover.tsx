'use client';

import { useRef } from 'react';
import { cn } from '@/lib/utils';
import PixelCard from '@/components/PixelCard';

// Shared "dialog system" primitives used across create/entry dialogs.
// The status cover is a pixel band at the top of a dialog that mirrors submission state:
// transparent (idle) -> gray pixels (loading) -> green (success) / red (error).

export type DialogStatus = 'idle' | 'loading' | 'success' | 'error';

const PIXEL_GRAY = '#e5e7eb,#cbd5e1,#94a3b8';
const PIXEL_GREEN = '#bbf7d0,#86efac,#73d200';
const PIXEL_RED = '#fecaca,#f87171,#ef4444';

/** Derive the cover status from the usual dialog flags. */
export function resolveDialogStatus({
    isLoading,
    hasErrors,
    isSuccess,
}: {
    isLoading?: boolean;
    hasErrors?: boolean;
    isSuccess?: boolean;
}): DialogStatus {
    if (isLoading) return 'loading';
    if (hasErrors) return 'error';
    if (isSuccess) return 'success';
    return 'idle';
}

/**
 * Pixel status band for the top of a dialog. Drop in as the first child of a
 * `DialogContent` that uses `gap-0 overflow-hidden p-0`, then put the dialog body
 * in a `px-6 pb-6` wrapper below it.
 *
 * Destructive flows can pass `dangerous` so success-green is never shown (red-only).
 */
export function DialogStatusCover({
    status,
    dangerous = false,
    className,
}: {
    status: DialogStatus;
    dangerous?: boolean;
    className?: string;
}) {
    // Idle keeps the previous palette so pixels fade out smoothly instead of snapping.
    const lastColorsRef = useRef(PIXEL_GRAY);
    let colors = lastColorsRef.current;
    if (status === 'loading') colors = PIXEL_GRAY;
    else if (status === 'success') colors = dangerous ? PIXEL_RED : PIXEL_GREEN;
    else if (status === 'error') colors = PIXEL_RED;
    lastColorsRef.current = colors;

    return (
        <div aria-hidden className={cn('relative h-24 overflow-hidden', className)}>
            <PixelCard
                active={status !== 'idle'}
                colors={colors}
                gap={5}
                speed={40}
                noFocus
                className="pointer-events-none absolute inset-0 aspect-auto! h-full! w-full! rounded-none! border-0!"
            />
            <div className="absolute inset-x-0 bottom-0 h-1/2 bg-gradient-to-t from-popover to-transparent" />
        </div>
    );
}

// Shared field styling for dialog inputs. `fieldInputClass` is padding-free so a leading-icon
// variant composes cleanly via cn(): cn(fieldInputClass, 'pl-9 pr-3', err && fieldErrorClass).
export const fieldInputClass =
    'w-full rounded-lg bg-muted py-2 text-sm text-foreground placeholder:text-muted-foreground outline-none ring-1 ring-border transition focus:ring-2 focus:ring-brand';
export const fieldErrorClass = 'ring-2 ring-destructive focus:ring-destructive';
export const fieldWarnClass = 'ring-2 ring-amber-500 focus:ring-amber-500';
// Requires the input's wrapper to be `group relative`.
export const fieldLeadIconClass =
    'pointer-events-none absolute left-3 top-1/2 size-4 -translate-y-1/2 text-muted-foreground transition-colors group-focus-within:text-brand';
