'use client';

import type { ComponentType } from 'react';
import { useTranslations } from 'next-intl';
import { PlusIcon } from '@heroicons/react/16/solid';
import { CheckCircleIcon, DocumentTextIcon } from '@heroicons/react/24/outline';
import { Loader2Icon } from 'lucide-react';

import {
    DropdownMenu,
    DropdownMenuContent,
    DropdownMenuItem,
    DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu';
import type { ActionId } from '@/app/lib/actions/types';
import { useActions } from '@/app/hooks/useActions';

type QuickItem = { id: ActionId; label: string; Icon: ComponentType<{ className?: string }> };

/**
 * Dashboard "New" launcher. It dispatches to the shared action registry so the task and note flows
 * are identical to every other surface (the global actions menu, the command palette) rather than
 * carrying their own duplicated create logic.
 */
export default function QuickCreate() {
    const t = useTranslations('DashboardQuickCreate');
    const { run, pendingIds } = useActions();

    const items: QuickItem[] = [
        { id: 'create.task', label: t('newTask'), Icon: CheckCircleIcon },
        { id: 'create.note', label: t('newNote'), Icon: DocumentTextIcon },
    ];

    return (
        <DropdownMenu>
            <DropdownMenuTrigger asChild>
                <button
                    type="button"
                    className="inline-flex items-center gap-1.5 rounded-full bg-brand px-4 py-2 text-sm font-semibold text-brand-foreground transition-[transform,background-color] duration-150 ease-out hover:bg-brand-hover active:scale-[0.97] motion-reduce:transition-none motion-reduce:active:scale-100"
                >
                    <PlusIcon className="size-4" />
                    {t('new')}
                </button>
            </DropdownMenuTrigger>
            <DropdownMenuContent align="end" className="w-44">
                {items.map(({ id, label, Icon }) => {
                    const pending = pendingIds.has(id);
                    return (
                        <DropdownMenuItem
                            key={id}
                            disabled={pending}
                            onSelect={(event) => {
                                event.preventDefault();
                                void run(id, { source: 'menu' });
                            }}
                        >
                            {pending ? (
                                <Loader2Icon className="size-4 animate-spin text-muted-foreground" />
                            ) : (
                                <Icon className="size-4 text-muted-foreground" />
                            )}
                            {label}
                        </DropdownMenuItem>
                    );
                })}
            </DropdownMenuContent>
        </DropdownMenu>
    );
}
