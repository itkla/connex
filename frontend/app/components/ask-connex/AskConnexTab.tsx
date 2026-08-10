'use client';

import { useRef } from 'react';
import { SparklesIcon } from '@heroicons/react/24/outline';
import { motion, useReducedMotion } from 'motion/react';

import { easeOut, instant } from '@/app/lib/motion';
import { Tooltip, TooltipContent, TooltipTrigger } from '@/components/ui/tooltip';

const OPEN_DRAG_THRESHOLD = 24;

/** Desktop right-edge pull-tab that opens the persistent Ask Connex drawer. */
export default function AskConnexTab({
    label,
    working,
    onOpen,
}: {
    label: string;
    working: boolean;
    onOpen: () => void;
}) {
    const reduceMotion = useReducedMotion() ?? false;
    const pointerStart = useRef<{ id: number; x: number } | null>(null);

    return (
        <Tooltip>
            <TooltipTrigger asChild>
                <motion.button
                    type="button"
                    aria-label={label}
                    onClick={onOpen}
                    onPointerDown={(event) => {
                        pointerStart.current = { id: event.pointerId, x: event.clientX };
                        event.currentTarget.setPointerCapture(event.pointerId);
                    }}
                    onPointerUp={(event) => {
                        const start = pointerStart.current;
                        pointerStart.current = null;
                        if (event.currentTarget.hasPointerCapture(event.pointerId)) {
                            event.currentTarget.releasePointerCapture(event.pointerId);
                        }
                        if (start?.id === event.pointerId && start.x - event.clientX >= OPEN_DRAG_THRESHOLD) {
                            onOpen();
                        }
                    }}
                    onPointerCancel={() => {
                        pointerStart.current = null;
                    }}
                    whileHover={reduceMotion
                        ? undefined
                        : { x: -3, transition: { duration: 0.16, ease: easeOut } }}
                    whileTap={reduceMotion
                        ? undefined
                        : { scale: 0.97, transition: { duration: 0.1, ease: easeOut } }}
                    transition={reduceMotion ? instant : undefined}
                    className="fixed right-0 top-[38%] z-40 hidden h-24 w-7 touch-none flex-col items-center justify-center gap-3 rounded-l-xl rounded-r-none bg-popover text-popover-foreground shadow-lg ring-1 ring-foreground/10 outline-none hover:bg-muted focus-visible:ring-2 focus-visible:ring-ring md:flex"
                >
                    <span aria-hidden className="h-px w-3 bg-border" />
                    <span className="relative">
                        <SparklesIcon className="size-4" />
                        {working ? (
                            <span className="absolute -right-1 -top-1 size-1.5 rounded-full bg-primary" />
                        ) : null}
                    </span>
                    <span aria-hidden className="h-px w-3 bg-border" />
                </motion.button>
            </TooltipTrigger>
            <TooltipContent side="left">{label}</TooltipContent>
        </Tooltip>
    );
}
