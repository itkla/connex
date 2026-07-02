'use client';

import { motion, useMotionTemplate, useReducedMotion, useSpring } from 'motion/react';
import { useCallback, useEffect, useRef, useState, type ReactNode } from 'react';
import { cn } from '@/lib/utils';

const EASE_OUT: [number, number, number, number] = [0.23, 1, 0.32, 1];

type Ripple = { x: number; y: number; id: number };

/**
 * A liquid-glass surface for the time-travel control. Every browser gets the frosted base with a
 * pointer-tracked specular highlight, an idle sheen sweep, a lit/iridescent rim, layered "thickness"
 * shadows, a press ripple, and a hover lift. Chromium additionally refracts the map behind the pill
 * through an SVG displacement filter (feature-detected) — suppressed while {@code suppressRefraction}
 * is set (e.g. during playback) since displacement over an animating backdrop is the one real hazard.
 * All motion honours prefers-reduced-motion; prefers-reduced-transparency collapses it to a solid pill.
 */
export default function LiquidGlass({
    children,
    className,
    accent,
    suppressRefraction = false,
}: {
    children: ReactNode;
    className?: string;
    accent?: string;
    suppressRefraction?: boolean;
}) {
    const reduce = useReducedMotion();

    const gx = useSpring(50, { stiffness: 220, damping: 26 });
    const gy = useSpring(50, { stiffness: 220, damping: 26 });
    const go = useSpring(0.3, { stiffness: 260, damping: 30 });
    const glareX = useMotionTemplate`${gx}%`;
    const glareY = useMotionTemplate`${gy}%`;

    const [canRefract, setCanRefract] = useState(false);
    useEffect(() => {
        const supported =
            typeof CSS !== 'undefined' &&
            (CSS.supports('backdrop-filter', 'url(#lg-refract)') ||
                CSS.supports('-webkit-backdrop-filter', 'url(#lg-refract)'));
        if (!supported) return;
        const raf = requestAnimationFrame(() => setCanRefract(true));
        return () => cancelAnimationFrame(raf);
    }, []);

    const [ripple, setRipple] = useState<Ripple | null>(null);
    const rippleId = useRef(0);

    const onPointerMove = useCallback(
        (e: React.PointerEvent<HTMLDivElement>) => {
            if (reduce) return;
            const r = e.currentTarget.getBoundingClientRect();
            gx.set(((e.clientX - r.left) / r.width) * 100);
            gy.set(((e.clientY - r.top) / r.height) * 100);
            go.set(0.6);
        },
        [reduce, gx, gy, go],
    );

    const onPointerLeave = useCallback(() => {
        if (reduce) return;
        gx.set(50);
        gy.set(50);
        go.set(0.3);
    }, [reduce, gx, gy, go]);

    const onPointerDown = useCallback(
        (e: React.PointerEvent<HTMLDivElement>) => {
            if (reduce) return;
            const r = e.currentTarget.getBoundingClientRect();
            rippleId.current += 1;
            setRipple({
                x: ((e.clientX - r.left) / r.width) * 100,
                y: ((e.clientY - r.top) / r.height) * 100,
                id: rippleId.current,
            });
        },
        [reduce],
    );

    const style = {
        '--glare-x': glareX,
        '--glare-y': glareY,
        '--glare-o': go,
        ...(accent ? { '--lg-accent': accent } : {}),
    } as React.CSSProperties;

    return (
        <motion.div
            layout
            transition={reduce ? { duration: 0 } : { type: 'spring', stiffness: 380, damping: 36 }}
            onPointerMove={onPointerMove}
            onPointerLeave={onPointerLeave}
            onPointerDown={onPointerDown}
            whileHover={reduce ? undefined : { y: -2 }}
            whileTap={reduce ? undefined : { scale: 0.985 }}
            data-playing={suppressRefraction ? '' : undefined}
            style={style}
            className={cn('lg-pill pointer-events-auto relative', canRefract && 'lg-can-refract', className)}
        >
            <span aria-hidden className="lg-sheen" />
            {ripple ? (
                <motion.span
                    key={ripple.id}
                    aria-hidden
                    className="lg-ripple"
                    style={{ left: `${ripple.x}%`, top: `${ripple.y}%` }}
                    initial={{ opacity: 0.4, scale: 0 }}
                    animate={{ opacity: 0, scale: 2.6 }}
                    transition={{ duration: 0.6, ease: EASE_OUT }}
                    onAnimationComplete={() => setRipple((cur) => (cur && cur.id === ripple.id ? null : cur))}
                />
            ) : null}
            <div className="lg-deck relative z-[3] flex items-center gap-2 p-1">{children}</div>
        </motion.div>
    );
}
