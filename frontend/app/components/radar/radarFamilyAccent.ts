import type { RadarFamily } from '@/app/lib/types';

/**
 * Family accent dot classes shared by the triage rows and the family filter chips, so a family
 * reads as the same colour wherever it appears on the Radar surface.
 */
export const FAMILY_DOTS = {
    relationship_decay: 'bg-warmth-cool',
    deal_risk: 'bg-destructive',
    warm_path: 'bg-chart-5',
} satisfies Record<RadarFamily, string>;
