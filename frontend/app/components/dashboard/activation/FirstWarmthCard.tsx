'use client';

import Link from 'next/link';
import { useTranslations } from 'next-intl';
import { motion, useReducedMotion } from 'motion/react';

import { Button } from '@/components/ui/button';
import type { TemperatureBand, WarmthBandCounts } from '@/app/lib/types';
import { durationStandard, easeOut, instant } from '@/app/lib/motion';
import { warmthDotClass, warmthSurfaceClasses } from '@/app/lib/utils';
import { cn } from '@/lib/utils';

const BANDS: TemperatureBand[] = ['hot', 'warm', 'cool', 'cold'];

/** Where the journey hands over: the contacts list, whose warmth column reads per person. */
const WARMTH_HREF = '/records/contacts';

/**
 * The end of the first-run journey: the workspace's first warmth reading, shown as the bands its
 * own logged interactions produced. Bands nobody is in are left out rather than shown as zeroes, so
 * the card only ever claims what the recorded evidence supports, and its one action hands the
 * member to the contacts list where warmth is read person by person.
 */
export default function FirstWarmthCard({ readings }: { readings: WarmthBandCounts }) {
    const t = useTranslations('FirstRunJourney');
    const tBand = useTranslations('Temperature');
    const reduce = useReducedMotion() ?? false;
    const present = BANDS.filter((band) => readings[band] > 0);

    return (
        <div className="flex h-full flex-col overflow-hidden rounded-2xl border border-border bg-card">
            <div className="flex items-center justify-between gap-3 border-b border-border px-5 py-3.5">
                <h3 className="min-w-0 text-sm font-medium text-foreground">{t('warmth.title')}</h3>
            </div>

            <div className="flex flex-1 flex-col gap-4 px-5 py-4">
                <p className="text-sm text-muted-foreground">{t('warmth.body')}</p>

                <div className="min-w-0">
                    <h4 className="text-xs font-medium tracking-[0.12em] text-muted-foreground uppercase">
                        {t('warmth.readings')}
                    </h4>
                    <ul className="mt-2 grid gap-2">
                        {present.map((band, index) => (
                            <motion.li
                                key={band}
                                initial={reduce ? false : { opacity: 0, transform: 'translateY(6px)' }}
                                animate={{ opacity: 1, transform: 'translateY(0)' }}
                                transition={
                                    reduce
                                        ? instant
                                        : { duration: durationStandard, delay: index * 0.04, ease: easeOut }
                                }
                                className="flex items-center gap-3 rounded-lg border border-border bg-muted/40 px-3.5 py-2.5"
                            >
                                <span
                                    className={cn(
                                        'inline-flex items-center gap-1.5 rounded-full px-2 py-0.5 text-xs font-medium ring-1 ring-inset',
                                        warmthSurfaceClasses(band),
                                    )}
                                >
                                    <span className={cn('size-1.5 rounded-full', warmthDotClass(band))} aria-hidden />
                                    {tBand(band)}
                                </span>
                                <p className="min-w-0 flex-1 text-sm text-foreground">
                                    {t('warmth.bandCount', { count: readings[band] })}
                                </p>
                            </motion.li>
                        ))}
                    </ul>
                </div>

                <div className="mt-auto flex flex-wrap items-center gap-2 pt-1">
                    <Button asChild size="toolbar" variant="brand">
                        <Link href={WARMTH_HREF}>{t('warmth.cta')}</Link>
                    </Button>
                </div>
            </div>
        </div>
    );
}
