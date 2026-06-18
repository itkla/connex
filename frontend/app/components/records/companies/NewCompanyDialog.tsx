'use client';

import { Dialog, DialogContent, DialogHeader, DialogTitle, DialogDescription, DialogFooter, DialogClose } from '@/components/ui/dialog';
import { Button } from '@/components/ui/button';
import { Label } from '@/components/ui/label';
import { cn } from '@/lib/utils';
import { type CreateCompanyPayload } from '@/app/lib/types';
import { ChangeEvent, DragEvent, Dispatch, FormEvent, SetStateAction, useEffect, useState } from 'react';
import {
    Loader2Icon,
    CameraIcon,
    ImagePlusIcon,
    XIcon,
    Building2Icon,
    GlobeIcon,
    BriefcaseIcon,
    PhoneIcon,
    MapPinIcon,
} from 'lucide-react';
import { useTranslations } from 'next-intl';
import { useFieldErrors } from '@/app/hooks/useFieldErrors';

const inputBase = 'w-full rounded-lg bg-muted py-2 text-sm text-foreground placeholder:text-muted-foreground outline-none ring-1 ring-border transition focus:ring-2 focus:ring-brand';
const inputError = 'ring-2 ring-destructive focus:ring-destructive';
const leadIcon = 'pointer-events-none absolute left-3 top-1/2 size-4 -translate-y-1/2 text-muted-foreground transition-colors group-focus-within:text-brand';

type Props = {
    open: boolean;
    onOpenChange: (open: boolean) => void;
    payload: CreateCompanyPayload;
    setPayload: Dispatch<SetStateAction<CreateCompanyPayload>>;
    logoFile: File | null;
    setLogoFile: Dispatch<SetStateAction<File | null>>;
    isCreating: boolean;
    isSuccess?: boolean;
    createNewCompany: () => void | Promise<void>;
};

export default function NewCompanyDialog({
    open,
    onOpenChange,
    payload,
    setPayload,
    logoFile,
    setLogoFile,
    isCreating,
    isSuccess = false,
    createNewCompany,
}: Props) {
    const t = useTranslations('CompaniesNewDialog');
    const [logoPreview, setLogoPreview] = useState<string | null>(null);
    const [isDragging, setIsDragging] = useState(false);
    const { fieldErrors, reset: resetFieldErrors, clearError, captureFieldErrors } = useFieldErrors();

    const initial = payload.name.trim().charAt(0).toUpperCase();
    const hasErrors = Object.keys(fieldErrors).length > 0;
    const status: 'idle' | 'loading' | 'success' | 'error' = isCreating
        ? 'loading'
        : hasErrors
            ? 'error'
            : isSuccess
                ? 'success'
                : 'idle';

    useEffect(() => {
        if (!open && logoPreview) {
            URL.revokeObjectURL(logoPreview);
            setLogoPreview(null);
        }
        if (!open) {
            resetFieldErrors();
            setIsDragging(false);
        }
    }, [open, logoPreview, resetFieldErrors]);

    useEffect(() => {
        return () => {
            if (logoPreview) URL.revokeObjectURL(logoPreview);
        };
    }, [logoPreview]);

    const applyFile = (file: File | undefined | null) => {
        if (!file || !file.type.startsWith('image/')) return;
        if (logoPreview) URL.revokeObjectURL(logoPreview);
        setLogoPreview(URL.createObjectURL(file));
        setLogoFile(file);
    };

    const handleLogoChange = (e: ChangeEvent<HTMLInputElement>) => {
        applyFile(e.target.files?.[0]);
        e.target.value = '';
    };

    const handleDrop = (e: DragEvent<HTMLLabelElement>) => {
        e.preventDefault();
        setIsDragging(false);
        applyFile(e.dataTransfer.files?.[0]);
    };

    const removeLogo = () => {
        if (logoPreview) URL.revokeObjectURL(logoPreview);
        setLogoPreview(null);
        setLogoFile(null);
    };

    const handleSubmit = async (e: FormEvent) => {
        e.preventDefault();
        if (isCreating) return;
        resetFieldErrors();
        try {
            await createNewCompany();
        } catch (err) {
            captureFieldErrors(err);
        }
    };

    return (
        <Dialog open={open} onOpenChange={onOpenChange}>
            <DialogContent className="gap-0 overflow-hidden p-0 sm:max-w-lg">
                <div
                    aria-hidden
                    className={cn(
                        'relative h-24 overflow-hidden transition-colors duration-500 ease-out',
                        status === 'idle' && 'bg-transparent',
                        status === 'loading' && 'bg-muted',
                        status === 'success' && 'bg-brand',
                        status === 'error' && 'bg-destructive'
                    )}
                >
                    {status === 'loading' && <div className="ncd-skeleton absolute inset-0" />}
                    <div
                        className={cn(
                            'absolute inset-0 opacity-[0.14] transition-opacity duration-500',
                            status !== 'success' && status !== 'error' && 'opacity-0'
                        )}
                        style={{
                            backgroundImage: 'radial-gradient(circle at 1px 1px, #fff 1px, transparent 0)',
                            backgroundSize: '15px 15px',
                        }}
                    />
                    <div
                        className={cn(
                            'absolute -left-10 -top-16 size-48 rounded-full bg-white/25 blur-2xl transition-opacity duration-500',
                            status !== 'success' && status !== 'error' && 'opacity-0'
                        )}
                    />
                    <div className="absolute inset-x-0 bottom-0 h-1/2 bg-gradient-to-t from-popover to-transparent" />
                </div>

                <div className="px-6 pb-6">
                    <div className="ncd-pop relative -mt-12 mb-4 w-fit">
                        <label
                            htmlFor="company-logo"
                            onDragOver={(e) => {
                                e.preventDefault();
                                setIsDragging(true);
                            }}
                            onDragLeave={() => setIsDragging(false)}
                            onDrop={handleDrop}
                            className={cn(
                                'group relative flex size-20 cursor-pointer items-center justify-center overflow-hidden rounded-2xl shadow-lg ring-4 ring-popover transition-[outline,box-shadow] has-[:focus-visible]:outline has-[:focus-visible]:outline-2 has-[:focus-visible]:outline-offset-2 has-[:focus-visible]:outline-brand',
                                isDragging && 'outline outline-2 outline-offset-2 outline-brand'
                            )}
                        >
                            {logoPreview ? (
                                <img src={logoPreview} alt="" className="size-full object-contain bg-white" />
                            ) : initial ? (
                                <div className="flex size-full select-none items-center justify-center bg-brand-light text-3xl font-semibold text-brand-dark">
                                    {initial}
                                </div>
                            ) : (
                                <div className="flex size-full items-center justify-center bg-brand-light">
                                    <ImagePlusIcon className="size-7 text-brand-dark/70 transition group-hover:text-brand-dark" />
                                </div>
                            )}

                            {(logoPreview || initial) && (
                                <div className="absolute inset-0 flex items-center justify-center bg-black/45 opacity-0 transition group-hover:opacity-100">
                                    <CameraIcon className="size-5 text-white" />
                                </div>
                            )}

                            <input
                                id="company-logo"
                                type="file"
                                accept="image/*"
                                onChange={handleLogoChange}
                                className="sr-only"
                            />
                        </label>

                        {logoPreview && (
                            <button
                                type="button"
                                onClick={removeLogo}
                                aria-label="Remove logo"
                                className="absolute -right-1 -top-1 flex size-5 items-center justify-center rounded-full bg-foreground text-background ring-2 ring-popover transition hover:scale-110 active:scale-95"
                            >
                                <XIcon className="size-3" />
                            </button>
                        )}
                    </div>

                    <DialogHeader className="ncd-rise mb-5" style={{ animationDelay: '40ms' }}>
                        <DialogTitle className="text-xl font-semibold tracking-tight">{t('title')}</DialogTitle>
                        <DialogDescription>{t('description')}</DialogDescription>
                    </DialogHeader>

                    <form onSubmit={handleSubmit} className="grid gap-5">
                        <div className="ncd-rise grid gap-1.5" style={{ animationDelay: '90ms' }}>
                            <Label htmlFor="company-name">
                                {t('labelName')} <span className="text-muted-foreground">*</span>
                            </Label>
                            <div className="group relative">
                                <Building2Icon className={leadIcon} />
                                <input
                                    id="company-name"
                                    type="text"
                                    value={payload.name}
                                    onChange={(e) => {
                                        setPayload((prev) => ({ ...prev, name: e.target.value }));
                                        clearError('name');
                                    }}
                                    className={cn(inputBase, 'pl-9 pr-3', fieldErrors.name && inputError)}
                                    placeholder={t('placeholderName')}
                                    aria-invalid={Boolean(fieldErrors.name)}
                                    autoComplete="organization"
                                    autoFocus
                                    required
                                />
                            </div>
                            {fieldErrors.name && <p className="text-sm text-destructive">{fieldErrors.name}</p>}
                        </div>

                        <div className="ncd-rise grid gap-1.5" style={{ animationDelay: '140ms' }}>
                            <Label htmlFor="company-website">{t('labelWebsite')}</Label>
                            <div className="group relative">
                                <GlobeIcon className={leadIcon} />
                                <input
                                    id="company-website"
                                    type="url"
                                    value={payload.website ?? ''}
                                    onChange={(e) => {
                                        setPayload((prev) => ({ ...prev, website: e.target.value }));
                                        clearError('website');
                                    }}
                                    className={cn(inputBase, 'pl-9 pr-3', fieldErrors.website && inputError)}
                                    aria-invalid={Boolean(fieldErrors.website)}
                                    placeholder={t('placeholderWebsite')}
                                    autoComplete="url"
                                />
                            </div>
                            {fieldErrors.website && <p className="text-sm text-destructive">{fieldErrors.website}</p>}
                        </div>

                        <div className="ncd-rise grid grid-cols-2 gap-3" style={{ animationDelay: '190ms' }}>
                            <div className="grid gap-1.5">
                                <Label htmlFor="company-industry">
                                    {t('labelIndustry')} <span className="text-muted-foreground">*</span>
                                </Label>
                                <div className="group relative">
                                    <BriefcaseIcon className={leadIcon} />
                                    <input
                                        id="company-industry"
                                        type="text"
                                        value={payload.industry ?? ''}
                                        onChange={(e) => setPayload((prev) => ({ ...prev, industry: e.target.value }))}
                                        className={cn(inputBase, 'pl-9 pr-3')}
                                        placeholder={t('placeholderIndustry')}
                                    />
                                </div>
                            </div>
                            <div className="grid gap-1.5">
                                <Label htmlFor="company-phone">{t('labelPhone')}</Label>
                                <div className="group relative">
                                    <PhoneIcon className={leadIcon} />
                                    <input
                                        id="company-phone"
                                        type="tel"
                                        value={payload.phone ?? ''}
                                        onChange={(e) => {
                                            setPayload((prev) => ({ ...prev, phone: e.target.value }));
                                            clearError('phone');
                                        }}
                                        className={cn(inputBase, 'pl-9 pr-3', fieldErrors.phone && inputError)}
                                        placeholder={t('placeholderPhone')}
                                        aria-invalid={Boolean(fieldErrors.phone)}
                                        autoComplete="tel"
                                    />
                                </div>
                                {fieldErrors.phone && <p className="text-sm text-destructive">{fieldErrors.phone}</p>}
                            </div>
                        </div>

                        <div className="ncd-rise grid gap-1.5" style={{ animationDelay: '240ms' }}>
                            <Label htmlFor="company-address">{t('labelAddress')}</Label>
                            <div className="group relative">
                                <MapPinIcon className={leadIcon} />
                                <input
                                    id="company-address"
                                    type="text"
                                    value={payload.address ?? ''}
                                    onChange={(e) => setPayload((prev) => ({ ...prev, address: e.target.value }))}
                                    className={cn(inputBase, 'pl-9 pr-3')}
                                    placeholder={t('placeholderAddress')}
                                    autoComplete="street-address"
                                />
                            </div>
                        </div>

                        <DialogFooter className="ncd-rise mt-1" style={{ animationDelay: '290ms' }}>
                            <DialogClose asChild>
                                <Button type="button" variant="outline" disabled={isCreating}>
                                    {t('cancel')}
                                </Button>
                            </DialogClose>
                            <Button
                                type="submit"
                                disabled={isCreating || hasErrors || isSuccess}
                                className="min-w-24 bg-brand text-white shadow-sm transition hover:bg-brand-hover hover:shadow-md"
                            >
                                {isCreating ? (
                                    <>
                                        <Loader2Icon className="size-4 animate-spin" />
                                        {t('create')}
                                    </>
                                ) : (
                                    t('create')
                                )}
                            </Button>
                        </DialogFooter>
                    </form>
                </div>
            </DialogContent>
        </Dialog>
    );
}


