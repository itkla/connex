'use client';

import { Fragment, forwardRef, type ComponentProps, type ComponentType, type ReactNode, useMemo, useState } from 'react';
import { useTranslations } from 'next-intl';
import {
    ArchiveBoxArrowDownIcon,
    ArchiveBoxIcon,
    EllipsisHorizontalIcon,
    EyeIcon,
    PencilSquareIcon,
    TrashIcon,
} from '@heroicons/react/24/outline';

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
import { actionLabel } from '@/app/lib/actions/actionLabels';
import type { ActionId } from '@/app/lib/actions/types';
import type { RecordMenuModel, RecordRemoveIntent } from '@/app/components/records/types';

export type { RecordMenuExtraItem, RecordMenuModel } from '@/app/components/records/types';

type MenuItemDescriptor = {
    key: string;
    label: string;
    icon?: ReactNode;
    destructive?: boolean;
    onSelect: () => void;
};

/** Registry action ids surfaced in a record menu, grouped so separators fall between groups. */
const REGISTRY_VIEW = ['record.open', 'record.open-new-tab'] as const;
const REGISTRY_CREATE = ['create.task', 'create.note', 'create.activity'] as const;

type ResolvedGroupItems<T> = {
    peek: T | null;
    registryView: readonly (T | null)[];
    registryCreate: readonly (T | null)[];
    extraItems: readonly T[];
    quickEdit: T | null;
    runWorkflow: T | null;
    copyLink: T | null;
    remove: T | null;
};

/** Resolves the shared group structure independently of either menu primitive. */
function resolveRecordMenuGroups<T>(model: RecordMenuModel, items: ResolvedGroupItems<T>): T[][] {
    const {
        includeCreateActions = true,
        includeRecordActions = true,
        allowRecordMutation = true,
    } = model;
    const groups: (T | null)[][] = [
        includeRecordActions ? [items.peek, ...items.registryView] : [],
        includeRecordActions && includeCreateActions ? [...items.registryCreate] : [],
        includeRecordActions
            ? [allowRecordMutation ? items.quickEdit : null, ...items.extraItems]
            : [],
        includeRecordActions
            ? [allowRecordMutation ? items.runWorkflow : null, items.copyLink]
            : [],
        [items.remove],
    ];
    const resolvedGroups: T[][] = [];
    for (const group of groups) {
        const resolvedGroup: T[] = [];
        for (const item of group) {
            if (item !== null) resolvedGroup.push(item);
        }
        if (resolvedGroup.length > 0) resolvedGroups.push(resolvedGroup);
    }
    return resolvedGroups;
}

/**
 * The removal item per intent. Archiving and restoring are reversible, so they are ordinary items
 * with the archive icons rather than destructive ones; only a real delete stays destructive.
 */
const REMOVE_ITEM: Record<RecordRemoveIntent, Omit<MenuItemDescriptor, 'label' | 'onSelect'>> = {
    delete: { key: 'delete', icon: <TrashIcon className="size-4 text-destructive" />, destructive: true },
    archive: { key: 'archive', icon: <ArchiveBoxArrowDownIcon className="size-4 text-muted-foreground" /> },
    restore: { key: 'restore', icon: <ArchiveBoxIcon className="size-4 text-muted-foreground" /> },
};

/**
 * Resolves the record menu into ordered groups of items — browser-local peek/open at the top, the
 * create actions, contact-local actions, shared record actions, then removal. Registry items resolve
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
    const tMessage = useTranslations();
    const tr = useTranslations('RecordActionMenu');

    return useMemo(() => {
        if (!enabled) return [];
        const {
            record,
            onOpen,
            onPeek,
            onQuickEdit,
            onRemove,
            removeIntent = 'delete',
        } = model;

        const registry = (id: ActionId): MenuItemDescriptor | null => {
            if (!actions.some((action) => action.id === id)) return null;
            const action = getAction(id);
            if (!action || !isAvailableForRecord(id, record)) return null;
            const Icon = action.icon;
            return {
                key: id,
                label: actionLabel(action, t, tMessage),
                icon: Icon ? <Icon className="size-4 text-muted-foreground" /> : null,
                onSelect: id === 'record.open' && onOpen
                    ? onOpen
                    : () => void run(id, { source: 'menu', record }),
            };
        };
        const peek: MenuItemDescriptor | null = onPeek
            ? { key: 'peek', label: tr('peek'), icon: <EyeIcon className="size-4 text-muted-foreground" />, onSelect: onPeek }
            : null;
        const quickEdit: MenuItemDescriptor | null = onQuickEdit
            ? { key: 'quick-edit', label: tr('quickEdit'), icon: <PencilSquareIcon className="size-4 text-muted-foreground" />, onSelect: onQuickEdit }
            : null;
        const remove: MenuItemDescriptor | null = onRemove
            ? { ...REMOVE_ITEM[removeIntent], label: tr(removeIntent), onSelect: onRemove }
            : null;

        return resolveRecordMenuGroups(model, {
            peek,
            registryView: REGISTRY_VIEW.map(registry),
            registryCreate: REGISTRY_CREATE.map(registry),
            extraItems: model.extraItems ?? [],
            quickEdit,
            runWorkflow: registry('record.run-workflow'),
            copyLink: registry('record.copy-link'),
            remove,
        });
    }, [enabled, actions, model, getAction, isAvailableForRecord, run, t, tMessage, tr]);
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
        <Fragment key={group.map((item) => item.key).join(':')}>
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
                    'flex size-7 items-center justify-center rounded-full text-muted-foreground opacity-100 transition hover:bg-muted/70 hover:text-foreground sm:opacity-0 sm:group-hover:opacity-100 focus:opacity-100 focus-visible:opacity-100 data-[state=open]:opacity-100',
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
export function RecordContextMenu({
    model,
    children,
    onOpenChange,
}: {
    model: RecordMenuModel;
    children: ReactNode;
    onOpenChange?: (open: boolean) => void;
}) {
    const [activated, setActivated] = useState(false);
    const groups = useRecordMenuGroups(model, activated);
    return (
        <ContextMenu onOpenChange={(open) => {
            if (open) setActivated(true);
            onOpenChange?.(open);
        }}>
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
export function RecordActionMenuTrigger({
    model,
    triggerClassName,
}: {
    model: RecordMenuModel;
    triggerClassName?: string;
}) {
    const [activated, setActivated] = useState(false);
    const groups = useRecordMenuGroups(model, activated);
    const tr = useTranslations('RecordActionMenu');
    return (
        <DropdownMenu onOpenChange={(open) => open && setActivated(true)}>
            <DropdownMenuTrigger asChild>
                <RecordActionsTriggerButton
                    ariaLabel={tr('menuAria', { name: model.record.label })}
                    className={triggerClassName}
                />
            </DropdownMenuTrigger>
            <DropdownMenuContent align="end" className="w-48">
                <MenuBody groups={groups} Item={DropdownMenuItem} Separator={DropdownMenuSeparator} />
            </DropdownMenuContent>
        </DropdownMenu>
    );
}
