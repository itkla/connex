'use client';

import Link from 'next/link';
import { useEffect, useRef } from 'react';
import { useSearchParams } from 'next/navigation';
import { useLocale, useTranslations } from 'next-intl';
import { motion, useReducedMotion } from 'motion/react';
import { toastError, toastSuccess } from '@/app/lib/toast';
import {
    EllipsisHorizontalIcon,
    PencilIcon,
    TrashIcon,
    DocumentDuplicateIcon,
    UserIcon,
    BriefcaseIcon,
    LockClosedIcon,
} from '@heroicons/react/24/outline';

import {
    DropdownMenu,
    DropdownMenuTrigger,
    DropdownMenuContent,
    DropdownMenuItem,
    DropdownMenuSeparator,
} from '@/components/ui/dropdown-menu';
import { Avatar, AvatarFallback, AvatarImage } from '@/components/ui/avatar';
import { copyToClipboard, formatShortDate, formatDateTime } from '@/app/lib/utils';
import type { Contact, Deal, Note, User } from '@/app/lib/types';
import NoteContent from './NoteContent';

interface NoteCardProps {
    note: Note;
    person?: Contact;
    deal?: Deal;
    author?: User;
    onEdit?: () => void;
    onDelete?: () => void;
}

const EASE_OUT: [number, number, number, number] = [0.23, 1, 0.32, 1];

export default function NoteCard({ note, person, deal, author, onEdit, onDelete }: NoteCardProps) {
    const t = useTranslations('ActivityNotesCard');
    const locale = useLocale();
    const reduce = useReducedMotion() ?? false;
    const cardRef = useRef<HTMLDivElement>(null);
    const searchParams = useSearchParams();
    const isHighlighted = searchParams.get('note') === String(note.id);

    useEffect(() => {
        if (isHighlighted) {
            cardRef.current?.scrollIntoView({ behavior: reduce ? 'auto' : 'smooth', block: 'center' });
        }
    }, [isHighlighted, reduce]);
    const updated = note.updatedAt ?? note.createdAt;
    const authorName = author?.displayName || author?.username || t('unknownAuthor');
    const content = (note.content ?? '').trim();
    const breakAt = content.indexOf('\n');
    const heading = breakAt === -1 ? content : content.slice(0, breakAt).trim();
    const body = breakAt === -1 ? '' : content.slice(breakAt + 1).trim();
    const hasContext = Boolean(person || deal);

    const presence = reduce
        ? { initial: { opacity: 0 }, animate: { opacity: 1 }, exit: { opacity: 0 }, transition: { duration: 0.15 } }
        : {
              initial: { opacity: 0, scale: 0.96 },
              animate: { opacity: 1, scale: 1 },
              exit: { opacity: 0, scale: 0.96 },
              transition: { duration: 0.2, ease: EASE_OUT },
          };

    const copyContent = () => {
        if (copyToClipboard(note.content, 'Note')) {
            toastSuccess(t('toastContentCopied'));
        } else {
            toastError(t('toastFailedCopy'));
        }
    };

    return (
        <motion.div ref={cardRef} layout={!reduce} {...presence} className="min-w-0 scroll-mt-24">
            <motion.div
                whileHover={reduce ? undefined : { y: -2 }}
                whileTap={reduce ? undefined : { scale: 0.99 }}
                transition={{ duration: 0.2, ease: EASE_OUT }}
                onClick={() => onEdit?.()}
                className={`group relative flex aspect-square w-full min-w-0 cursor-pointer flex-col overflow-hidden rounded-2xl bg-card p-4 transition-shadow duration-200 hover:shadow-lg ${isHighlighted ? 'ring-2 ring-brand' : 'ring-1 ring-border'}`}
            >
                <DropdownMenu>
                    <DropdownMenuTrigger asChild>
                        <button
                            type="button"
                            aria-label={t('actionsAria')}
                            onClick={(e) => e.stopPropagation()}
                            className="absolute top-3 right-3 flex size-7 shrink-0 items-center justify-center rounded-full text-muted-foreground opacity-0 transition hover:bg-muted hover:text-foreground focus-visible:opacity-100 group-hover:opacity-100 pointer-coarse:opacity-100 aria-expanded:bg-muted aria-expanded:text-foreground aria-expanded:opacity-100"
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
                            <PencilIcon className="size-4 text-muted-foreground" />
                            {t('edit')}
                        </DropdownMenuItem>
                        <DropdownMenuItem
                            onSelect={(e) => {
                                e.preventDefault();
                                copyContent();
                            }}
                        >
                            <DocumentDuplicateIcon className="size-4 text-muted-foreground" />
                            {t('copyContent')}
                        </DropdownMenuItem>
                        <DropdownMenuSeparator />
                        <DropdownMenuItem
                            className="text-destructive hover:bg-destructive/10"
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

                <div className="min-h-0 flex-1 overflow-y-auto pr-7">
                    {body ? (
                        <>
                            <p className="text-[15px] font-semibold leading-snug break-words text-foreground">
                                <NoteContent content={heading} references={note.references} />
                            </p>
                            <p className="mt-1.5 text-sm leading-relaxed break-words whitespace-pre-wrap text-muted-foreground">
                                <NoteContent content={body} references={note.references} />
                            </p>
                        </>
                    ) : (
                        <p className="text-sm leading-relaxed break-words whitespace-pre-wrap text-foreground">
                            <NoteContent content={heading} references={note.references} />
                        </p>
                    )}
                </div>

                <div className="mt-3 shrink-0 space-y-2.5 border-t border-border pt-3">
                    <div className="flex min-w-0 flex-wrap items-center gap-1.5">
                        {person && (
                            <Link
                                href={`/records/contacts/${person.id}`}
                                onClick={(e) => e.stopPropagation()}
                                className="inline-flex max-w-[12rem] items-center gap-1 rounded-full bg-brand-light/40 px-2 py-0.5 text-xs font-medium text-brand-dark transition hover:bg-brand-light"
                                title={person.name}
                            >
                                <UserIcon className="size-3 shrink-0" />
                                <span className="truncate">{person.name}</span>
                            </Link>
                        )}
                        {deal && (
                            <Link
                                href={`/records/deals/${deal.id}`}
                                onClick={(e) => e.stopPropagation()}
                                className="inline-flex max-w-[12rem] items-center gap-1 rounded-full bg-muted px-2 py-0.5 text-xs font-medium text-foreground transition hover:bg-muted/70"
                                title={deal.name}
                            >
                                <BriefcaseIcon className="size-3 shrink-0" />
                                <span className="truncate">{deal.name}</span>
                            </Link>
                        )}
                        {!hasContext && (
                            <span className="inline-flex items-center gap-1 text-xs font-medium text-muted-foreground">
                                <LockClosedIcon className="size-3 shrink-0" />
                                {t('private')}
                            </span>
                        )}
                    </div>

                    <div className="flex items-center justify-between gap-2">
                        <div className="flex min-w-0 items-center gap-2">
                            <Avatar size="sm" className="ring-1 ring-border">
                                {author?.profilePictureUrl ? (
                                    <AvatarImage src={author.profilePictureUrl} alt={authorName} />
                                ) : (
                                    <AvatarFallback>
                                        <UserIcon className="size-3 text-muted-foreground" />
                                    </AvatarFallback>
                                )}
                            </Avatar>
                            <span className="truncate text-xs font-medium text-muted-foreground">{authorName}</span>
                        </div>
                        <span
                            className="shrink-0 text-xs text-muted-foreground tabular-nums"
                            title={formatDateTime(updated, locale)}
                        >
                            {formatShortDate(updated, locale)}
                        </span>
                    </div>
                </div>
            </motion.div>
        </motion.div>
    );
}