'use client';

import Link from 'next/link';
import { useTranslations } from 'next-intl';
import { toast } from 'sonner';
import {
    EllipsisHorizontalIcon,
    PencilIcon,
    TrashIcon,
    DocumentDuplicateIcon,
    UserIcon,
    BriefcaseIcon,
} from '@heroicons/react/24/outline';

import {
    DropdownMenu,
    DropdownMenuTrigger,
    DropdownMenuContent,
    DropdownMenuItem,
    DropdownMenuSeparator,
} from '@/components/ui/dropdown-menu';
import { Avatar, AvatarFallback, AvatarImage } from '@/components/ui/avatar';
import { Tooltip, TooltipContent, TooltipTrigger } from '@/components/ui/tooltip';
import { copyToClipboard, formatShortDate } from '@/app/lib/utils';
import type { Contact, Deal, Note, User } from '@/app/lib/types';

interface NoteCardProps {
    note: Note;
    person?: Contact;
    deal?: Deal;
    author?: User;
    onEdit?: () => void;
    onDelete?: () => void;
}

export default function NoteCard({ note, person, deal, author, onEdit, onDelete }: NoteCardProps) {
    const t = useTranslations('ActivityNotesCard');
    const updated = note.updatedAt ?? note.createdAt;
    const authorName = author?.displayName || author?.username || '';

    const copyContent = () => {
        if (copyToClipboard(note.content, 'Note')) {
            toast.success(t('toastContentCopied'), {
                style: { backgroundColor: 'var(--color-brand)', color: 'white' },
            });
        } else {
            toast.error(t('toastFailedCopy'), {
                style: { backgroundColor: 'var(--color-destructive)', color: 'white' },
            });
        }
    };

    return (
        <div
            className="relative flex w-full min-w-0 flex-col gap-3 rounded-2xl bg-white p-4 ring-1 ring-black/5 shadow-sm transition duration-300 hover:shadow-lg hover:-translate-y-0.5 cursor-pointer"
            onClick={() => onEdit?.()}
        >
            <div className="flex min-w-0 flex-wrap items-center gap-1.5 pr-9">
                {person ? (
                    <Link
                        href={`/records/contacts/${person.id}`}
                        onClick={(e) => e.stopPropagation()}
                        className="inline-flex max-w-[12rem] items-center gap-1 rounded-full bg-brand-light/40 px-2 py-0.5 text-xs font-medium text-brand-dark transition hover:bg-brand-light"
                        title={person.name}
                    >
                        <UserIcon className="size-3 shrink-0" />
                        <span className="truncate">{person.name}</span>
                    </Link>
                ) : null}
                {deal ? (
                    <Link
                        href={`/records/deals/${deal.id}`}
                        onClick={(e) => e.stopPropagation()}
                        className="inline-flex max-w-[12rem] items-center gap-1 rounded-full bg-neutral-100 px-2 py-0.5 text-xs font-medium text-neutral-700 transition hover:bg-neutral-200"
                        title={deal.name}
                    >
                        <BriefcaseIcon className="size-3 shrink-0" />
                        <span className="truncate">{deal.name}</span>
                    </Link>
                ) : null}
                {!person && !deal ? (
                    <span className="inline-flex items-center rounded-full bg-neutral-50 px-2 py-0.5 text-xs font-medium text-neutral-400 ring-1 ring-inset ring-neutral-200">
                        {t('private')}
                    </span>
                ) : null}
            </div>

            <p className="line-clamp-6 text-sm whitespace-pre-wrap text-neutral-800">
                {note.content}
            </p>

            <div className="mt-auto flex items-center justify-between gap-2 pt-1">
                <div className="flex min-w-0 items-center gap-2">
                    {author ? (
                        <Tooltip>
                            <TooltipTrigger asChild>
                                <Avatar size="sm" className="ring-1 ring-black/5">
                                    {author.profilePictureUrl ? (
                                        <AvatarImage
                                            src={author.profilePictureUrl}
                                            alt={authorName}
                                        />
                                    ) : (
                                        <AvatarFallback>
                                            <UserIcon className="size-3 text-neutral-500" />
                                        </AvatarFallback>
                                    )}
                                </Avatar>
                            </TooltipTrigger>
                            <TooltipContent side="bottom" align="start">
                                {authorName || t('unknownAuthor')}
                            </TooltipContent>
                        </Tooltip>
                    ) : null}
                    <span className="truncate text-xs text-neutral-500">
                        {formatShortDate(updated)}
                    </span>
                </div>
            </div>

            <DropdownMenu>
                <DropdownMenuTrigger asChild>
                    <button
                        type="button"
                        aria-label={t('actionsAria')}
                        onClick={(e) => e.stopPropagation()}
                        className="absolute top-3 right-3 flex h-7 w-7 shrink-0 items-center justify-center rounded-full text-neutral-400 transition hover:bg-neutral-100 hover:text-neutral-700"
                    >
                        <EllipsisHorizontalIcon className="size-4" />
                    </button>
                </DropdownMenuTrigger>
                <DropdownMenuContent
                    align="end"
                    side="bottom"
                    className="w-44"
                    onClick={(e) => e.stopPropagation()}
                >
                    <DropdownMenuItem
                        onSelect={(e) => {
                            e.preventDefault();
                            onEdit?.();
                        }}
                    >
                        <PencilIcon className="size-4 text-neutral-500" />
                        {t('edit')}
                    </DropdownMenuItem>
                    <DropdownMenuItem
                        onSelect={(e) => {
                            e.preventDefault();
                            copyContent();
                        }}
                    >
                        <DocumentDuplicateIcon className="size-4 text-neutral-500" />
                        {t('copyContent')}
                    </DropdownMenuItem>
                    <DropdownMenuSeparator />
                    <DropdownMenuItem
                        className="text-destructive hover:bg-red-500/10"
                        onSelect={(e) => {
                            e.preventDefault();
                            onDelete?.();
                        }}
                    >
                        <TrashIcon className="size-4 text-destructive" />
                        {t('delete')}
                    </DropdownMenuItem>
                </DropdownMenuContent>
            </DropdownMenu>
        </div>
    );
}