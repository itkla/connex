'use client';

import { motion, useReducedMotion } from 'motion/react';
import type { ReactNode } from 'react';

import { durationExpressive, easeOut } from '@/app/lib/motion';
import { cn } from '@/lib/utils';

/**
 * Pins the resting state for readers who ask for reduced motion, in CSS rather than script. The
 * server cannot know that preference, so it renders the animated wrapper with its hidden starting
 * style, and a reduced-motion browser then hydrates the plain wrapper, which keeps the server's
 * inline `opacity: 0` and offset because hydration does not patch attributes (#2017). These
 * `!important` media-query rules beat that inline style before and after hydration, and never apply
 * to anyone who has not asked for less motion.
 */
const REDUCED_MOTION_RESTING_STATE = 'motion-reduce:opacity-100! motion-reduce:transform-none!';

/**
 * Staggered fade-up entrance shared across app pages — the expressive speed, spent on the one
 * memorable moment a page gets. Content fades in from a small downward offset and honors
 * `prefers-reduced-motion` by rendering a plain wrapper with no motion, whose resting state is
 * pinned in CSS so a server-rendered page is never left invisible. Pass an explicit `delay`, or an
 * `index` to stagger a list of siblings at roughly 60ms per item.
 */
export default function Rise({
    children,
    delay,
    index,
    className,
}: {
    children: ReactNode;
    delay?: number;
    index?: number;
    className?: string;
}) {
    const reduce = useReducedMotion() ?? false;
    const resolvedDelay = delay ?? (index != null ? index * 0.06 : 0);
    const resolvedClassName = cn(REDUCED_MOTION_RESTING_STATE, className);
    if (reduce) return <div className={resolvedClassName}>{children}</div>;
    return (
        <motion.div
            className={resolvedClassName}
            initial={{ opacity: 0, y: 14 }}
            animate={{ opacity: 1, y: 0 }}
            transition={{ duration: durationExpressive, delay: resolvedDelay, ease: easeOut }}
        >
            {children}
        </motion.div>
    );
}
