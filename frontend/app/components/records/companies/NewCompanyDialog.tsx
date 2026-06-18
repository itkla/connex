'use client';

import { Dialog, DialogContent, DialogHeader, DialogTitle, DialogDescription, DialogFooter, DialogClose } from '@/components/ui/dialog';
import { Button } from '@/components/ui/button';
import { Label } from '@/components/ui/label';
import { Autocomplete, AutocompleteContent, AutocompleteList, AutocompleteItem, AutocompleteEmpty, AutocompleteInput } from '@/components/ui/autocomplete';
import { InputGroupAddon } from '@/components/ui/input-group';
import { cn } from '@/lib/utils';
import { ensureUrlScheme, isLikelyUrl, normalizeWebsiteForCompare } from '@/app/lib/utils';
import { type CreateCompanyPayload, type Company } from '@/app/lib/types';
import { isFieldError } from '@/app/lib/api';
import { ChangeEvent, DragEvent, Dispatch, FormEvent, SetStateAction, useEffect, useMemo, useRef, useState } from 'react';
import Link from 'next/link';
import PixelCard from '@/components/PixelCard';
import {
    ArrowPathIcon,
    CameraIcon,
    XMarkIcon,
    BuildingOffice2Icon,
    GlobeAltIcon,
    BriefcaseIcon,
    PhoneIcon,
    MapPinIcon,
    ExclamationTriangleIcon,
} from '@heroicons/react/24/outline';
import { ImagePlusIcon } from 'lucide-react';
import { useTranslations } from 'next-intl';
import { useFieldErrors } from '@/app/hooks/useFieldErrors';

const inputBase = 'w-full rounded-lg bg-muted py-2 text-sm text-foreground placeholder:text-muted-foreground outline-none ring-1 ring-border transition focus:ring-2 focus:ring-brand';
const inputError = 'ring-2 ring-destructive focus:ring-destructive';
const PIXEL_GRAY = '#e5e7eb,#cbd5e1,#94a3b8';
const PIXEL_GREEN = '#bbf7d0,#86efac,#73d200';
const PIXEL_RED = '#fecaca,#f87171,#ef4444';
const inputWarn = 'ring-2 ring-amber-500 focus:ring-amber-500';
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
    existingCompanies?: Company[];
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
    existingCompanies = [],
    createNewCompany,
}: Props) {
    const t = useTranslations('CompaniesNewDialog');
    const [logoPreview, setLogoPreview] = useState<string | null>(null);
    const [isDragging, setIsDragging] = useState(false);
    const [websiteFormatError, setWebsiteFormatError] = useState<string | null>(null);
    const { fieldErrors, reset: resetFieldErrors, clearError, captureFieldErrors } = useFieldErrors();

    const initial = payload.name.trim().charAt(0).toUpperCase();

    const websiteByDomain = useMemo(() => {
        const map = new Map<string, Company>();
        for (const c of existingCompanies) {
            const key = normalizeWebsiteForCompare(c.website);
            if (key && !map.has(key)) map.set(key, c);
        }
        return map;
    }, [existingCompanies]);

    const industrySuggestions = useMemo(() => {
        const set = new Set<string>();
        for (const c of existingCompanies) {
            const v = c.industry?.trim();
            if (v) set.add(v);
        }
        return Array.from(set).sort((a, b) => a.localeCompare(b));
    }, [existingCompanies]);

    const duplicateCompany = useMemo(() => {
        const key = normalizeWebsiteForCompare(payload.website);
        return key ? websiteByDomain.get(key) ?? null : null;
    }, [payload.website, websiteByDomain]);

    const hasErrors = Object.keys(fieldErrors).length > 0;
    const websiteBlocked = Boolean(duplicateCompany) || Boolean(websiteFormatError);
    const status: 'idle' | 'loading' | 'success' | 'error' = isCreating
        ? 'loading'
        : hasErrors
            ? 'error'
            : isSuccess
                ? 'success'
                : 'idle';

    const lastPixelColorsRef = useRef(PIXEL_GRAY);
    let pixelColors = lastPixelColorsRef.current;
    if (status === 'loading') pixelColors = PIXEL_GRAY;
    else if (status === 'success') pixelColors = PIXEL_GREEN;
    else if (status === 'error') pixelColors = PIXEL_RED;
    lastPixelColorsRef.current = pixelColors;

    useEffect(() => {
        if (!open && logoPreview) {
            URL.revokeObjectURL(logoPreview);
            setLogoPreview(null);
        }
        if (!open) {
            resetFieldErrors();
            setIsDragging(false);
            setWebsiteFormatError(null);
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

    const handleWebsiteBlur = () => {
        const normalized = ensureUrlScheme(payload.website ?? '');
        if (normalized !== (payload.website ?? '')) {
            setPayload((prev) => ({ ...prev, website: normalized }));
        }
        setWebsiteFormatError(normalized && !isLikelyUrl(normalized) ? t('websiteInvalid') : null);
    };

    const handleOpenChange = (next: boolean) => {
        if (!next && isCreating) return;
        onOpenChange(next);
    };

    const handleSubmit = async (e: FormEvent) => {
        e.preventDefault();
        if (isCreating || websiteBlocked) return;
        resetFieldErrors();
        try {
            await createNewCompany();
        } catch (err) {
            captureFieldErrors(err);
            if (isFieldError(err)) {
                const firstKey = Object.keys(err.fieldErrors)[0];
                if (firstKey) {
                    requestAnimationFrame(() => document.getElementById(`company-${firstKey}`)?.focus());
                }
            }
        }
    };

    return (
        <Dialog open={open} onOpenChange={handleOpenChange}>
            <DialogContent className="gap-0 overflow-hidden p-0 sm:max-w-lg">
                <div aria-hidden className="relative h-24 overflow-hidden">
                    <PixelCard
                        active={status !== 'idle'}
                        colors={pixelColors}
                        gap={5}
                        speed={40}
                        noFocus
                        className="pointer-events-none absolute inset-0 aspect-auto! h-full! w-full! rounded-none! border-0!"
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
                                <img src={logoPreview} alt="" className="size-full bg-white object-contain" />
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
                                <XMarkIcon className="size-3" />
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
                                <BuildingOffice2Icon className={leadIcon} />
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
                                    aria-describedby={fieldErrors.name ? 'company-name-error' : undefined}
                                    autoComplete="organization"
                                    autoFocus
                                    required
                                />
                            </div>
                            {fieldErrors.name && <p id="company-name-error" className="text-sm text-destructive">{fieldErrors.name}</p>}
                        </div>

                        <div className="ncd-rise grid gap-1.5" style={{ animationDelay: '140ms' }}>
                            <Label htmlFor="company-website">{t('labelWebsite')}</Label>
                            <div className="group relative">
                                <GlobeAltIcon className={leadIcon} />
                                <input
                                    id="company-website"
                                    type="url"
                                    value={payload.website ?? ''}
                                    onChange={(e) => {
                                        setPayload((prev) => ({ ...prev, website: e.target.value }));
                                        clearError('website');
                                        setWebsiteFormatError(null);
                                    }}
                                    onBlur={handleWebsiteBlur}
                                    className={cn(
                                        inputBase,
                                        'pl-9 pr-3',
                                        (fieldErrors.website || websiteFormatError) && inputError,
                                        duplicateCompany && !fieldErrors.website && !websiteFormatError && inputWarn
                                    )}
                                    aria-invalid={Boolean(fieldErrors.website || websiteFormatError || duplicateCompany)}
                                    aria-describedby={(fieldErrors.website || websiteFormatError || duplicateCompany) ? 'company-website-help' : undefined}
                                    placeholder={t('placeholderWebsite')}
                                    autoComplete="url"
                                />
                            </div>
                            {fieldErrors.website ? (
                                <p id="company-website-help" className="text-sm text-destructive">{fieldErrors.website}</p>
                            ) : duplicateCompany ? (
                                <p id="company-website-help" className="flex items-center gap-1.5 text-sm text-amber-600 dark:text-amber-500">
                                    <ExclamationTriangleIcon className="size-3.5 shrink-0" />
                                    <span>
                                        {t('websiteDuplicate')}{' '}
                                        <Link
                                            href={`/records/companies/${duplicateCompany.id}`}
                                            className="font-medium underline underline-offset-2 hover:text-foreground"
                                        >
                                            {duplicateCompany.name}
                                        </Link>
                                    </span>
                                </p>
                            ) : websiteFormatError ? (
                                <p id="company-website-help" className="text-sm text-destructive">{websiteFormatError}</p>
                            ) : null}
                        </div>

                        <div className="ncd-rise grid grid-cols-2 gap-3" style={{ animationDelay: '190ms' }}>
                            <div className="grid gap-1.5">
                                <Label htmlFor="company-industry">
                                    {t('labelIndustry')}
                                </Label>
                                <Autocomplete
                                    items={industrySuggestions}
                                    value={payload.industry ?? ''}
                                    onValueChange={(v) => setPayload((prev) => ({ ...prev, industry: v }))}
                                    mode="both"
                                >
                                    <AutocompleteInput
                                        id="company-industry"
                                        placeholder={t('placeholderIndustry')}
                                        className="rounded-lg border-0 bg-muted shadow-none ring-1 ring-border dark:bg-muted has-[[data-slot=input-group-control]:focus-visible]:ring-2 has-[[data-slot=input-group-control]:focus-visible]:ring-brand"
                                    >
                                        <InputGroupAddon align="inline-start">
                                            <BriefcaseIcon className="size-4 text-muted-foreground transition-colors group-focus-within/input-group:text-brand" />
                                        </InputGroupAddon>
                                    </AutocompleteInput>
                                    {industrySuggestions.length > 0 && (
                                        <AutocompleteContent>
                                            <AutocompleteEmpty>{t('noIndustryMatches')}</AutocompleteEmpty>
                                            <AutocompleteList>
                                                {(opt: string) => (
                                                    <AutocompleteItem key={opt} value={opt}>
                                                        {opt}
                                                    </AutocompleteItem>
                                                )}
                                            </AutocompleteList>
                                        </AutocompleteContent>
                                    )}
                                </Autocomplete>
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
                                        aria-describedby={fieldErrors.phone ? 'company-phone-error' : undefined}
                                        autoComplete="tel"
                                    />
                                </div>
                                {fieldErrors.phone && <p id="company-phone-error" className="text-sm text-destructive">{fieldErrors.phone}</p>}
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
                                disabled={isCreating || hasErrors || isSuccess || websiteBlocked}
                                className="min-w-24 bg-brand text-white shadow-sm transition hover:bg-brand-hover hover:shadow-md"
                            >
                                {isCreating ? (
                                    <>
                                        <ArrowPathIcon className="size-4 animate-spin" />
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


