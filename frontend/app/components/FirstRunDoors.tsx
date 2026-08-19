'use client';

import { ArrowUpTrayIcon, PlusIcon } from '@heroicons/react/24/outline';
import { Loader2Icon } from 'lucide-react';

import { Button } from '@/components/ui/button';
import type { FirstRunDoor } from '@/app/lib/firstRunJourney';

/**
 * The ways into a record type with nothing in it yet: bring a list in, or add one by hand. Rendered
 * wherever the guided first run asks for a workspace's first records, so the dashboard checklist and
 * the record browsers offer the same doors in the same order. Both labels are supplied by the
 * caller, which names each door exactly as the action behind it is named everywhere else. The first
 * door is the primary action; nothing else in the region competes with it.
 */
export default function FirstRunDoors({
    doors,
    importLabel,
    createLabel,
    onImport,
    onCreate,
    importPending = false,
    size = 'toolbar',
}: {
    doors: FirstRunDoor[];
    importLabel: string;
    createLabel: string;
    onImport: () => void;
    onCreate: () => void;
    /** Whether the import door is waiting on the action that opens it. */
    importPending?: boolean;
    size?: 'toolbar' | 'page';
}) {
    if (doors.length === 0) return null;

    return (
        <div className="flex w-full flex-col items-stretch justify-center gap-2 sm:w-auto sm:flex-row sm:items-center">
            {doors.map((door, index) => {
                const variant = index === 0 ? 'brand' : 'outline';
                if (door === 'import') {
                    return (
                        <Button
                            key={door}
                            type="button"
                            size={size}
                            variant={variant}
                            disabled={importPending}
                            className="w-full sm:w-auto sm:shrink-0"
                            onClick={onImport}
                        >
                            {importPending ? (
                                <Loader2Icon className="size-3.5 animate-spin" aria-hidden />
                            ) : (
                                <ArrowUpTrayIcon aria-hidden />
                            )}
                            {importLabel}
                        </Button>
                    );
                }
                return (
                    <Button
                        key={door}
                        type="button"
                        size={size}
                        variant={variant}
                        className="w-full sm:w-auto sm:shrink-0"
                        onClick={onCreate}
                    >
                        <PlusIcon strokeWidth={2.5} aria-hidden />
                        {createLabel}
                    </Button>
                );
            })}
        </div>
    );
}
