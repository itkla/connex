import {
    RADAR_MARK_FILL,
    RADAR_MARK_SHAPE,
    RADAR_TONE_FAMILY,
    type RadarMarkTone,
} from '@/app/components/radar/radarFamilyAccent';
import type { RadarFamily } from '@/app/lib/types';
import { cn } from '@/lib/utils';

/**
 * Radar's mark: the one glyph the rest of the product quotes to say "Radar has something here".
 *
 * Shape names the signal family and fill names the reading, so the same mark works on Radar's own
 * horizon, on a record's signals block, and in a dashboard row without being redrawn. It is
 * deliberately free of hooks, translations, and data access, so it renders in a server component as
 * happily as in a client one.
 *
 * The mark is decorative on its own: it always travels with text that says the same thing, so it is
 * hidden from assistive technology rather than given an invented name.
 */
export function RadarMark({
    tone,
    family,
    className,
}: {
    /** What the mark is coloured by: a warmth band, a risk severity, or an intro path. */
    tone: RadarMarkTone;
    /** Overrides the family the tone implies. Only needed where a family renders a neutral tone. */
    family?: RadarFamily;
    className?: string;
}) {
    const shape = RADAR_MARK_SHAPE[family ?? RADAR_TONE_FAMILY[tone]];
    return (
        <span
            aria-hidden
            className={cn('inline-flex size-3 shrink-0 items-center justify-center', className)}
        >
            <span
                className={cn(
                    shape,
                    RADAR_MARK_FILL[tone],
                    tone === 'path' || RADAR_TONE_FAMILY[tone] === 'deal_risk'
                        ? 'size-2'
                        : 'size-2.5',
                    'forced-colors:bg-current',
                )}
            />
        </span>
    );
}

/**
 * Radar's chip: a mark, what it is, and optionally how many.
 *
 * This is the miniature other surfaces quote — a record's signals block, a dashboard triage row —
 * so Radar reads as the source of the intelligence rather than one more place that mentions it.
 * The label arrives already translated, which keeps the chip usable from a server component and
 * keeps the caller's namespace its own business.
 */
export function RadarSignalChip({
    tone,
    family,
    label,
    count,
    className,
}: {
    tone: RadarMarkTone;
    family?: RadarFamily;
    /** The reading in the user's language, e.g. the family name or a warmth band. */
    label: string;
    /** How many signals this chip stands for. Omitted when the chip stands for exactly one. */
    count?: number;
    className?: string;
}) {
    return (
        <span
            className={cn(
                'inline-flex items-center gap-1.5 whitespace-nowrap rounded-full bg-muted px-2 py-0.5 text-xs font-medium text-foreground ring-1 ring-border ring-inset select-none',
                className,
            )}
        >
            <RadarMark tone={tone} family={family} />
            {label}
            {count === undefined ? null : (
                <span className="tabular-nums text-muted-foreground">{count}</span>
            )}
        </span>
    );
}
