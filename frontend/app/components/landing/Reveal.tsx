"use client";

import { motion } from "motion/react";
import { useSyncExternalStore, type ReactNode } from "react";
import { durationStandard, easeOut } from "@/app/lib/motion";

const subscribe = () => () => {};
const getClientSnapshot = () => true;
const getServerSnapshot = () => false;

/**
 * Scroll reveal for marketing sections.
 *
 * The hidden starting state is armed only after mount, so the server-rendered
 * markup is fully visible: a crawler, a printed page, a reader with JavaScript
 * disabled, or a headless renderer sees the content rather than a stack of
 * blank sections. `useSyncExternalStore` supplies `false` as the server
 * snapshot and `true` on the client, so hydration matches and the hidden state
 * is armed only once the browser is actually driving the page.
 *
 * Reduced motion is pinned in CSS (`motion-reduce:` beats the inline style
 * `motion` writes), not read from `useReducedMotion()`, which returns `null` on
 * a server-rendered first paint and would let the movement through.
 */
export default function Reveal({
    children,
    delay = 0,
    className,
}: {
    children: ReactNode;
    delay?: number;
    className?: string;
}) {
    const armed = useSyncExternalStore(subscribe, getClientSnapshot, getServerSnapshot);

    if (!armed) return <div className={className}>{children}</div>;

    return (
        <motion.div
            className={`motion-reduce:translate-y-0! motion-reduce:opacity-100! ${className ?? ""}`}
            initial={{ opacity: 0, y: 20 }}
            whileInView={{ opacity: 1, y: 0 }}
            viewport={{ once: true, amount: 0.2 }}
            transition={{ duration: durationStandard, ease: easeOut, delay }}
        >
            {children}
        </motion.div>
    );
}
