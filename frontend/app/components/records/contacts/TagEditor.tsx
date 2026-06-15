'use client';

import { startTransition, useOptimistic } from 'react';
import { useRouter } from 'next/navigation';
import { useTranslations } from 'next-intl';
import { toastError } from '@/app/lib/toast';
import { PlusIcon, XMarkIcon } from '@heroicons/react/24/outline';

import { Badge } from '@/components/ui/badge';
import {
    DropdownMenu,
    DropdownMenuContent,
    DropdownMenuItem,
    DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu';
import { addCompanyTag, addContactTag, removeCompanyTag, removeContactTag } from '@/app/lib/api';
import { readableTextColor } from '@/app/lib/utils';
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
                toastError(err instanceof Error ? err.message : t('toastFailedAdd'));
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
                toastError(err instanceof Error ? err.message : t('toastFailedRemove'));
            }
        });
    };

    return (
        <>
            {optimisticTags.map((tag) => (
                <Badge
                    key={tag.id}
                    variant="default"
                    className="group/tag text-sm transform duration-200"
                    style={{ backgroundColor: tag.color, color: readableTextColor(tag.color) }}
                >
                    {tag.name}
                    <button
                        type="button"
                        aria-label={t('removeAria', { name: tag.name })}
                        onClick={() => handleRemove(tag)}
                        className="-mr-0.5 hidden h-3.5 w-3.5 items-center justify-center rounded-full transition group-hover/tag:inline-flex hover:bg-foreground/10"
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
                            className="inline-flex items-center gap-1 rounded-4xl border border-dashed border-border px-2 py-0.5 text-xs text-muted-foreground transition hover:border-muted-foreground hover:text-foreground"
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
