"use client";

import { motion } from "motion/react";
import { useEffect, useRef, useState, type ReactNode } from "react";
import { durationStandard, easeOut } from "@/app/lib/motion";

/** How far below the fold an element must start before it is worth hiding to reveal later. */
const ARM_MARGIN = 1.05;

/** Force a reveal if the observer never reports, so nothing can stay hidden indefinitely. */
const SAFETY_MS = 2500;

/**
 * Scroll reveal for marketing sections.
 *
 * The rule this obeys: a reveal enhances content that is already visible, it never gates it. Three
 * things follow from that.
 *
 * The hidden state is armed after mount, so server-rendered markup, a crawler, a printed page, or
 * a reader without JavaScript sees the content rather than a stack of blank sections.
 *
 * It arms only for elements that actually start below the fold. Anything already on screen renders
 * visible immediately — there is nothing to reveal, and hiding it risks a flash of empty layout.
 *
 * A safety timer releases the hidden state even if the observer never reports. Headless renderers
 * and screenshot tools lay the whole document out without scrolling, so `whileInView` alone leaves
 * every section below the first screen permanently blank; that is a real failure mode, not a
 * theoretical one, and it is why this does not use `whileInView`.
 *
 * Reduced motion is pinned in CSS (`motion-reduce:` beats the inline style `motion` writes), not
 * read from `useReducedMotion()`, which returns `null` on a server-rendered first paint. It must be
 * `transform-none`: Tailwind's `translate-y-0` compiles to the `translate` property, which composes
 * with rather than cancels the `transform` that `motion` writes.
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
    const ref = useRef<HTMLDivElement>(null);
    const [state, setState] = useState<"open" | "armed">("open");

    useEffect(() => {
        const node = ref.current;
        if (!node || typeof IntersectionObserver === "undefined") return;
        if (node.getBoundingClientRect().top <= window.innerHeight * ARM_MARGIN) return;

        setState("armed");
        const release = () => setState("open");
        const timer = window.setTimeout(release, SAFETY_MS);
        const observer = new IntersectionObserver(
            (entries) => {
                if (entries.some((entry) => entry.isIntersecting)) release();
            },
            { threshold: 0.15 },
        );
        observer.observe(node);

        return () => {
            window.clearTimeout(timer);
            observer.disconnect();
        };
    }, []);

    return (
        <motion.div
            ref={ref}
            className={`motion-reduce:transform-none! motion-reduce:opacity-100! ${className ?? ""}`}
            animate={state === "armed" ? { opacity: 0, y: 20 } : { opacity: 1, y: 0 }}
            initial={false}
            transition={{ duration: durationStandard, ease: easeOut, delay: state === "armed" ? 0 : delay }}
        >
            {children}
        </motion.div>
    );
}
