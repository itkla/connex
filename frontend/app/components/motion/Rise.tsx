'use client';

import { motion, useReducedMotion } from 'motion/react';
import type { ReactNode } from 'react';

const EASE_OUT: [number, number, number, number] = [0.23, 1, 0.32, 1];

/**
 * Staggered fade-up entrance shared across app pages. Content fades in from a small
 * downward offset and honors `prefers-reduced-motion` by rendering a plain wrapper
 * with no motion. Pass an explicit `delay`, or an `index` to stagger a list of
 * siblings at roughly 60ms per item.
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
    if (reduce) return <div className={className}>{children}</div>;
    return (
        <motion.div
            className={className}
            initial={{ opacity: 0, y: 14 }}
            animate={{ opacity: 1, y: 0 }}
            transition={{ duration: 0.5, delay: resolvedDelay, ease: EASE_OUT }}
        >
            {children}
        </motion.div>
    );
}
