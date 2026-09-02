'use client';

import { useLayoutEffect, useRef, useState } from 'react';
import { AnimatePresence, motion } from 'motion/react';

import { easeOut, instant, springSmooth, springSnappy } from '@/app/lib/motion';

const MORPH_VARIANTS = {
    enter: (direction: number) => ({
        opacity: 0,
        x: direction >= 0 ? 12 : -12,
        filter: 'blur(3px)',
    }),
    center: { opacity: 1, x: 0, filter: 'blur(0px)' },
    exit: (direction: number) => ({
        opacity: 0,
        x: direction >= 0 ? -12 : 12,
        filter: 'blur(3px)',
    }),
    still: { opacity: 0, x: 0, filter: 'blur(0px)' },
};

const MORPH_CONTENT_TRANSITION = {
    x: springSnappy,
    opacity: { duration: 0.16, ease: easeOut },
    filter: { duration: 0.16, ease: easeOut },
};

/** Animates the mobile create drawer between its selector and embedded composer. */
export default function MobileCreateMorphingBody({
    viewKey,
    direction,
    reduceMotion,
    children,
}: {
    viewKey: string;
    direction: number;
    reduceMotion: boolean;
    children: React.ReactNode;
}) {
    const measureRef = useRef<HTMLDivElement | null>(null);
    const [height, setHeight] = useState<number | 'auto'>('auto');
    const [animateHeight, setAnimateHeight] = useState(false);

    useLayoutEffect(() => {
        const node = measureRef.current;
        if (!node) return;
        const measure = () => setHeight(node.offsetHeight);
        measure();
        const raf = requestAnimationFrame(() => setAnimateHeight(true));
        const observer = new ResizeObserver(measure);
        observer.observe(node);
        return () => {
            cancelAnimationFrame(raf);
            observer.disconnect();
        };
    }, []);

    return (
        <motion.div
            animate={reduceMotion ? undefined : { height }}
            transition={animateHeight ? springSmooth : instant}
            style={{ overflow: 'hidden' }}
        >
            <div ref={measureRef} className="relative">
                <AnimatePresence mode="popLayout" initial={false} custom={direction}>
                    <motion.div
                        key={viewKey}
                        custom={direction}
                        variants={MORPH_VARIANTS}
                        initial={reduceMotion ? 'still' : 'enter'}
                        animate="center"
                        exit={reduceMotion ? 'still' : 'exit'}
                        transition={reduceMotion ? { duration: 0.12 } : MORPH_CONTENT_TRANSITION}
                    >
                        {children}
                    </motion.div>
                </AnimatePresence>
            </div>
        </motion.div>
    );
}
