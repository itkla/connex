'use client';

import { ArchiveBoxArrowDownIcon, ArchiveBoxIcon, EllipsisHorizontalIcon, EyeIcon, PencilIcon, EnvelopeIcon, PhoneIcon } from '@heroicons/react/24/outline';
import { useRouter } from 'next/navigation';
import { useTranslations } from 'next-intl';
import { useState } from 'react';
import {
    DropdownMenu,
    DropdownMenuTrigger,
    DropdownMenuContent,
    DropdownMenuItem,
    DropdownMenuSeparator,
} from '@/components/ui/dropdown-menu';
import { copyToClipboard, readableTextColor } from '@/app/lib/utils';
import { cn } from '@/lib/utils';
import { toastError, toastSuccess } from '@/app/lib/toast';

import QuickEditSheet, { type ContactDraft } from '@/app/components/records/contacts/QuickEditSheet';
import ProtectedMediaImage from '@/app/components/ProtectedMediaImage';
import ChangeCompanyDialog from '@/app/components/records/contacts/ChangeCompanyDialog';
import { updateContact, uploadContactPicture } from '@/app/lib/api';
import type { Contact, UpdateContactPayload } from '@/app/lib/types';
import type { RecordRemoveIntent } from '@/app/components/records/types';
import { BuildingOffice2Icon, UserCircleIcon } from '@heroicons/react/24/outline';
import type { Tag } from '@/app/lib/types';
import {
    recordDetailNavigationPath,
    type RecordReturnSelectionSnapshot,
} from '@/app/lib/recordReturnPath';
import { useApiErrorToast } from '@/app/hooks/useApiErrorToast';

function initialsOf(name: string): string {
    const parts = name.trim().split(/\s+/).filter(Boolean);
    if (parts.length === 0) return '?';
    return (parts[0][0] + (parts.length > 1 ? parts[parts.length - 1][0] : '')).toUpperCase();
}

const AVATAR_TINTS = [
    'bg-rose-100 text-rose-700 dark:bg-rose-950/40 dark:text-rose-300',
    'bg-orange-100 text-orange-700 dark:bg-orange-950/40 dark:text-orange-300',
    'bg-amber-100 text-amber-700 dark:bg-amber-950/40 dark:text-amber-300',
    'bg-emerald-100 text-emerald-700 dark:bg-emerald-950/40 dark:text-emerald-300',
    'bg-teal-100 text-teal-700 dark:bg-teal-950/40 dark:text-teal-300',
    'bg-sky-100 text-sky-700 dark:bg-sky-950/40 dark:text-sky-300',
    'bg-indigo-100 text-indigo-700 dark:bg-indigo-950/40 dark:text-indigo-300',
    'bg-violet-100 text-violet-700 dark:bg-violet-950/40 dark:text-violet-300',
    'bg-fuchsia-100 text-fuchsia-700 dark:bg-fuchsia-950/40 dark:text-fuchsia-300',
];

function tintFor(seed: string): string {
    let h = 0;
    for (let i = 0; i < seed.length; i++) h = (h * 31 + seed.charCodeAt(i)) >>> 0;
    return AVATAR_TINTS[h % AVATAR_TINTS.length];
}

interface ContactCardProps {
    id: number;
    name?: string;
    title?: string;
    company?: string;
    companyId?: number | null;
    email?: string;
    phone?: string;
    imageUrl?: string;
    ownerName?: string;
    tags?: Tag[];
    onQuickEdit?: () => void;
    onDelete?: () => void;
    readOnly?: boolean;
    /** What `onDelete` really does; contacts are archived rather than deleted (#854). */
    removeIntent?: RecordRemoveIntent;
    returnSelection?: RecordReturnSelectionSnapshot;
}

export default function ContactCard({
    id,
    name = 'Tahm Kench',
    title = 'CTO',
    company = '',
    ownerName,
    companyId,
    email,
    phone,
    imageUrl,
    tags = [],
    onQuickEdit,
    onDelete,
    readOnly = false,
    removeIntent = 'archive',
    returnSelection,
}: ContactCardProps) {
    const router = useRouter();
    const t = useTranslations('ContactsCard');
    const reportApiError = useApiErrorToast('ContactsCard');
    const [editSheetOpen, setEditSheetOpen] = useState(false);
    const [draft, setDraft] = useState<ContactDraft>({ name: '', email: '', phone: '', title: '' });
    const [imageFile, setImageFile] = useState<File | null>(null);
    const [isSaving, setIsSaving] = useState(false);
    const [changeCompanyOpen, setChangeCompanyOpen] = useState(false);

    function openContactPage() {
        router.push(recordDetailNavigationPath('contacts', id, returnSelection));
    }

    function openInternalQuickEdit() {
        setDraft({
            name: name ?? '',
            email: email ?? '',
            phone: phone ?? '',
            title: title ?? '',
        });
        setImageFile(null);
        setEditSheetOpen(true);
    }

    function openChangeCompanyDialog() {
        setChangeCompanyOpen(true);
    }

    async function saveInternalEdits() {
        const trimmedName = draft.name.trim();
        if (!trimmedName) {
            toastError(t('toastNameRequired'));
            return;
        }
        setIsSaving(true);
        let detailsSaved = false;
        try {
            const payload: UpdateContactPayload = {
                name: trimmedName,
                email: draft.email.trim() || undefined,
                phone: draft.phone.trim() || undefined,
                title: draft.title.trim() || undefined,
                companyId: companyId ?? null,
            };
            await updateContact(id, payload);
            detailsSaved = true;
            if (imageFile) await uploadContactPicture(id, imageFile);
            toastSuccess(t('toastContactUpdated'));
            setEditSheetOpen(false);
            setImageFile(null);
            router.refresh();
        } catch (err) {
            if (detailsSaved && imageFile) toastError(t('toastPartiallySaved'));
            else reportApiError(err, 'toastFailedSave');
            if (detailsSaved) router.refresh();
        } finally {
            setIsSaving(false);
        }
    }

    const syntheticContact: Contact = {
        id,
        name: name ?? '',
        email: email ?? '',
        phone: phone ?? '',
        title: title ?? '',
        imageUrl: imageUrl ?? '',
        createdAt: '',
        updatedAt: '',
    };



    return (
        <>
        <div
            className={cn(
                'group relative flex flex-col overflow-hidden rounded-2xl border border-border bg-card transition duration-200',
                !readOnly && 'cursor-pointer hover:-translate-y-1 hover:shadow-lg dark:hover:shadow-[0_20px_45px_-18px_rgb(0_0_0/0.6)]',
            )}
            onClick={readOnly ? undefined : openContactPage}
        >
            <div className="relative aspect-square w-full overflow-hidden">
                <ProtectedMediaImage
                    src={imageUrl}
                    alt={name}
                    className="h-full w-full object-cover transition-transform duration-500 ease-out group-hover:scale-[1.04]"
                    fallback={(
                        <span className={cn('flex h-full w-full items-center justify-center', tintFor(name))}>
                            <span className="text-5xl font-semibold tracking-tight select-none">{initialsOf(name)}</span>
                        </span>
                    )}
                />

                <DropdownMenu>
                    <DropdownMenuTrigger asChild>
                        <button
                            type="button"
                            aria-label={t('actionsAria')}
                            onClick={(e) => e.stopPropagation()}
                            className="absolute top-2 right-2 flex size-8 items-center justify-center rounded-full bg-card/85 text-foreground opacity-100 ring-1 ring-border backdrop-blur transition hover:bg-card sm:opacity-0 sm:group-hover:opacity-100 focus:opacity-100 focus-visible:opacity-100 data-[state=open]:opacity-100"
                        >
                            <EllipsisHorizontalIcon className="size-5" />
                        </button>
                    </DropdownMenuTrigger>
                    <DropdownMenuContent align="end" side="bottom" className="w-48" onClick={(e) => e.stopPropagation()}>
                        {!readOnly && (
                            <>
                                <DropdownMenuItem onSelect={openContactPage}>
                                    <EyeIcon className="size-4 text-muted-foreground" />
                                    {t('view')}
                                </DropdownMenuItem>
                                <DropdownMenuItem
                                    onSelect={(event) => {
                                        event.preventDefault();
                                        if (onQuickEdit) onQuickEdit();
                                        else openInternalQuickEdit();
                                    }}
                                >
                                    <PencilIcon className="size-4 text-muted-foreground" />
                                    {t('quickEdit')}
                                </DropdownMenuItem>
                                {email && (
                                    <DropdownMenuItem onSelect={() => { window.location.href = `mailto:${email}`; }}>
                                        <EnvelopeIcon className="size-4 text-muted-foreground" />
                                        {t('sendEmail')}
                                    </DropdownMenuItem>
                                )}
                                {phone && (
                                    <DropdownMenuItem onSelect={() =>
                                        copyToClipboard(phone, 'Phone')
                                            ? toastSuccess(t('toastPhoneCopied'))
                                            : toastError(t('toastFailedCopyPhone'))
                                    }>
                                        <PhoneIcon className="size-4 text-muted-foreground" />
                                        {t('copyPhone')}
                                    </DropdownMenuItem>
                                )}
                                <DropdownMenuSeparator />
                                <DropdownMenuItem onClick={() => openChangeCompanyDialog()}>
                                    <BuildingOffice2Icon className="size-4 text-muted-foreground" />
                                    {t('changeCompany')}
                                </DropdownMenuItem>
                                <DropdownMenuSeparator />
                            </>
                        )}
                        {onDelete && (
                            <DropdownMenuItem
                                onSelect={(e) => {
                                    e.preventDefault();
                                    onDelete();
                                }}
                            >
                                {removeIntent === 'restore'
                                    ? <ArchiveBoxIcon className="size-4 text-muted-foreground" />
                                    : <ArchiveBoxArrowDownIcon className="size-4 text-muted-foreground" />}
                                {t(removeIntent === 'restore' ? 'restore' : 'archive')}
                            </DropdownMenuItem>
                        )}
                    </DropdownMenuContent>
                </DropdownMenu>
            </div>

            <div className="flex flex-col gap-2.5 p-4">
                <div className="min-w-0">
                    <h3 className="truncate font-semibold leading-tight text-foreground">{name}</h3>
                    {title && <p className="mt-0.5 truncate text-xs text-muted-foreground">{title}</p>}
                </div>

                {company && (
                    <span className="inline-flex max-w-full items-center gap-1.5 self-start rounded-full bg-muted px-2.5 py-1 text-xs font-medium text-muted-foreground ring-1 ring-inset ring-border">
                        <BuildingOffice2Icon className="size-3.5 shrink-0 text-muted-foreground" />
                        <span className="truncate">{company}</span>
                    </span>
                )}

                {ownerName && (
                    <span className="inline-flex max-w-full items-center gap-1.5 self-start text-xs text-muted-foreground">
                        <UserCircleIcon className="size-3.5 shrink-0" />
                        <span className="truncate">{t('ownerLabel', { name: ownerName })}</span>
                    </span>
                )}

                {tags.length > 0 && (
                    <div className="flex flex-wrap gap-1">
                        {tags.map((tag) => (
                            <span
                                key={tag.id}
                                className="max-w-full truncate rounded-full px-2 py-0.5 text-[10px] font-medium"
                                style={{ backgroundColor: tag.color, color: readableTextColor(tag.color) }}
                            >
                                {tag.name}
                            </span>
                        ))}
                    </div>
                )}
            </div>
        </div>
        {!onQuickEdit && (
            <QuickEditSheet
                editSheetOpen={editSheetOpen}
                setEditSheetOpen={setEditSheetOpen}
                selectedIds={new Set([id])}
                selectedContacts={[syntheticContact]}
                drafts={{ [id]: draft }}
                updateDraft={(_id, patch) => setDraft((d) => ({ ...d, ...patch }))}
                imageFiles={{ [id]: imageFile }}
                updateImageFile={(_id, file) => setImageFile(file)}
                isSaving={isSaving}
                saveEdits={saveInternalEdits}
            />
        )}

        <ChangeCompanyDialog
            open={changeCompanyOpen}
            onOpenChange={setChangeCompanyOpen}
            contacts={[syntheticContact]}
            onSuccess={() => {
                toastSuccess(t('toastCompanyChanged'));
                router.refresh();
            }}
        />
        </>
    );
}
