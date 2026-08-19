'use client';

import { useTranslations } from 'next-intl';
import { ArrowUpTrayIcon, PlusIcon } from '@heroicons/react/24/outline';
import { Loader2Icon } from 'lucide-react';

import { Button } from '@/components/ui/button';
import type { FirstRunDoor } from '@/app/lib/firstRunJourney';

/**
 * The ways into a workspace with no people in it yet: bring a list in, or add someone. Rendered
 * wherever the first-run journey asks for its first contacts, so the dashboard checklist and the
 * contacts browser offer the same two doors in the same order. The first door is the primary
 * action; nothing else in the region competes with it.
 */
export default function FirstRunDoors({
    doors,
    onImport,
    onNew,
    newLabel,
    importPending = false,
    size = 'toolbar',
}: {
    doors: FirstRunDoor[];
    onImport: () => void;
    onNew: () => void;
    /** Label for the create door. Defaults to the journey's own "New contact". */
    newLabel?: string;
    /** Whether the import door is waiting on the action that opens it. */
    importPending?: boolean;
    size?: 'toolbar' | 'page';
}) {
    const t = useTranslations('FirstRunJourney');

    if (doors.length === 0) return null;

    return (
        <div className="flex flex-wrap items-center justify-center gap-2">
            {doors.map((door, index) => {
                const variant = index === 0 ? 'brand' : 'outline';
                if (door === 'importCsv') {
                    return (
                        <Button
                            key={door}
                            type="button"
                            size={size}
                            variant={variant}
                            disabled={importPending}
                            onClick={onImport}
                        >
                            {importPending ? (
                                <Loader2Icon className="size-3.5 animate-spin" aria-hidden />
                            ) : (
                                <ArrowUpTrayIcon aria-hidden />
                            )}
                            {t('doors.importCsv')}
                        </Button>
                    );
                }
                return (
                    <Button
                        key={door}
                        type="button"
                        size={size}
                        variant={variant}
                        onClick={onNew}
                    >
                        <PlusIcon strokeWidth={2.5} aria-hidden />
                        {newLabel ?? t('doors.newContact')}
                    </Button>
                );
            })}
        </div>
    );
}
