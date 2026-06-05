'use client';

import { useCallback, useEffect, useLayoutEffect, useRef } from 'react';
import { useLocale } from 'next-intl';
import { useReducedMotion } from 'motion/react';

import { formatCompactCurrency } from '@/app/lib/utils';

const useIsoLayoutEffect = typeof document !== 'undefined' ? useLayoutEffect : useEffect;
const EASE_OUT_QUART = (p: number) => 1 - Math.pow(1 - p, 4);

export default function CountUp({
    value,
    format = 'count',
    currency = 'USD',
    duration = 750,
    className,
}: {
    value: number;
    format?: 'count' | 'currency';
    currency?: string;
    duration?: number;
    className?: string;
}) {
    const locale = useLocale();
    const reduce = useReducedMotion() ?? false;
    const ref = useRef<HTMLSpanElement>(null);

    const fmt = useCallback(
        (v: number) =>
            format === 'currency'
                ? formatCompactCurrency(v, currency, locale)
                : Math.round(v).toLocaleString(locale),
        [format, currency, locale],
    );

    useIsoLayoutEffect(() => {
        const el = ref.current;
        if (!el) return;
        if (reduce || value === 0) {
            el.textContent = fmt(value);
            return;
        }
        el.textContent = fmt(0);
        let raf = 0;
        const start = performance.now();
        const tick = (t: number) => {
            const p = Math.min(1, (t - start) / duration);
            el.textContent = fmt(value * EASE_OUT_QUART(p));
            if (p < 1) raf = requestAnimationFrame(tick);
        };
        raf = requestAnimationFrame(tick);
        return () => cancelAnimationFrame(raf);
    }, [value, reduce, fmt, duration]);

    return (
        <span ref={ref} className={className}>
            {fmt(value)}
        </span>
    );
}