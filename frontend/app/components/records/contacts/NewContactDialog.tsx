'use client';

import { Dialog, DialogContent, DialogHeader, DialogTitle, DialogDescription, DialogFooter, DialogClose } from '@/components/ui/dialog';
import { Button } from '@/components/ui/button';
import { Loader2Icon } from 'lucide-react';
import { Combobox, ComboboxItem, ComboboxList, ComboboxContent, ComboboxEmpty, ComboboxInput } from '@/components/ui/combobox';
import { Label } from '@/components/ui/label';
import { type Company, type CreateContactPayload } from '@/app/lib/types';
import { ChangeEvent, Dispatch, SetStateAction, useEffect, useState, type WheelEvent } from 'react';
import { CameraIcon } from '@heroicons/react/24/outline';
import { useTranslations } from 'next-intl';
import { uploadContactPicture } from '@/app/lib/utils';
import { useFieldErrors } from '@/app/hooks/useFieldErrors';
const inputClass = 'w-full rounded-lg bg-neutral-100 px-3 py-2 text-sm text-black placeholder-neutral-500 outline-none ring-1 ring-black/5 transition focus:ring-2 focus:ring-brand';

type Props = {
    newContactDialogOpen: boolean;
    setNewContactDialogOpen: (open: boolean) => void;
    newContactPayload: CreateContactPayload;
    setNewContactPayload: Dispatch<SetStateAction<CreateContactPayload>>;
    imageFile: File | null;
    setImageFile: Dispatch<SetStateAction<File | null>>;
    companies: Company[];
    selectedCompany: Company | null;
    isCreating: boolean;
    createNewContact: () => void | Promise<void>;
};

export default function NewContactDialog({
    newContactDialogOpen,
    setNewContactDialogOpen,
    newContactPayload,
    setNewContactPayload,
    imageFile,
    setImageFile,
    companies,
    selectedCompany,
    isCreating,
    createNewContact,
}: Props) {
    const t = useTranslations('ContactsNewContactDialog');
    const [imagePreview, setImagePreview] = useState<string | null>(null);
    const { fieldErrors, reset: resetFieldErrors, clearError, captureFieldErrors } = useFieldErrors();

    const handleCreate = async () => {
        resetFieldErrors();
        try {
            await createNewContact();
        } catch (err) {
            captureFieldErrors(err);
        }
    };

    const handleListWheel = (e: WheelEvent<HTMLDivElement>) => {
        const lineHeightPx = 16;
        const delta = e.deltaMode === 1 ? e.deltaY * lineHeightPx : e.deltaY;
        e.currentTarget.scrollTop += delta;
    };

    useEffect(() => {
        if (!newContactDialogOpen && imagePreview) {
            URL.revokeObjectURL(imagePreview);
            setImagePreview(null);
        }
        if (!newContactDialogOpen) resetFieldErrors();
    }, [newContactDialogOpen, imagePreview, resetFieldErrors]);

    useEffect(() => {
        return () => {
            if (imagePreview) URL.revokeObjectURL(imagePreview);
        };
    }, [imagePreview]);

    const handleImageChange = (e: ChangeEvent<HTMLInputElement>) => {
        const file = e.target.files?.[0];
        if (!file) return;
        if (imagePreview) URL.revokeObjectURL(imagePreview);
        setImagePreview(URL.createObjectURL(file));
        setImageFile(file);
    };

    return (
        <Dialog open={newContactDialogOpen} onOpenChange={setNewContactDialogOpen}>
            <DialogContent>
                <DialogHeader>
                    <DialogTitle>{t('dialogTitle')}</DialogTitle>
                    <DialogDescription>{t('description')}</DialogDescription>
                </DialogHeader>

                <div className="flex justify-center">
                    <label
                        htmlFor="imageUrl"
                        className="group relative flex h-20 w-20 cursor-pointer items-center justify-center overflow-hidden rounded-full bg-neutral-100 ring-1 ring-black/5 transition hover:ring-2 hover:ring-brand"
                    >
                        {imagePreview ? (
                            <img src={imagePreview} alt="" className="h-full w-full object-cover" />
                        ) : (
                            <div className="h-full w-full" style={{ background: 'linear-gradient(180deg, #cdd5dc 0%, #b6bfc6 60%, #9aa4ad 100%)' }} />
                        )}
                        {/* <ContactAvatar contact={newContactPayload} type="medium" /> */}
                        <div className="absolute inset-0 flex items-center justify-center bg-black/40 opacity-0 transition group-hover:opacity-100">
                            <CameraIcon className="size-6 text-white" />
                        </div>
                        <input
                            id="imageUrl"
                            type="file"
                            accept="image/*"
                            onChange={handleImageChange}
                            className="sr-only"
                        />
                    </label>
                </div>

                <div className="grid gap-4">
                    <div className="grid gap-1.5">
                        <Label htmlFor="name">{t('name')}</Label>
                        <input
                            id="name"
                            type="text"
                            value={newContactPayload.name}
                            onChange={(e) => {
                                setNewContactPayload((prev) => ({ ...prev, name: e.target.value }));
                                clearError('name');
                            }}
                            className={`${inputClass} ${fieldErrors.name ? 'ring-2 ring-red-400 focus:ring-red-500' : ''}`}
                            placeholder={t('namePlaceholder')}
                            aria-invalid={Boolean(fieldErrors.name)}
                            autoFocus
                            required
                        />
                        {fieldErrors.name && (
                            <p className="px-1 text-sm text-red-600">{fieldErrors.name}</p>
                        )}
                    </div>

                    <div className="grid gap-1.5">
                        <Label htmlFor="email">{t('email')}</Label>
                        <input
                            id="email"
                            type="email"
                            value={newContactPayload.email}
                            onChange={(e) => {
                                setNewContactPayload((prev) => ({ ...prev, email: e.target.value }));
                                clearError('email');
                            }}
                            className={`${inputClass} ${fieldErrors.email ? 'ring-2 ring-red-400 focus:ring-red-500' : ''}`}
                            placeholder={t('emailPlaceholder')}
                            aria-invalid={Boolean(fieldErrors.email)}
                        />
                        {fieldErrors.email && (
                            <p className="px-1 text-sm text-red-600">{fieldErrors.email}</p>
                        )}
                    </div>

                    <div className="grid grid-cols-2 gap-3">
                        <div className="grid gap-1.5">
                            <Label htmlFor="phone">{t('phone')}</Label>
                            <input
                                id="phone"
                                type="tel"
                                value={newContactPayload.phone}
                                onChange={(e) => {
                                    setNewContactPayload((prev) => ({ ...prev, phone: e.target.value }));
                                    clearError('phone');
                                }}
                                className={`${inputClass} ${fieldErrors.phone ? 'ring-2 ring-red-400 focus:ring-red-500' : ''}`}
                                placeholder={t('phonePlaceholder')}
                                aria-invalid={Boolean(fieldErrors.phone)}
                            />
                            {fieldErrors.phone && (
                                <p className="px-1 text-sm text-red-600">{fieldErrors.phone}</p>
                            )}
                        </div>
                        <div className="grid gap-1.5">
                            <Label htmlFor="title">{t('title')}</Label>
                            <input
                                id="title"
                                type="text"
                                value={newContactPayload.title}
                                onChange={(e) => setNewContactPayload((prev) => ({ ...prev, title: e.target.value }))}
                                className={inputClass}
                                placeholder={t('titlePlaceholder')}
                            />
                        </div>
                    </div>

                    <div className="grid gap-1.5">
                        <Label htmlFor="company">{t('company')}</Label>
                        <Combobox
                            items={companies}
                            itemToStringLabel={(c: Company) => c.name}
                            value={selectedCompany}
                            // disabled={isCreating}
                            onValueChange={(c) =>
                                setNewContactPayload((prev) => ({
                                    ...prev,
                                    companyId: (c as Company | null)?.id,
                                }))
                            }
                        >
                            <ComboboxInput id="company" placeholder={t('selectCompanyPlaceholder')} className="ring-1 ring-black/5"/>
                            <ComboboxContent className="pointer-events-auto">
                                <ComboboxList onWheel={handleListWheel}>
                                    <ComboboxEmpty>{t('noCompaniesFound')}</ComboboxEmpty>
                                    {companies.map((company) => (
                                        <ComboboxItem key={company.id} value={company}>
                                            {company.name}
                                        </ComboboxItem>
                                    ))}
                                </ComboboxList>
                            </ComboboxContent>
                        </Combobox>
                    </div>
                </div>

                <DialogFooter>
                    <DialogClose asChild>
                        <Button variant="outline" disabled={isCreating}>{t('cancel')}</Button>
                    </DialogClose>
                    <Button
                        onClick={handleCreate}
                        disabled={isCreating}
                        className="bg-brand text-white hover:bg-brand-dark"
                    >
                        {isCreating ? <Loader2Icon className="size-4 animate-spin" /> : t('create')}
                    </Button>
                </DialogFooter>
            </DialogContent>
        </Dialog>
    );
}
