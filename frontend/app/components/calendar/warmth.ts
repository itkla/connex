import type { CalendarEvent } from '@/app/lib/calendar';
import type { RelationshipTemperature, TemperatureBand, TemperatureTrend } from '@/app/lib/types';

/** The contact whose relationship warmth a calendar event reflects, if any. Deals link via company, not a person, so they carry no per-contact warmth here. */
export function warmthContactId(event: CalendarEvent): number | null {
    switch (event.kind) {
        case 'task':
        case 'activity':
            return event.raw.personId ?? null;
        case 'note':
            return event.raw.person ?? null;
        case 'deal':
            return null;
    }
}

/** Solid dot color per warmth band. */
export const WARMTH_DOT_CLASS: Record<TemperatureBand, string> = {
    hot: 'bg-warmth-hot',
    warm: 'bg-warmth-warm',
    cool: 'bg-warmth-cool',
    cold: 'bg-warmth-cold',
};

/** Tinted surface + text per warmth band, for the relationship chip. */
export const WARMTH_CHIP_CLASS: Record<TemperatureBand, string> = {
    hot: 'bg-warmth-hot/15 text-foreground',
    warm: 'bg-warmth-warm/15 text-foreground',
    cool: 'bg-warmth-cool/20 text-foreground',
    cold: 'bg-warmth-cold/15 text-foreground',
};

/** Calendar-namespace i18n key for a warmth band label. */
export const WARMTH_LABEL_KEY: Record<TemperatureBand, 'warmthHot' | 'warmthWarm' | 'warmthCool' | 'warmthCold'> = {
    hot: 'warmthHot',
    warm: 'warmthWarm',
    cool: 'warmthCool',
    cold: 'warmthCold',
};

/** Calendar-namespace i18n key for a warmth trend label. */
export const WARMTH_TREND_KEY: Record<TemperatureTrend, 'trendRising' | 'trendSteady' | 'trendCooling'> = {
    rising: 'trendRising',
    steady: 'trendSteady',
    cooling: 'trendCooling',
};

/** Cool or cold — the relationships worth flagging on the calendar at a glance. */
export function isAtRisk(band: TemperatureBand): boolean {
    return band === 'cool' || band === 'cold';
}

/** Builds a contact-id → temperature lookup from the scoring endpoint's list. */
export function temperatureIndex(temperatures: RelationshipTemperature[]): Map<number, RelationshipTemperature> {
    const map = new Map<number, RelationshipTemperature>();
    for (const t of temperatures) map.set(t.id, t);
    return map;
}
