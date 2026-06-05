'use client';

import Link from 'next/link';
import { motion, useReducedMotion } from 'motion/react';
import type { ReactNode } from 'react';

import { cn } from '@/lib/utils';

const EASE_OUT: [number, number, number, number] = [0.23, 1, 0.32, 1];

export default function MotionCard({
    href,
    index = 0,
    className,
    children,
}: {
    href?: string;
    index?: number;
    className?: string;
    children: ReactNode;
}) {
    const reduce = useReducedMotion() ?? false;

    const card = (
        <motion.div
            initial={reduce ? false : { opacity: 0, y: 12 }}
            animate={reduce ? undefined : { opacity: 1, y: 0 }}
            transition={{ duration: 0.45, delay: index * 0.06, ease: EASE_OUT }}
            className="h-full"
        >
            <motion.div
                whileHover={reduce ? undefined : { y: -2 }}
                whileTap={reduce ? undefined : { scale: 0.99 }}
                transition={{ duration: 0.2, ease: EASE_OUT }}
                className={cn('h-full', className)}
            >
                {children}
            </motion.div>
        </motion.div>
    );

    if (href) {
        return (
            <Link href={href} className="group block h-full">
                {card}
            </Link>
        );
    }
    return card;
}