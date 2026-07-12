'use client';

import { useMemo, useState } from 'react';
import { useTranslations } from 'next-intl';
import { Loader2Icon } from 'lucide-react';
import { ShieldCheckIcon } from '@heroicons/react/24/outline';

import {
    Dialog,
    DialogContent,
    DialogHeader,
    DialogTitle,
    DialogDescription,
    DialogFooter,
    DialogClose,
} from '@/components/ui/dialog';
import { Button } from '@/components/ui/button';
import { Label } from '@/components/ui/label';
import {
    DialogStatusCover,
    resolveDialogStatus,
    fieldInputClass,
    fieldErrorClass,
    fieldLeadIconClass,
} from '@/components/ui/dialog-status-cover';
import { cn } from '@/lib/utils';
import type { CustomRole } from '@/app/lib/types';
import { type PermissionGroup } from './permissions';

type Props = {
    open: boolean;
    onOpenChange: (open: boolean) => void;
    groups: PermissionGroup[];
    editing: CustomRole | null;
    onSubmit: (name: string, permissions: string[]) => Promise<void>;
};

/**
 * Create or edit a custom workspace role. Mirrors the app's create-dialog kit:
 * pixel status band, staggered ncd-rise entrance, and a brief success hold.
 *
 * Submit lifecycle (saving / success lock) lives here so the dialog can hold
 * itself open through the success band; the field state lives in `RoleForm`,
 * remounted per target via `key` so it initializes from props without an effect.
 */
export default function RoleDialog({ open, onOpenChange, groups, editing, onSubmit }: Props) {
    const [isSaving, setIsSaving] = useState(false);
    const [succeeded, setSucceeded] = useState(false);

    const handleOpenChange = (next: boolean) => {
        if (!next && (isSaving || succeeded)) return;
        if (!next) {
            setIsSaving(false);
            setSucceeded(false);
        }
        onOpenChange(next);
    };

    const handleSubmit = async (name: string, permissions: string[]) => {
        setIsSaving(true);
        try {
            await onSubmit(name, permissions);
            setIsSaving(false);
            setSucceeded(true);
            setTimeout(() => {
                setSucceeded(false);
                onOpenChange(false);
            }, 900);
        } catch (err) {
            setIsSaving(false);
            throw err;
        }
    };

    return (
        <Dialog open={open} onOpenChange={handleOpenChange}>
            <DialogContent className="gap-0 overflow-hidden p-0 sm:max-w-lg">
                {open && (
                    <RoleForm
                        key={editing ? `edit-${editing.id}` : 'new'}
                        groups={groups}
                        editing={editing}
                        isSaving={isSaving}
                        succeeded={succeeded}
                        onSubmit={handleSubmit}
                    />
                )}
            </DialogContent>
        </Dialog>
    );
}

function RoleForm({
    groups,
    editing,
    isSaving,
    succeeded,
    onSubmit,
}: {
    groups: PermissionGroup[];
    editing: CustomRole | null;
    isSaving: boolean;
    succeeded: boolean;
    onSubmit: (name: string, permissions: string[]) => Promise<void>;
}) {
    const t = useTranslations('WorkspaceRoles');

    const [name, setName] = useState(editing?.name ?? '');
    const [selected, setSelected] = useState<Set<string>>(() => new Set(editing?.permissions ?? []));
    const [nameError, setNameError] = useState<string | null>(null);

    const allPermissions = useMemo(
        () => groups.flatMap((g) => g.items.map((i) => i.permission)),
        [groups],
    );

    const status = resolveDialogStatus({
        isLoading: isSaving,
        hasErrors: Boolean(nameError),
        isSuccess: succeeded,
    });

    const locked = isSaving || succeeded;

    const toggle = (permission: string) => {
        setSelected((prev) => {
            const next = new Set(prev);
            if (next.has(permission)) next.delete(permission);
            else next.add(permission);
            return next;
        });
    };

    const setGroup = (items: string[], on: boolean) => {
        setSelected((prev) => {
            const next = new Set(prev);
            for (const p of items) {
                if (on) next.add(p);
                else next.delete(p);
            }
            return next;
        });
    };

    const allSelected = allPermissions.length > 0 && selected.size === allPermissions.length;

    const handleSubmit = async () => {
        if (locked) return;
        const trimmed = name.trim();
        if (!trimmed) {
            setNameError(t('nameRequired'));
            requestAnimationFrame(() => document.getElementById('role-name')?.focus());
            return;
        }
        setNameError(null);
        try {
            await onSubmit(trimmed, [...selected]);
        } catch {
            return;
        }
    };

    return (
        <>
            <DialogStatusCover status={status} />

            <div className="px-6 pb-6">
                <DialogHeader className="ncd-rise -mt-12 mb-5" style={{ animationDelay: '40ms' }}>
                    <DialogTitle className="text-xl font-semibold tracking-tight">
                        {editing ? t('editTitle') : t('createTitle')}
                    </DialogTitle>
                    <DialogDescription>{t('createDescription')}</DialogDescription>
                </DialogHeader>

                <form
                    onSubmit={(e) => {
                        e.preventDefault();
                        handleSubmit();
                    }}
                    className="grid gap-5"
                >
                    <div className="ncd-rise grid gap-1.5" style={{ animationDelay: '90ms' }}>
                        <Label htmlFor="role-name">{t('roleName')}</Label>
                        <div className="group relative">
                            <ShieldCheckIcon className={fieldLeadIconClass} />
                            <input
                                id="role-name"
                                type="text"
                                value={name}
                                onChange={(e) => {
                                    setName(e.target.value);
                                    if (nameError) setNameError(null);
                                }}
                                className={cn(fieldInputClass, 'pl-9 pr-3', nameError && fieldErrorClass)}
                                placeholder={t('roleNamePlaceholder')}
                                aria-invalid={Boolean(nameError)}
                                aria-describedby={nameError ? 'role-name-error' : undefined}
                                autoFocus
                                maxLength={64}
                            />
                        </div>
                        {nameError && (
                            <p id="role-name-error" className="text-sm text-destructive">
                                {nameError}
                            </p>
                        )}
                    </div>

                    <div className="ncd-rise grid gap-2" style={{ animationDelay: '140ms' }}>
                        <div className="flex items-baseline justify-between">
                            <Label>{t('permissionsLabel')}</Label>
                            <div className="flex items-center gap-2 text-xs">
                                <span className="text-muted-foreground">
                                    {t('selectedCount', { count: selected.size })}
                                </span>
                                <span aria-hidden className="text-border">|</span>
                                <button
                                    type="button"
                                    onClick={() => setSelected(allSelected ? new Set() : new Set(allPermissions))}
                                    className="font-medium text-brand transition hover:text-brand-hover"
                                >
                                    {allSelected ? t('clearAll') : t('selectAll')}
                                </button>
                            </div>
                        </div>

                        <div className="max-h-[40vh] divide-y divide-border overflow-y-auto overflow-x-hidden rounded-xl bg-muted ring-1 ring-border">
                            {groups.map(({ group, label, items }) => {
                                const keys = items.map((i) => i.permission);
                                const on = keys.filter((k) => selected.has(k)).length;
                                const groupAll = on === keys.length;
                                return (
                                    <div key={group} className="px-3.5 py-3">
                                        <div className="mb-2 flex items-center justify-between gap-2">
                                            <span className="text-xs font-medium tracking-[0.12em] text-muted-foreground uppercase">
                                                {label}
                                            </span>
                                            <button
                                                type="button"
                                                onClick={() => setGroup(keys, !groupAll)}
                                                className="text-[11px] font-medium text-muted-foreground transition hover:text-brand"
                                            >
                                                {groupAll ? t('clearAll') : t('selectAll')}
                                            </button>
                                        </div>
                                        <div className="grid grid-cols-2 gap-1">
                                            {items.map(({ permission, action }) => {
                                                const checked = selected.has(permission);
                                                return (
                                                    <label
                                                        key={permission}
                                                        className={cn(
                                                            'flex cursor-pointer items-center gap-2 rounded-lg px-2 py-1.5 text-sm transition',
                                                            checked
                                                                ? 'bg-background text-foreground ring-1 ring-border'
                                                                : 'text-muted-foreground hover:bg-background/60 hover:text-foreground',
                                                        )}
                                                    >
                                                        <input
                                                            type="checkbox"
                                                            checked={checked}
                                                            onChange={() => toggle(permission)}
                                                            className="size-4 shrink-0 rounded border-border accent-brand"
                                                        />
                                                        <span className="truncate">{action}</span>
                                                    </label>
                                                );
                                            })}
                                        </div>
                                    </div>
                                );
                            })}
                        </div>
                        {selected.size === 0 && (
                            <p className="text-xs text-muted-foreground">{t('noPermissionsHint')}</p>
                        )}
                    </div>

                    <DialogFooter className="ncd-rise mt-1" style={{ animationDelay: '190ms' }}>
                        <DialogClose asChild>
                            <Button type="button" variant="outline" disabled={locked}>
                                {t('cancel')}
                            </Button>
                        </DialogClose>
                        <Button
                            type="submit"
                            variant="brand"
                            disabled={locked}
                            className="min-w-24 shadow-sm transition hover:shadow-md"
                        >
                            {isSaving ? (
                                <Loader2Icon className="size-4 animate-spin" />
                            ) : editing ? (
                                t('save')
                            ) : (
                                t('create')
                            )}
                        </Button>
                    </DialogFooter>
                </form>
            </div>
        </>
    );
}
