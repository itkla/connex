'use client';

import { EllipsisHorizontalIcon, EyeIcon, PencilIcon, EnvelopeIcon, PhoneIcon, TrashIcon } from '@heroicons/react/24/outline';
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
import { toast } from 'sonner';
import { toastError, toastSuccess } from '@/app/lib/toast';

import QuickEditSheet, { type ContactDraft } from '@/app/components/records/contacts/QuickEditSheet';
import ChangeCompanyDialog from '@/app/components/records/contacts/ChangeCompanyDialog';
// import RemoveFromCompanyDialog from '@/app/components/records/contacts/RemoveFromCompanyDialog';
import { updateContact } from '@/app/lib/api';
import { uploadContactPicture } from '@/app/lib/utils';
import type { Company, Contact, UpdateContactPayload } from '@/app/lib/types';
import { BuildingOffice2Icon, NoSymbolIcon } from '@heroicons/react/24/outline';
import type { Tag } from '@/app/lib/types';
import { getCompanies } from '@/app/lib/api';

function initialsOf(name: string): string {
    const parts = name.trim().split(/\s+/).filter(Boolean);
    if (parts.length === 0) return '?';
    return (parts[0][0] + (parts.length > 1 ? parts[parts.length - 1][0] : '')).toUpperCase();
}

const AVATAR_TINTS = [
    'bg-rose-100 text-rose-700',
    'bg-orange-100 text-orange-700',
    'bg-amber-100 text-amber-700',
    'bg-emerald-100 text-emerald-700',
    'bg-teal-100 text-teal-700',
    'bg-sky-100 text-sky-700',
    'bg-indigo-100 text-indigo-700',
    'bg-violet-100 text-violet-700',
    'bg-fuchsia-100 text-fuchsia-700',
];

function tintFor(seed: string): string {
    let h = 0;
    // use the contact name as seed so that the tint is consistent for the same name
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
    tags?: Tag[];
    onQuickEdit?: () => void;
    onDelete?: () => void;
}

export default function ContactCard({
    id,
    name = 'Tahm Kench',
    title = 'CTO',
    company = '',
    companyId,
    email,
    phone,
    imageUrl,
    tags = [],
    onQuickEdit,
    onDelete,
}: ContactCardProps) {
    const router = useRouter();
    const t = useTranslations('ContactsCard');
    const [editSheetOpen, setEditSheetOpen] = useState(false);
    const [draft, setDraft] = useState<ContactDraft>({ name: '', email: '', phone: '', title: '' });
    const [imageFile, setImageFile] = useState<File | null>(null);
    const [isSaving, setIsSaving] = useState(false);
    const [companies, setCompanies] = useState<Company[]>([]);
    const [changeCompanyOpen, setChangeCompanyOpen] = useState(false);
    const [removeFromCompanyOpen, setRemoveFromCompanyOpen] = useState(false);

    function openContactPage() {
        router.push(`/records/contacts/${id}`);
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

    // open the change company dialog so the user can select a new company for the contact
    function openChangeCompanyDialog() {
        getCompanies({}).then(setCompanies).catch(() => setCompanies([]));
        setChangeCompanyOpen(true);
    }

    // insert function like from /records/contacts to change the company of the contact


    async function saveInternalEdits() {
        const trimmedName = draft.name.trim();
        if (!trimmedName) {
            toast.error(t('toastNameRequired'));
            return;
        }
        setIsSaving(true);
        try {
            const payload: UpdateContactPayload = {
                name: trimmedName,
                email: draft.email.trim() || undefined,
                phone: draft.phone.trim() || undefined,
                title: draft.title.trim() || undefined,
                companyId: companyId ?? null,
                imageUrl: imageUrl || undefined,
            };
            if (imageFile) {
                payload.imageUrl = await uploadContactPicture(id, imageFile);
            }
            await updateContact(id, payload);
            toastSuccess(t('toastContactUpdated'));
            setEditSheetOpen(false);
            setImageFile(null);
            router.refresh();
        } catch (err) {
            toastError(err instanceof Error ? err.message : t('toastFailedSave'));
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
            className="group relative flex cursor-pointer flex-col overflow-hidden rounded-2xl bg-white ring-1 ring-black/5 transition duration-200 hover:-translate-y-1 hover:shadow-[0_20px_45px_-18px_rgb(0_0_0/0.32)] hover:ring-black/10"
            onClick={openContactPage}
        >
            <div className="relative aspect-square w-full overflow-hidden">
                {imageUrl ? (
                    <img
                        src={imageUrl}
                        alt={name}
                        className="h-full w-full object-cover transition-transform duration-500 ease-out group-hover:scale-[1.04]"
                    />
                ) : (
                    <div className={cn('flex h-full w-full items-center justify-center', tintFor(name))}>
                        <span className="text-5xl font-semibold tracking-tight select-none">{initialsOf(name)}</span>
                    </div>
                )}

                <DropdownMenu>
                    <DropdownMenuTrigger asChild>
                        <button
                            type="button"
                            aria-label={t('actionsAria')}
                            onClick={(e) => e.stopPropagation()}
                            className="absolute top-2 right-2 flex size-8 items-center justify-center rounded-full bg-white/85 text-neutral-700 opacity-0 ring-1 ring-black/5 backdrop-blur transition group-hover:opacity-100 hover:bg-white focus:opacity-100 focus-visible:opacity-100 data-[state=open]:opacity-100"
                        >
                            <EllipsisHorizontalIcon className="size-5" />
                        </button>
                    </DropdownMenuTrigger>
                    <DropdownMenuContent align="end" side="bottom" className="w-48" onClick={(e) => e.stopPropagation()}>
                        <DropdownMenuItem onSelect={() => router.push(`/records/contacts/${id}`)}>
                            <EyeIcon className="size-4 text-neutral-500" />
                            {t('view')}
                        </DropdownMenuItem>
                        <DropdownMenuItem
                            onSelect={(e) => {
                                e.preventDefault();
                                if (onQuickEdit) onQuickEdit();
                                else openInternalQuickEdit();
                            }}
                        >
                            <PencilIcon className="size-4 text-neutral-500" />
                            {t('quickEdit')}
                        </DropdownMenuItem>
                        {email && (
                            <DropdownMenuItem onSelect={() =>
                                copyToClipboard(email, 'Email') ? toast.success(t('toastEmailCopied')) : toast.error(t('toastFailedCopyEmail'))
                            }>
                                <EnvelopeIcon className="size-4 text-neutral-500" />
                                {t('copyEmail')}
                            </DropdownMenuItem>
                        )}
                        {phone && (
                            <DropdownMenuItem onSelect={() =>
                                copyToClipboard(phone, 'Phone') ? toast.success(t('toastPhoneCopied')) : toast.error(t('toastFailedCopyPhone'))
                            }>
                                <PhoneIcon className="size-4 text-neutral-500" />
                                {t('copyPhone')}
                            </DropdownMenuItem>
                        )}
                        <DropdownMenuSeparator />
                        <DropdownMenuItem onClick={() => openChangeCompanyDialog()}>
                            <BuildingOffice2Icon className="size-4 text-neutral-500" />
                            {t('changeCompany')}
                        </DropdownMenuItem>
                        <DropdownMenuItem onClick={() => setRemoveFromCompanyOpen(true)}>
                            <NoSymbolIcon className="size-4 text-neutral-500" />
                            {t('removeFromCompany')}
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

            <div className="flex flex-col gap-2.5 p-4">
                <div className="min-w-0">
                    <h3 className="truncate font-semibold leading-tight text-neutral-900">{name}</h3>
                    {title && <p className="mt-0.5 truncate text-xs text-neutral-500">{title}</p>}
                </div>

                {company && (
                    <span className="inline-flex max-w-full items-center gap-1.5 self-start rounded-full bg-neutral-100 px-2.5 py-1 text-xs font-medium text-neutral-600 ring-1 ring-inset ring-black/5">
                        <BuildingOffice2Icon className="size-3.5 shrink-0 text-neutral-400" />
                        <span className="truncate">{company}</span>
                    </span>
                )}

                {/* // TODO: fix this bug (not showing) */}
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
            companies={companies}
            onSuccess={() => {
                toastSuccess(t('toastCompanyChanged'));
                router.refresh();
            }}
        />
        </>
    );
}