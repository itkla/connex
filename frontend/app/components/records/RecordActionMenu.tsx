'use client';

import { type ReactNode, useMemo } from 'react';
import { useTranslations } from 'next-intl';
import { EllipsisHorizontalIcon, EyeIcon, PencilSquareIcon, TrashIcon } from '@heroicons/react/24/outline';

import {
    ContextMenu,
    ContextMenuContent,
    ContextMenuItem,
    ContextMenuSeparator,
    ContextMenuTrigger,
} from '@/components/ui/context-menu';
import {
    DropdownMenu,
    DropdownMenuContent,
    DropdownMenuItem,
    DropdownMenuSeparator,
    DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu';
import { useActions } from '@/app/hooks/useActions';
import type { ActiveRecordRef } from '@/app/lib/actions/types';

/**
 * The record a row/card menu acts on, plus the browser-local actions the shell owns (peek, quick
 * edit, delete) that can't live in the global registry because they drive browser-scoped UI.
 */
export type RecordMenuModel = {
    record: ActiveRecordRef;
    onPeek?: () => void;
    onQuickEdit?: () => void;
    onDelete?: () => void;
};

type MenuEntry =
    | { kind: 'separator'; key: string }
    | {
          kind: 'item';
          key: string;
          label: string;
          icon: ReactNode;
          destructive?: boolean;
          onSelect: () => void;
      };

const REGISTRY_ORDER = [
    'record.open',
    'record.open-new-tab',
    '--',
    'create.task',
    'create.note',
    'create.activity',
    '--',
    'record.copy-link',
] as const;

/**
 * Builds the ordered menu entries for a record: browser-local peek/quick-edit at the top, the
 * registry-backed navigate/create/copy actions in the middle (each resolved against the row's record
 * via {@link useActions}'s per-record override so availability reflects the row, not the page), and a
 * destructive delete last. Entries collapse adjacent/edge separators so the rendered menu never shows
 * a dangling divider.
 */
function useRecordMenuEntries(model: RecordMenuModel): MenuEntry[] {
    const { getAction, isAvailableForRecord, run } = useActions();
    const t = useTranslations('Actions');
    const tr = useTranslations('RecordActionMenu');

    return useMemo(() => {
        const entries: MenuEntry[] = [];
        const { record, onPeek, onQuickEdit, onDelete } = model;

        if (onPeek) {
            entries.push({
                kind: 'item',
                key: 'peek',
                label: tr('peek'),
                icon: <EyeIcon className="size-4 text-muted-foreground" />,
                onSelect: onPeek,
            });
        }
        if (onQuickEdit) {
            entries.push({
                kind: 'item',
                key: 'quick-edit',
                label: tr('quickEdit'),
                icon: <PencilSquareIcon className="size-4 text-muted-foreground" />,
                onSelect: onQuickEdit,
            });
        }

        for (const id of REGISTRY_ORDER) {
            if (id === '--') {
                entries.push({ kind: 'separator', key: `sep-${entries.length}` });
                continue;
            }
            const action = getAction(id);
            if (!action || !isAvailableForRecord(id, record)) continue;
            const Icon = action.icon;
            entries.push({
                kind: 'item',
                key: id,
                label: t(action.labelKey),
                icon: Icon ? <Icon className="size-4 text-muted-foreground" /> : null,
                onSelect: () => void run(id, { source: 'menu', record }),
            });
        }

        if (onDelete) {
            entries.push({ kind: 'separator', key: `sep-delete` });
            entries.push({
                kind: 'item',
                key: 'delete',
                label: tr('delete'),
                icon: <TrashIcon className="size-4 text-destructive" />,
                destructive: true,
                onSelect: onDelete,
            });
        }

        return collapseSeparators(entries);
    }, [model, getAction, isAvailableForRecord, run, t, tr]);
}

function collapseSeparators(entries: MenuEntry[]): MenuEntry[] {
    const out: MenuEntry[] = [];
    for (const entry of entries) {
        if (entry.kind === 'separator') {
            if (out.length === 0 || out[out.length - 1].kind === 'separator') continue;
            out.push(entry);
        } else {
            out.push(entry);
        }
    }
    while (out.length && out[out.length - 1].kind === 'separator') out.pop();
    return out;
}

/**
 * Wraps a record row or card so a right-click (or the platform context-menu key when the element is
 * focused) opens the shared record action menu. Left-click behaviour of the wrapped element is
 * untouched. Renders nothing extra in the DOM flow — the content is portaled — so it is safe to wrap
 * a `<tr>` or a grid card.
 */
export function RecordContextMenu({ model, children }: { model: RecordMenuModel; children: ReactNode }) {
    const entries = useRecordMenuEntries(model);
    return (
        <ContextMenu>
            <ContextMenuTrigger asChild>{children}</ContextMenuTrigger>
            <ContextMenuContent>
                {entries.map((entry) =>
                    entry.kind === 'separator' ? (
                        <ContextMenuSeparator key={entry.key} />
                    ) : (
                        <ContextMenuItem
                            key={entry.key}
                            variant={entry.destructive ? 'destructive' : 'default'}
                            onSelect={entry.onSelect}
                        >
                            {entry.icon}
                            {entry.label}
                        </ContextMenuItem>
                    ),
                )}
            </ContextMenuContent>
        </ContextMenu>
    );
}

/**
 * The always-visible kebab that opens the same record action menu as {@link RecordContextMenu}. Both
 * consume {@link useRecordMenuEntries}, so the pointer, keyboard, and right-click surfaces can never
 * drift. The trigger is keyboard-operable and reveals on row/card hover or focus.
 */
export function RecordActionMenuTrigger({ model }: { model: RecordMenuModel }) {
    const entries = useRecordMenuEntries(model);
    const tr = useTranslations('RecordActionMenu');
    return (
        <DropdownMenu>
            <DropdownMenuTrigger asChild>
                <button
                    type="button"
                    aria-label={tr('menuAria', { name: model.record.label })}
                    className="flex size-7 items-center justify-center rounded-full text-muted-foreground opacity-0 transition hover:bg-muted/70 hover:text-foreground group-hover:opacity-100 focus:opacity-100 focus-visible:opacity-100 data-[state=open]:opacity-100"
                >
                    <EllipsisHorizontalIcon className="size-5" />
                </button>
            </DropdownMenuTrigger>
            <DropdownMenuContent align="end" className="w-48">
                {entries.map((entry) =>
                    entry.kind === 'separator' ? (
                        <DropdownMenuSeparator key={entry.key} />
                    ) : (
                        <DropdownMenuItem
                            key={entry.key}
                            variant={entry.destructive ? 'destructive' : 'default'}
                            onSelect={entry.onSelect}
                        >
                            {entry.icon}
                            {entry.label}
                        </DropdownMenuItem>
                    ),
                )}
            </DropdownMenuContent>
        </DropdownMenu>
    );
}
