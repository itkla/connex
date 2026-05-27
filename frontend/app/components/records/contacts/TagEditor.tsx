'use client';

import { startTransition, useOptimistic } from 'react';
import { useRouter } from 'next/navigation';
import { useTranslations } from 'next-intl';
import { toast } from 'sonner';
import { PlusIcon, XMarkIcon } from '@heroicons/react/24/outline';

import { Badge } from '@/components/ui/badge';
import {
    DropdownMenu,
    DropdownMenuContent,
    DropdownMenuItem,
    DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu';
import { addCompanyTag, addContactTag, removeCompanyTag, removeContactTag } from '@/app/lib/api';
import { type Tag } from '@/app/lib/types';

type TagAction = { type: 'add'; tag: Tag } | { type: 'remove'; tagId: number };

type Props = {
    currentTags: Tag[];
    allTags: Tag[];
} & ({ contactId: number; companyId?: never } | { companyId: number; contactId?: never });

export default function TagEditor({
    contactId,
    companyId,
    currentTags,
    allTags,
}: Props) {
    const router = useRouter();
    const t = useTranslations('ContactsTagEditor');
    const [optimisticTags, applyOptimistic] = useOptimistic<Tag[], TagAction>(
        currentTags,
        (state, action) => {
            if (action.type === 'add') return [...state, action.tag];
            return state.filter((t) => t.id !== action.tagId);
        },
    );

    const currentIds = new Set(optimisticTags.map((t) => t.id));
    const availableTags = allTags.filter((t) => !currentIds.has(t.id));

    const addTag = (tagId: number) =>
        contactId != null ? addContactTag(contactId, tagId) : addCompanyTag(companyId!, tagId);
    const removeTag = (tagId: number) =>
        contactId != null ? removeContactTag(contactId, tagId) : removeCompanyTag(companyId!, tagId);

    const handleAdd = (tag: Tag) => {
        startTransition(async () => {
            applyOptimistic({ type: 'add', tag });
            try {
                await addTag(tag.id);
                router.refresh();
            } catch (err) {
                toast.error(err instanceof Error ? err.message : t('toastFailedAdd'), {
                    style: { backgroundColor: 'var(--color-destructive)', color: 'white' },
                });
            }
        });
    };

    const handleRemove = (tag: Tag) => {
        startTransition(async () => {
            applyOptimistic({ type: 'remove', tagId: tag.id });
            try {
                await removeTag(tag.id);
                router.refresh();
            } catch (err) {
                toast.error(err instanceof Error ? err.message : t('toastFailedRemove'), {
                    style: { backgroundColor: 'var(--color-destructive)', color: 'white' },
                });
            }
        });
    };

    return (
        <>
            {optimisticTags.map((tag) => (
                <Badge
                    key={tag.id}
                    variant="default"
                    className="group/tag text-sm text-white transform duration-200"
                    style={{ backgroundColor: tag.color }}
                >
                    {tag.name}
                    <button
                        type="button"
                        aria-label={t('removeAria', { name: tag.name })}
                        onClick={() => handleRemove(tag)}
                        className="-mr-0.5 hidden h-3.5 w-3.5 items-center justify-center rounded-full transition group-hover/tag:inline-flex hover:bg-black/20"
                    >
                        <XMarkIcon className="size-3" />
                    </button>
                </Badge>
            ))}
            {availableTags.length > 0 ? (
                <DropdownMenu>
                    <DropdownMenuTrigger asChild>
                        <button
                            type="button"
                            aria-label={t('addTagAria')}
                            className="inline-flex items-center gap-1 rounded-4xl border border-dashed border-neutral-300 px-2 py-0.5 text-xs text-neutral-500 transition hover:border-neutral-400 hover:text-neutral-700"
                        >
                            <PlusIcon className="size-3" />
                            {t('tag')}
                        </button>
                    </DropdownMenuTrigger>
                    <DropdownMenuContent align="start">
                        {availableTags.map((tag) => (
                            <DropdownMenuItem
                                key={tag.id}
                                onSelect={() => handleAdd(tag)}
                                className="flex items-center gap-2"
                            >
                                <span
                                    className="size-2.5 shrink-0 rounded-full"
                                    style={{ backgroundColor: tag.color }}
                                />
                                <span>{tag.name}</span>
                            </DropdownMenuItem>
                        ))}
                    </DropdownMenuContent>
                </DropdownMenu>
            ) : null}
        </>
    );
}
