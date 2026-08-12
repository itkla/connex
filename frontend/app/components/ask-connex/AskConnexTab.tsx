'use client';

import { useRef, type Ref } from 'react';
import { SparklesIcon } from '@heroicons/react/24/outline';

import { Tooltip, TooltipContent, TooltipTrigger } from '@/components/ui/tooltip';

const DRAG_THRESHOLD = 24;

/** Desktop right-edge pull-tab that opens or closes the persistent Ask Connex panel. */
export default function AskConnexTab({
    buttonRef,
    label,
    closeLabel,
    open,
    working,
    onOpen,
    onClose,
}: {
    buttonRef: Ref<HTMLButtonElement>;
    label: string;
    closeLabel: string;
    open: boolean;
    working: boolean;
    onOpen: () => void;
    onClose: () => void;
}) {
    const pointerStart = useRef<{ id: number; x: number } | null>(null);
    const suppressClick = useRef(false);
    const accessibleLabel = open ? closeLabel : label;

    return (
        <Tooltip>
            <TooltipTrigger asChild>
                <button
                    ref={buttonRef}
                    type="button"
                    aria-label={accessibleLabel}
                    aria-controls="ask-connex-desktop-panel"
                    aria-expanded={open}
                    onClick={() => {
                        if (suppressClick.current) {
                            suppressClick.current = false;
                            return;
                        }
                        if (open) onClose();
                        else onOpen();
                    }}
                    onPointerDown={(event) => {
                        if (pointerStart.current) return;
                        pointerStart.current = { id: event.pointerId, x: event.clientX };
                        event.currentTarget.setPointerCapture(event.pointerId);
                    }}
                    onPointerUp={(event) => {
                        const start = pointerStart.current;
                        if (!start || start.id !== event.pointerId) return;
                        pointerStart.current = null;
                        if (event.currentTarget.hasPointerCapture(event.pointerId)) {
                            event.currentTarget.releasePointerCapture(event.pointerId);
                        }
                        const distance = start.x - event.clientX;
                        if ((!open && distance >= DRAG_THRESHOLD) || (open && distance <= -DRAG_THRESHOLD)) {
                            suppressClick.current = true;
                            if (open) onClose();
                            else onOpen();
                        }
                    }}
                    onPointerCancel={(event) => {
                        if (pointerStart.current?.id !== event.pointerId) return;
                        pointerStart.current = null;
                        suppressClick.current = false;
                    }}
                    className="absolute top-1/3 left-0 z-20 hidden h-24 w-7 -translate-x-full touch-none flex-col items-center justify-center gap-3 rounded-l-xl border border-r-0 border-border bg-popover text-popover-foreground shadow-sm outline-none transition-colors hover:bg-muted focus-visible:ring-2 focus-visible:ring-ring active:bg-muted md:flex"
                >
                    <span aria-hidden className="h-px w-3 bg-border" />
                    <span className="relative">
                        <SparklesIcon className="size-4" />
                        {working ? (
                            <span className="absolute -top-1 -right-1 size-1.5 rounded-full bg-primary" />
                        ) : null}
                    </span>
                    <span aria-hidden className="h-px w-3 bg-border" />
                </button>
            </TooltipTrigger>
            <TooltipContent side="left">{accessibleLabel}</TooltipContent>
        </Tooltip>
    );
}
