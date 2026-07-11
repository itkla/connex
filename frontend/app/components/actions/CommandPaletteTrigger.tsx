'use client';

import { useTranslations } from 'next-intl';
import { MagnifyingGlassIcon } from '@heroicons/react/24/outline';

import { useCommandPalette } from './CommandPaletteProvider';

/**
 * The pointer-visible, keyboard-accessible trigger that opens the command palette, showing the
 * `Cmd/Ctrl+K` affordance. Rendered in the sidebar beneath the Quick Create launcher.
 */
export default function CommandPaletteTrigger() {
    const t = useTranslations('Actions');
    const { open } = useCommandPalette();

    return (
        <button
            type="button"
            onClick={open}
            className="mb-5 inline-flex w-full items-center gap-2 rounded-lg bg-muted px-3 py-2 text-sm text-muted-foreground ring-1 ring-border transition-colors hover:bg-sidebar-accent hover:text-sidebar-accent-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-brand"
        >
            <MagnifyingGlassIcon className="size-4 shrink-0" />
            <span className="flex-1 truncate text-left">{t('palette.trigger')}</span>
            <kbd className="pointer-events-none inline-flex select-none items-center rounded border border-border bg-background px-1.5 py-0.5 font-mono text-[10px] font-medium text-muted-foreground">
                ⌘K
            </kbd>
        </button>
    );
}
