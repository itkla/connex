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
import { copyToClipboard } from '@/app/lib/utils';
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
            className="relative w-64 max-w-full rounded-2xl bg-gradient-to-br from-brand-light via-brand to-brand-dark hover:shadow-lg duration-300 hover:scale-105 cursor-pointer transition"
            onClick={openContactPage}
        >
            <div className="aspect-square w-full overflow-hidden rounded-2xl bg-neutral-200 shadow-[0_10px_25px_-5px_rgba(0,0,0,0.35)] ring-1 ring-black/5">

                <div className="absolute top-3 right-3">
                    {tags.map((tag) => (
                        <span key={tag.id} className="gap-1 text-xs w-1 h-1 tracking-wide opacity-80 bg-neutral-500/20 text-white rounded-full px-2 py-1" style={{ backgroundColor: tag.color }}>
                            {tag.name}
                        </span>
                    ))}
                </div>
                {imageUrl ? (
                    <img
                        src={imageUrl}
                        alt={name}
                        className="h-full w-full object-cover"
                    />
                ) : (
                    <div
                        className="h-full w-full"
                        // TODO: find something better than this
                        style={{
                            background:
                                'linear-gradient(180deg, #cdd5dc 0%, #b6bfc6 60%, #9aa4ad 100%)',
                        }}
                        aria-hidden="true"
                    />
                )}
            </div>

            <div className="px-3 pt-3 pb-3 pr-11 text-white">
                <div className="min-w-0">
                    <h3 className="text-base font-semibold leading-tight">
                        {name}
                    </h3>
                    <p className="mt-0.5 truncate text-xs font-medium uppercase tracking-wide opacity-80">
                        {title}
                    </p>
                    <p className="mt-1 truncate text-sm opacity-90">
                        {company}
                    </p>
                </div>
            </div>

            <DropdownMenu>
                <DropdownMenuTrigger asChild>
                    <button
                        type="button"
                        aria-label={t('actionsAria')}
                        onClick={(e) => e.stopPropagation()}
                        className="absolute bottom-3 right-3 flex h-6 w-6 shrink-0 items-center justify-center rounded-full bg-white/20 text-white transition hover:bg-white/30"
                    >
                        <EllipsisHorizontalIcon className="size-4" />
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
                    <DropdownMenuItem onClick={() => {
                        openChangeCompanyDialog();
                    }}>
                        <BuildingOffice2Icon className="size-4 text-neutral-500" />
                        {t('changeCompany')}
                    </DropdownMenuItem>
                    <DropdownMenuItem onClick={() => {
                        setRemoveFromCompanyOpen(true);
                    }}>
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