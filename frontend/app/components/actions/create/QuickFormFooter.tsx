'use client';

import type { ReactNode } from 'react';
import { useTranslations } from 'next-intl';
import { motion, useReducedMotion, type PanInfo } from 'motion/react';
import { ChevronUpIcon } from '@heroicons/react/24/outline';

import { Button } from '@/components/ui/button';

/** Upward drag distance (px) past which the pull-up handle escalates to the full create dialog. */
const PULL_UP_THRESHOLD = 44;

/**
 * Shared footer for the in-panel quick-create forms. The left control is a "Pull up for more" handle
 * that escalates to the full create dialog — either by clicking it or by dragging it upward past a
 * threshold (which reads as sliding the quick form up into the full drawer). The submit content (label
 * or spinner) is passed as children so each form owns its pending state.
 */
export default function QuickFormFooter({
    onMoreDetails,
    pending,
    submitDisabled,
    children,
}: {
    onMoreDetails: () => void;
    pending: boolean;
    submitDisabled: boolean;
    children: ReactNode;
}) {
    const t = useTranslations('Actions');
    const reduceMotion = useReducedMotion() ?? false;
    const draggable = !reduceMotion && !pending;

    const handleDragEnd = (_event: PointerEvent, info: PanInfo) => {
        if (info.offset.y <= -PULL_UP_THRESHOLD) onMoreDetails();
    };

    return (
        <div className="mt-1 flex items-center justify-between gap-3">
            <motion.button
                type="button"
                onClick={onMoreDetails}
                disabled={pending}
                aria-label={t('quickCreate.pullUp')}
                drag={draggable ? 'y' : false}
                dragConstraints={{ top: 0, bottom: 0 }}
                dragElastic={{ top: 0.7, bottom: 0, left: 0, right: 0 }}
                dragSnapToOrigin
                onDragEnd={handleDragEnd}
                className="inline-flex touch-none items-center gap-1 rounded-md text-sm font-medium text-muted-foreground underline-offset-4 transition-colors hover:text-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-brand disabled:cursor-not-allowed disabled:opacity-50 disabled:hover:text-muted-foreground data-[draggable=true]:cursor-grab data-[draggable=true]:active:cursor-grabbing"
                data-draggable={draggable}
            >
                <ChevronUpIcon className="size-4" />
                {t('quickCreate.pullUp')}
            </motion.button>
            <Button
                type="submit"
                disabled={submitDisabled}
                aria-busy={pending}
                aria-label={t('quickCreate.create')}
                className="min-w-24 bg-brand text-brand-foreground shadow-sm transition hover:bg-brand-hover hover:shadow-md"
            >
                {children}
            </Button>
        </div>
    );
}
