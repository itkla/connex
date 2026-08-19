import type { RadarRiskSeverity } from '@/app/components/radar/radarHorizon';
import type { RadarFamily, TemperatureBand } from '@/app/lib/types';

/**
 * What a Radar mark is coloured by. Warmth bands colour a cooling relationship, risk severities
 * colour a flagged deal, and an intro path carries the opportunity accent — one semantic palette
 * per meaning, never a decorative accent.
 */
export type RadarMarkTone = TemperatureBand | RadarRiskSeverity | 'path';

/**
 * Radar's mark shapes, one per signal family.
 *
 * Shape carries the family and colour carries the reading, so a mark stays legible when colour is
 * unavailable — under forced colours, in a screenshot, or to a user who cannot separate the two
 * warm bands. Circle, diamond, square: three silhouettes that survive at ten pixels.
 */
export const RADAR_MARK_SHAPE = {
    relationship_decay: 'rounded-full',
    deal_risk: 'rotate-45 rounded-xs',
    warm_path: 'rounded-xs',
} satisfies Record<RadarFamily, string>;

/** Mark fill per tone. */
export const RADAR_MARK_FILL = {
    hot: 'bg-warmth-hot',
    warm: 'bg-warmth-warm',
    cool: 'bg-warmth-cool',
    cold: 'bg-warmth-cold',
    high: 'bg-risk-high',
    medium: 'bg-risk-medium',
    low: 'bg-risk-low',
    path: 'bg-chart-5',
} satisfies Record<RadarMarkTone, string>;

/**
 * The family a tone belongs to, so a mark rendered from a tone alone still gets its family shape.
 * Used by the miniature vocabulary other surfaces quote, where only the reading is in hand.
 */
export const RADAR_TONE_FAMILY = {
    hot: 'relationship_decay',
    warm: 'relationship_decay',
    cool: 'relationship_decay',
    cold: 'relationship_decay',
    high: 'deal_risk',
    medium: 'deal_risk',
    low: 'deal_risk',
    path: 'warm_path',
} satisfies Record<RadarMarkTone, RadarFamily>;
