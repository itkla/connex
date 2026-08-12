import { describe, expect, it } from 'vitest';

import {
    RADAR_FIELD_SURFACE,
    RADAR_FORCED_COLORS_AFFORDANCE,
    RADAR_PRESSABLE_SURFACE,
} from '@/app/components/radar/radarControlSurface';
import { cn } from '@/lib/utils';

const INPUT_BASE = 'h-9 w-full min-w-0 rounded-md border border-input bg-transparent px-2.5 py-1 text-base shadow-xs focus-visible:border-ring aria-invalid:border-destructive dark:bg-input/30 dark:aria-invalid:border-destructive/50';
const SELECT_TRIGGER_BASE = 'flex w-fit items-center rounded-md border border-input bg-transparent shadow-xs focus-visible:border-ring aria-invalid:border-destructive dark:bg-input/30 dark:hover:bg-input/50';
const SECONDARY_BUTTON_BASE = 'inline-flex border border-transparent bg-secondary text-secondary-foreground hover:bg-secondary/80';

describe('radar borderless control surfaces', () => {
    it('drops the field border, hairline shadow, and transparent fill from the input primitive', () => {
        const merged = cn(INPUT_BASE, RADAR_FIELD_SURFACE).split(' ');
        expect(merged).toContain('border-0');
        expect(merged).toContain('bg-muted');
        expect(merged).toContain('shadow-none');
        expect(merged).not.toContain('border');
        expect(merged).not.toContain('shadow-xs');
        expect(merged).not.toContain('bg-transparent');
    });

    it('leaves no border width for a surviving border colour to paint', () => {
        const widths = cn(INPUT_BASE, RADAR_FIELD_SURFACE)
            .split(' ')
            .filter((token) => token === 'border' || /^border-\d+$/.test(token));
        expect(widths).toEqual(['border-0']);
    });

    it('keeps the focus ring so a borderless field still announces focus', () => {
        expect(cn(INPUT_BASE, RADAR_FIELD_SURFACE)).toContain('focus-visible:border-ring');
    });

    it('restores a boundary and a focus outline under forced colours', () => {
        const merged = cn(INPUT_BASE, RADAR_FIELD_SURFACE).split(' ');
        expect(merged).toContain('forced-colors:border');
        expect(merged).toContain('forced-colors:focus-visible:outline-solid');
        expect(merged).toContain('forced-colors:focus-visible:outline-2');
    });

    it('carries the forced-colours affordance into every radar surface', () => {
        for (const token of RADAR_FORCED_COLORS_AFFORDANCE.split(' ')) {
            expect(RADAR_FIELD_SURFACE.split(' ')).toContain(token);
            expect(RADAR_PRESSABLE_SURFACE.split(' ')).toContain(token);
        }
    });

    it('overrides the dark-mode fill the primitive sets behind the base background', () => {
        const merged = cn(INPUT_BASE, RADAR_FIELD_SURFACE).split(' ');
        expect(merged).toContain('dark:bg-muted');
        expect(merged).not.toContain('dark:bg-input/30');
    });

    it('tints an invalid field instead of relying on a destructive border', () => {
        const merged = cn(INPUT_BASE, RADAR_FIELD_SURFACE).split(' ');
        expect(merged).toContain('aria-invalid:bg-destructive/10');
        expect(merged).toContain('dark:aria-invalid:bg-destructive/20');
    });

    it('replaces the select trigger hover fill in both themes', () => {
        const merged = cn(SELECT_TRIGGER_BASE, RADAR_PRESSABLE_SURFACE).split(' ');
        expect(merged).toContain('hover:bg-foreground/10');
        expect(merged).toContain('dark:hover:bg-foreground/10');
        expect(merged).not.toContain('dark:hover:bg-input/50');
    });

    it('replaces the secondary button fill and hover so it matches the other radar controls', () => {
        const merged = cn(SECONDARY_BUTTON_BASE, RADAR_PRESSABLE_SURFACE).split(' ');
        expect(merged).toContain('border-0');
        expect(merged).toContain('bg-muted');
        expect(merged).toContain('hover:bg-foreground/10');
        expect(merged).not.toContain('bg-secondary');
        expect(merged).not.toContain('hover:bg-secondary/80');
    });
});
