/**
 * Borderless control surfaces for the Radar experiment. Interactive controls drop their border and
 * carry a filled surface instead, so the target still reads as a target in both themes. Focus keeps
 * the shared ring, which stays visible without a border because it is drawn outside the box.
 */
export const RADAR_FIELD_SURFACE = 'border-0 bg-muted shadow-none aria-invalid:bg-destructive/10 dark:bg-muted dark:aria-invalid:bg-destructive/20';

/**
 * Field surface plus the hover fill used by pressable Radar controls. The hover tint is derived from
 * the foreground so it darkens in light mode and lightens in dark mode.
 */
export const RADAR_PRESSABLE_SURFACE = `${RADAR_FIELD_SURFACE} hover:bg-foreground/10 dark:hover:bg-foreground/10`;
