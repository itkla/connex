'use client';

import { useTranslations } from 'next-intl';

import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from '@/components/ui/dialog';

/** One key + what it does, rendered as a row in the shortcuts dialog. */
interface Shortcut {
    keys: string[];
    labelKey: string;
}

const SHORTCUTS: Shortcut[] = [
    { keys: ['T'], labelKey: 'goToToday' },
    { keys: ['M'], labelKey: 'viewMonth' },
    { keys: ['W'], labelKey: 'viewWeek' },
    { keys: ['D'], labelKey: 'viewDay' },
    { keys: ['A'], labelKey: 'viewAgenda' },
    { keys: ['←', '→'], labelKey: 'shortcutNavigate' },
    { keys: ['G'], labelKey: 'goToDate' },
    { keys: ['C'], labelKey: 'quickCreate' },
    { keys: ['?'], labelKey: 'shortcuts' },
];

/** Keyboard cheat-sheet, toggled with `?`. Reference only — the shell owns the key handling. */
export default function CalendarShortcuts({
    open,
    onOpenChange,
}: {
    open: boolean;
    onOpenChange: (open: boolean) => void;
}) {
    const t = useTranslations('Calendar');

    return (
        <Dialog open={open} onOpenChange={onOpenChange}>
            <DialogContent className="max-w-sm gap-4">
                <DialogHeader>
                    <DialogTitle className="text-base">{t('shortcutsTitle')}</DialogTitle>
                    <DialogDescription className="sr-only">{t('shortcutsHint')}</DialogDescription>
                </DialogHeader>
                <dl className="flex flex-col gap-1.5">
                    {SHORTCUTS.map((s) => (
                        <div key={s.labelKey} className="flex items-center justify-between gap-4 py-1">
                            <dt className="text-sm text-foreground">{t(s.labelKey)}</dt>
                            <dd className="flex items-center gap-1">
                                {s.keys.map((key) => (
                                    <kbd
                                        key={key}
                                        className="grid h-6 min-w-6 place-items-center rounded-md border border-border bg-muted px-1.5 text-[11px] font-medium tabular-nums text-muted-foreground"
                                    >
                                        {key}
                                    </kbd>
                                ))}
                            </dd>
                        </div>
                    ))}
                </dl>
            </DialogContent>
        </Dialog>
    );
}
