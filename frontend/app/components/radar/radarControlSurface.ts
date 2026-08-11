export const RADAR_FORCED_COLORS_AFFORDANCE = 'forced-colors:border forced-colors:focus-visible:outline-solid forced-colors:focus-visible:outline-2';

export const RADAR_FIELD_SURFACE = `border-0 bg-muted shadow-none aria-invalid:bg-destructive/10 dark:bg-muted dark:aria-invalid:bg-destructive/20 ${RADAR_FORCED_COLORS_AFFORDANCE}`;

export const RADAR_PRESSABLE_SURFACE = `${RADAR_FIELD_SURFACE} hover:bg-foreground/10 dark:hover:bg-foreground/10`;
