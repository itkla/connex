'use client';

import { ResponsiveDialog, ResponsiveDialogContent, ResponsiveDialogHeader, ResponsiveDialogTitle, ResponsiveDialogDescription, ResponsiveDialogFooter, ResponsiveDialogClose } from '@/components/ui/responsive-dialog';
import { Button } from '@/components/ui/button';
import { Loader2Icon } from 'lucide-react';
import { Combobox, ComboboxItem, ComboboxList, ComboboxContent, ComboboxEmpty, ComboboxInput } from '@/components/ui/combobox';
import { InputGroupAddon } from '@/components/ui/input-group';
import { Label } from '@/components/ui/label';
import { cn } from '@/lib/utils';
import { type Company, type CreateContactPayload } from '@/app/lib/types';
import { ChangeEvent, Dispatch, FormEvent, SetStateAction, useEffect, useState, type WheelEvent } from 'react';
import {
    CameraIcon,
    UserIcon,
    EnvelopeIcon,
    PhoneIcon,
    BriefcaseIcon,
    BuildingOffice2Icon,
} from '@heroicons/react/24/outline';
import { useTranslations } from 'next-intl';
import { initials, uploadContactPicture } from '@/app/lib/utils';
import { isFieldError } from '@/app/lib/api';
import { useFieldErrors } from '@/app/hooks/useFieldErrors';
import { DialogStatusCover, resolveDialogStatus, fieldInputClass, fieldErrorClass, fieldLeadIconClass } from '@/components/ui/dialog-status-cover';

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
    isSuccess?: boolean;
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
    isSuccess = false,
    createNewContact,
}: Props) {
    const t = useTranslations('ContactsNewContactDialog');
    const [imagePreview, setImagePreview] = useState<string | null>(null);
    const [prevOpen, setPrevOpen] = useState(newContactDialogOpen);
    const { fieldErrors, reset: resetFieldErrors, clearError, captureFieldErrors } = useFieldErrors();

    const handleCreate = async () => {
        resetFieldErrors();
        try {
            await createNewContact();
        } catch (err) {
            captureFieldErrors(err);
            if (isFieldError(err)) {
                const k = Object.keys(err.fieldErrors)[0];
                if (k) requestAnimationFrame(() => document.getElementById(k)?.focus());
            }
        }
    };

    const handleListWheel = (e: WheelEvent<HTMLDivElement>) => {
        const lineHeightPx = 16;
        const delta = e.deltaMode === 1 ? e.deltaY * lineHeightPx : e.deltaY;
        e.currentTarget.scrollTop += delta;
    };

    if (newContactDialogOpen !== prevOpen) {
        setPrevOpen(newContactDialogOpen);
        if (!newContactDialogOpen) {
            setImagePreview(null);
            resetFieldErrors();
        }
    }

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

    const handleOpenChange = (next: boolean) => {
        if (!next && isCreating) return;
        setNewContactDialogOpen(next);
    };

    const hasErrors = Object.keys(fieldErrors).length > 0;
    const status = resolveDialogStatus({ isLoading: isCreating, hasErrors, isSuccess });
    const contactInitial = initials(newContactPayload.name || '');

    return (
        <ResponsiveDialog open={newContactDialogOpen} onOpenChange={handleOpenChange}>
            <ResponsiveDialogContent className="gap-0 overflow-hidden p-0 sm:max-w-lg">
                <DialogStatusCover status={status} />

                <div className="px-6 pb-6">
                    <div className="ncd-pop relative -mt-12 mb-4 w-fit">
                        <label
                            htmlFor="imageUrl"
                            className="group relative flex size-20 cursor-pointer items-center justify-center overflow-hidden rounded-full bg-muted shadow-lg ring-4 ring-popover transition hover:ring-brand"
                        >
                            {imagePreview ? (
                                <img src={imagePreview} alt="" className="size-full object-cover" />
                            ) : contactInitial ? (
                                <div className="flex size-full select-none items-center justify-center bg-brand-light text-2xl font-semibold text-brand-dark">
                                    {contactInitial}
                                </div>
                            ) : (
                                <div className="flex size-full items-center justify-center bg-brand-light">
                                    <UserIcon className="size-7 text-brand-dark/70 transition group-hover:text-brand-dark" />
                                </div>
                            )}

                            {(imagePreview || contactInitial) && (
                                <div className="absolute inset-0 flex items-center justify-center bg-black/45 opacity-0 transition group-hover:opacity-100">
                                    <CameraIcon className="size-5 text-white" />
                                </div>
                            )}

                            <input
                                id="imageUrl"
                                type="file"
                                accept="image/*"
                                onChange={handleImageChange}
                                className="sr-only"
                            />
                        </label>
                    </div>

                    <ResponsiveDialogHeader className="ncd-rise mb-5" style={{ animationDelay: '40ms' }}>
                        <ResponsiveDialogTitle className="text-xl font-semibold tracking-tight">{t('dialogTitle')}</ResponsiveDialogTitle>
                        <ResponsiveDialogDescription>{t('description')}</ResponsiveDialogDescription>
                    </ResponsiveDialogHeader>

                    <form
                        onSubmit={(e: FormEvent) => {
                            e.preventDefault();
                            if (isCreating) return;
                            handleCreate();
                        }}
                        className="grid gap-5"
                    >
                        <div className="ncd-rise grid gap-1.5" style={{ animationDelay: '90ms' }}>
                            <Label htmlFor="name">{t('name')}</Label>
                            <div className="group relative">
                                <UserIcon className={fieldLeadIconClass} />
                                <input
                                    id="name"
                                    type="text"
                                    value={newContactPayload.name}
                                    onChange={(e) => {
                                        setNewContactPayload((prev) => ({ ...prev, name: e.target.value }));
                                        clearError('name');
                                    }}
                                    className={cn(fieldInputClass, 'pl-9 pr-3', fieldErrors.name && fieldErrorClass)}
                                    placeholder={t('namePlaceholder')}
                                    aria-invalid={Boolean(fieldErrors.name)}
                                    aria-describedby={fieldErrors.name ? 'name-error' : undefined}
                                    autoFocus
                                    required
                                />
                            </div>
                            {fieldErrors.name && <p id="name-error" className="text-sm text-destructive">{fieldErrors.name}</p>}
                        </div>

                        <div className="ncd-rise grid gap-1.5" style={{ animationDelay: '140ms' }}>
                            <Label htmlFor="email">{t('email')}</Label>
                            <div className="group relative">
                                <EnvelopeIcon className={fieldLeadIconClass} />
                                <input
                                    id="email"
                                    type="email"
                                    value={newContactPayload.email}
                                    onChange={(e) => {
                                        setNewContactPayload((prev) => ({ ...prev, email: e.target.value }));
                                        clearError('email');
                                    }}
                                    className={cn(fieldInputClass, 'pl-9 pr-3', fieldErrors.email && fieldErrorClass)}
                                    placeholder={t('emailPlaceholder')}
                                    aria-invalid={Boolean(fieldErrors.email)}
                                    aria-describedby={fieldErrors.email ? 'email-error' : undefined}
                                />
                            </div>
                            {fieldErrors.email && <p id="email-error" className="text-sm text-destructive">{fieldErrors.email}</p>}
                        </div>

                        <div className="ncd-rise grid grid-cols-2 gap-3" style={{ animationDelay: '190ms' }}>
                            <div className="grid gap-1.5">
                                <Label htmlFor="phone">{t('phone')}</Label>
                                <div className="group relative">
                                    <PhoneIcon className={fieldLeadIconClass} />
                                    <input
                                        id="phone"
                                        type="tel"
                                        value={newContactPayload.phone}
                                        onChange={(e) => {
                                            setNewContactPayload((prev) => ({ ...prev, phone: e.target.value }));
                                            clearError('phone');
                                        }}
                                        className={cn(fieldInputClass, 'pl-9 pr-3', fieldErrors.phone && fieldErrorClass)}
                                        placeholder={t('phonePlaceholder')}
                                        aria-invalid={Boolean(fieldErrors.phone)}
                                        aria-describedby={fieldErrors.phone ? 'phone-error' : undefined}
                                    />
                                </div>
                                {fieldErrors.phone && <p id="phone-error" className="text-sm text-destructive">{fieldErrors.phone}</p>}
                            </div>
                            <div className="grid gap-1.5">
                                <Label htmlFor="title">{t('title')}</Label>
                                <div className="group relative">
                                    <BriefcaseIcon className={fieldLeadIconClass} />
                                    <input
                                        id="title"
                                        type="text"
                                        value={newContactPayload.title}
                                        onChange={(e) => setNewContactPayload((prev) => ({ ...prev, title: e.target.value }))}
                                        className={cn(fieldInputClass, 'pl-9 pr-3')}
                                        placeholder={t('titlePlaceholder')}
                                    />
                                </div>
                            </div>
                        </div>

                        <div className="ncd-rise grid gap-1.5" style={{ animationDelay: '240ms' }}>
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
                                <ComboboxInput
                                    id="company"
                                    placeholder={t('selectCompanyPlaceholder')}
                                    className="rounded-lg border-0 bg-muted shadow-none ring-1 ring-border dark:bg-muted has-[[data-slot=input-group-control]:focus-visible]:ring-2 has-[[data-slot=input-group-control]:focus-visible]:ring-brand"
                                >
                                    <InputGroupAddon align="inline-start">
                                        <BuildingOffice2Icon className="size-4 text-muted-foreground transition-colors group-focus-within/input-group:text-brand" />
                                    </InputGroupAddon>
                                </ComboboxInput>
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

                        <ResponsiveDialogFooter className="ncd-rise mt-5" style={{ animationDelay: '290ms' }}>
                            <ResponsiveDialogClose asChild>
                                <Button type="button" variant="outline" disabled={isCreating}>{t('cancel')}</Button>
                            </ResponsiveDialogClose>
                            <Button
                                type="submit"
                                variant="brand"
                                disabled={isCreating || isSuccess}
                                className="min-w-24 shadow-sm transition hover:shadow-md"
                            >
                                {isCreating ? <Loader2Icon className="size-4 animate-spin" /> : t('create')}
                            </Button>
                        </ResponsiveDialogFooter>
                    </form>
                </div>
            </ResponsiveDialogContent>
        </ResponsiveDialog>
    );
}
