'use client';

import { Fragment, forwardRef, type ComponentProps, type ComponentType, type ReactNode, useMemo, useState } from 'react';
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
import { cn } from '@/lib/utils';
import { useActions } from '@/app/hooks/useActions';
import type { ActionId, ActiveRecordRef } from '@/app/lib/actions/types';

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

type MenuItemDescriptor = {
    key: string;
    label: string;
    icon: ReactNode;
    destructive?: boolean;
    onSelect: () => void;
};

/** Registry action ids surfaced in a record menu, grouped so separators fall between groups. */
const REGISTRY_VIEW = ['record.open', 'record.open-new-tab'] as const;
const REGISTRY_CREATE = ['create.task', 'create.note', 'create.activity'] as const;

/**
 * Resolves the record menu into ordered groups of items — browser-local peek/open at the top, the
 * create actions, then quick-edit/copy-link, then a destructive delete. Registry items are resolved
 * against the row's record via {@link useActions}'s per-record override so availability reflects the
 * row, not the page. Returns empty until `enabled` (the row's menu has been opened at least once),
 * so rows never interacted with — nearly all of them — pay nothing on a parent re-render; once opened
 * a row keeps computing so its items stay rendered through the menu's exit animation. Depends on the
 * live `actions` list so it recomputes once the provider registers its seed actions (which happens in
 * a mount effect) rather than caching a registry-less menu.
 */
function useRecordMenuGroups(model: RecordMenuModel, enabled: boolean): MenuItemDescriptor[][] {
    const { actions, getAction, isAvailableForRecord, run } = useActions();
    const t = useTranslations('Actions');
    const tr = useTranslations('RecordActionMenu');

    return useMemo(() => {
        if (!enabled || actions.length === 0) return [];
        const { record, onPeek, onQuickEdit, onDelete } = model;

        const registry = (id: ActionId): MenuItemDescriptor | null => {
            const action = getAction(id);
            if (!action || !isAvailableForRecord(id, record)) return null;
            const Icon = action.icon;
            return {
                key: id,
                label: t(action.labelKey),
                icon: Icon ? <Icon className="size-4 text-muted-foreground" /> : null,
                onSelect: () => void run(id, { source: 'menu', record }),
            };
        };
        const peek: MenuItemDescriptor | null = onPeek
            ? { key: 'peek', label: tr('peek'), icon: <EyeIcon className="size-4 text-muted-foreground" />, onSelect: onPeek }
            : null;
        const quickEdit: MenuItemDescriptor | null = onQuickEdit
            ? { key: 'quick-edit', label: tr('quickEdit'), icon: <PencilSquareIcon className="size-4 text-muted-foreground" />, onSelect: onQuickEdit }
            : null;
        const remove: MenuItemDescriptor | null = onDelete
            ? { key: 'delete', label: tr('delete'), icon: <TrashIcon className="size-4 text-destructive" />, destructive: true, onSelect: onDelete }
            : null;

        const groups: (MenuItemDescriptor | null)[][] = [
            [peek, ...REGISTRY_VIEW.map(registry)],
            REGISTRY_CREATE.map(registry),
            [quickEdit, registry('record.copy-link')],
            [remove],
        ];
        return groups.map((group) => group.filter((item): item is MenuItemDescriptor => item !== null)).filter((group) => group.length > 0);
    }, [enabled, actions, model, getAction, isAvailableForRecord, run, t, tr]);
}

type MenuItemComponent = ComponentType<{ variant?: 'default' | 'destructive'; onSelect?: (event: Event) => void; children?: ReactNode }>;

/** Renders resolved menu groups with the primitives of one menu family, so both surfaces share one layout. */
function MenuBody({
    groups,
    Item,
    Separator,
}: {
    groups: MenuItemDescriptor[][];
    Item: MenuItemComponent;
    Separator: ComponentType;
}) {
    return groups.map((group, index) => (
        <Fragment key={`group-${index}`}>
            {index > 0 && <Separator />}
            {group.map((item) => (
                <Item key={item.key} variant={item.destructive ? 'destructive' : 'default'} onSelect={item.onSelect}>
                    {item.icon}
                    {item.label}
                </Item>
            ))}
        </Fragment>
    ));
}

/** The ellipsis button shared by the record menu kebab and the basic RowActions trigger. */
export const RecordActionsTriggerButton = forwardRef<HTMLButtonElement, { ariaLabel: string } & ComponentProps<'button'>>(
    function RecordActionsTriggerButton({ ariaLabel, className, ...props }, ref) {
        return (
            <button
                ref={ref}
                type="button"
                aria-label={ariaLabel}
                className={cn(
                    'flex size-7 items-center justify-center rounded-full text-muted-foreground opacity-0 transition hover:bg-muted/70 hover:text-foreground group-hover:opacity-100 focus:opacity-100 focus-visible:opacity-100 data-[state=open]:opacity-100',
                    className,
                )}
                {...props}
            >
                <EllipsisHorizontalIcon className="size-5" />
            </button>
        );
    },
);

/**
 * Wraps a record row or card so a right-click (or the platform context-menu key when the element is
 * focused) opens the shared record action menu. Left-click behaviour of the wrapped element is
 * untouched, and the content is portaled so it is safe to wrap a `<tr>` or a grid card.
 */
export function RecordContextMenu({ model, children }: { model: RecordMenuModel; children: ReactNode }) {
    const [activated, setActivated] = useState(false);
    const groups = useRecordMenuGroups(model, activated);
    return (
        <ContextMenu onOpenChange={(open) => open && setActivated(true)}>
            <ContextMenuTrigger asChild>{children}</ContextMenuTrigger>
            <ContextMenuContent>
                <MenuBody groups={groups} Item={ContextMenuItem} Separator={ContextMenuSeparator} />
            </ContextMenuContent>
        </ContextMenu>
    );
}

/**
 * The always-visible kebab that opens the same record action menu as {@link RecordContextMenu}. Both
 * consume {@link useRecordMenuGroups}, so the pointer, keyboard, and right-click surfaces can never
 * drift. The trigger is keyboard-operable and reveals on row/card hover or focus.
 */
export function RecordActionMenuTrigger({ model }: { model: RecordMenuModel }) {
    const [activated, setActivated] = useState(false);
    const groups = useRecordMenuGroups(model, activated);
    const tr = useTranslations('RecordActionMenu');
    return (
        <DropdownMenu onOpenChange={(open) => open && setActivated(true)}>
            <DropdownMenuTrigger asChild>
                <RecordActionsTriggerButton ariaLabel={tr('menuAria', { name: model.record.label })} />
            </DropdownMenuTrigger>
            <DropdownMenuContent align="end" className="w-48">
                <MenuBody groups={groups} Item={DropdownMenuItem} Separator={DropdownMenuSeparator} />
            </DropdownMenuContent>
        </DropdownMenu>
    );
}
