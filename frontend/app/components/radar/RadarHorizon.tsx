'use client';

import { useTranslations } from 'next-intl';

import { RadarMark } from '@/app/components/radar/RadarVocabulary';
import {
    RADAR_HORIZON_MARK_LIMIT,
    radarHorizonColumns,
    radarMarkTone,
    type RadarHorizonBand,
} from '@/app/components/radar/radarHorizon';
import { RADAR_FORCED_COLORS_AFFORDANCE } from '@/app/components/radar/radarControlSurface';
import type { RadarSignal } from '@/app/lib/types';
import { cn } from '@/lib/utils';

type RadarHorizonProps = {
    signals: readonly RadarSignal[];
    band: RadarHorizonBand | null;
    onBandChange: (band: RadarHorizonBand | null) => void;
    /** Whether the signals below are a filtered view, so the hint says which picture this is. */
    filtered: boolean;
};

/**
 * The horizon: every signal Radar holds, placed by how long until it costs something.
 *
 * This is the page's first read and the question no other surface can answer. Each signal is its
 * own mark rather than a bar segment, so the picture is made of the same objects the rows below
 * are made of — mass on the left is attention already bleeding, mass on the right is work that can
 * wait. Selecting a column narrows the whole page to that deadline.
 */
export default function RadarHorizon({ signals, band, onBandChange, filtered }: RadarHorizonProps) {
    const t = useTranslations('Radar');
    const columns = radarHorizonColumns(signals);

    return (
        <section aria-labelledby="radar-horizon-heading" className="space-y-3">
            <div className="flex flex-wrap items-baseline justify-between gap-x-4 gap-y-1">
                <h2 id="radar-horizon-heading" className="text-sm font-semibold text-foreground">
                    {t('horizon.heading')}
                </h2>
                <p className="text-xs text-muted-foreground">{t(filtered ? 'horizon.hintFiltered' : 'horizon.hint')}</p>
            </div>

            <div
                role="group"
                aria-label={t('horizon.heading')}
                className="flex gap-1 overflow-x-auto rounded-2xl bg-muted/40 px-1 pt-1 pb-1 sm:grid sm:grid-cols-5 sm:overflow-x-visible"
            >
                {columns.map((column) => {
                    const selected = band === column.band;
                    const dimmed = band !== null && !selected;
                    return (
                        <button
                            key={column.band}
                            type="button"
                            aria-pressed={selected}
                            aria-label={t('horizon.bandNamed', {
                                band: t(`horizon.band.${column.band}`),
                                count: column.signals.length,
                            })}
                            onClick={() => onBandChange(selected ? null : column.band)}
                            className={cn(
                                'group/column flex min-w-28 flex-1 flex-col justify-end gap-2 rounded-xl px-2 pt-3 pb-2 text-left sm:min-w-0 outline-none transition-[background-color,transform] duration-(--motion-micro) hover:bg-background focus-visible:ring-3 focus-visible:ring-ring/50 motion-safe:active:scale-[0.98] motion-reduce:transition-none',
                                RADAR_FORCED_COLORS_AFFORDANCE,
                                selected && 'bg-background',
                            )}
                        >
                            <div
                                className={cn(
                                    'flex h-20 flex-wrap-reverse content-start gap-1 overflow-hidden transition-opacity duration-(--motion-standard) ease-calm',
                                    dimmed && 'opacity-35',
                                )}
                            >
                                {column.signals.slice(0, RADAR_HORIZON_MARK_LIMIT).map((signal) => (
                                    <RadarMark key={signal.id} tone={radarMarkTone(signal)} family={signal.family} />
                                ))}
                                {column.signals.length > RADAR_HORIZON_MARK_LIMIT ? (
                                    <span className="self-center text-[0.625rem] leading-none font-medium tabular-nums text-muted-foreground">
                                        {t('horizon.more', {
                                            count: column.signals.length - RADAR_HORIZON_MARK_LIMIT,
                                        })}
                                    </span>
                                ) : null}
                            </div>
                            <div className="border-t border-border pt-1.5">
                                <p className="text-base leading-none font-semibold tabular-nums text-foreground">
                                    {column.signals.length}
                                </p>
                                <p className="mt-1 truncate text-xs text-muted-foreground">
                                    {t(`horizon.band.${column.band}`)}
                                </p>
                            </div>
                        </button>
                    );
                })}
            </div>
        </section>
    );
}
