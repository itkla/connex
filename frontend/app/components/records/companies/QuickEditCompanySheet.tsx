'use client';

import { Sheet, SheetContent, SheetHeader, SheetTitle, SheetDescription, SheetFooter, SheetClose } from '@/components/ui/sheet';
import { Button } from '@/components/ui/button';
import { Loader2Icon } from 'lucide-react';
import { CameraIcon, BuildingOffice2Icon } from '@heroicons/react/24/outline';
import { Label } from '@/components/ui/label';
import { type Company } from '@/app/lib/types';
import { type SelectionId } from '@/app/components/records/types';
import CompanyAvatar from '@/app/components/records/companies/CompanyAvatar';
import { useTranslations } from 'next-intl';

// const inputClass = 'w-full rounded-lg bg-neutral-100 px-3 py-2 text-sm text-black placeholder-neutral-500 outline-none ring-1 ring-black/5 transition focus:ring-2 focus:ring-brand';

export type CompanyDraft = {
    name: string;
    website: string;
    industry: string;
    phone: string;
    address: string;
};

type Props = {
    open: boolean;
    onOpenChange: (open: boolean) => void;
    selectedIds: Set<SelectionId>;
    selectedCompanies: Company[];
    drafts: Record<number, CompanyDraft>;
    updateDraft: (id: number, patch: Partial<CompanyDraft>) => void;
    logoFiles?: Record<number, File | null>;
    updateLogoFile?: (id: number, file: File | null) => void;
    isSaving: boolean;
    saveEdits: () => void;
};

export default function QuickEditCompanySheet({
    open,
    onOpenChange,
    selectedIds,
    selectedCompanies,
    drafts,
    updateDraft,
    logoFiles,
    updateLogoFile,
    isSaving,
    saveEdits,
}: Props) {
    const t = useTranslations('CompaniesQuickEditSheet');
    return (
        <Sheet open={open} onOpenChange={onOpenChange}>
            <SheetContent side="right" className="flex w-full flex-col sm:max-w-lg">
                <SheetHeader className="border-b">
                    <SheetTitle>
                        {selectedIds.size === 1 ? t('titleSingle') : t('titleMultiple', { count: selectedIds.size })}
                    </SheetTitle>
                    <SheetDescription>
                        {t('description')}
                    </SheetDescription>
                </SheetHeader>

                <div className="flex-1 overflow-y-auto px-4 py-2">
                    <div className="flex flex-col gap-6">
                        {selectedCompanies.map((c, idx) => {
                            const draft = drafts[c.id];
                            if (!draft) return null;
                            const pendingLogo = logoFiles?.[c.id] ?? null;
                            const previewSrc = pendingLogo
                                ? URL.createObjectURL(pendingLogo)
                                : c.logoUrl || null;
                            return (
                                <div key={c.id} className={idx > 0 ? 'border-t pt-6' : ''}>
                                    <div className="mb-3 flex items-center gap-3">
                                        {/* {updateLogoFile ? (
                                            <label
                                                htmlFor={`logo-${c.id}`}
                                                className="group relative flex h-16 w-16 cursor-pointer items-center justify-center overflow-hidden rounded-2xl bg-neutral-200 ring-1 ring-black/5 transition hover:ring-2 hover:ring-brand"
                                            >
                                                {previewSrc ? (
                                                    <img src={previewSrc} alt="" className="h-full w-full object-cover" />
                                                ) : (
                                                    <CompanyAvatar company={c} type="large" />
                                                )}
                                                <div className="absolute inset-0 flex items-center justify-center bg-black/40 opacity-0 transition group-hover:opacity-100">
                                                    <CameraIcon className="size-5 text-white" />
                                                </div>
                                                <input
                                                    id={`logo-${c.id}`}
                                                    type="file"
                                                    accept="image/*"
                                                    onChange={(e) => updateLogoFile(c.id, e.target.files?.[0] ?? null)}
                                                    className="sr-only"
                                                />
                                            </label>
                                        ) : (
                                            <CompanyAvatar company={c} type="large" />
                                        )} */}
                                        <label
                                            htmlFor={`logo-${c.id}`}
                                            className="group relative flex h-16 w-16 cursor-pointer items-center justify-center overflow-hidden rounded-2xl bg-neutral-200 ring-1 ring-black/5 transition hover:ring-2 hover:ring-brand"
                                        >
                                            {previewSrc ? (
                                                <img src={previewSrc} alt="" className="h-full w-full object-cover" />
                                            ) : (
                                                <CompanyAvatar company={c} type="large" />
                                            )}
                                            <div className="absolute inset-0 flex items-center justify-center bg-black/40 opacity-0 transition group-hover:opacity-100">
                                                <CameraIcon className="size-5 text-white" />
                                            </div>
                                            <input
                                                id={`logo-${c.id}`}
                                                type="file"
                                                accept="image/*"
                                                onChange={(e) => updateLogoFile?.(c.id, e.target.files?.[0] ?? null)}
                                                className="sr-only"
                                            />
                                        </label>
                                        <div className="text-lg font-medium text-neutral-600">{c.name}</div>
                                    </div>

                                    <div className="grid gap-3">
                                        <div className="grid gap-1.5">
                                            <Label htmlFor={`name-${c.id}`}>{t('labelName')}</Label>
                                            <input
                                                id={`name-${c.id}`}
                                                type="text"
                                                value={draft.name}
                                                onChange={(e) => updateDraft(c.id, { name: e.target.value })}
                                                className="connex-input"
                                                required
                                            />
                                        </div>
                                        <div className="grid gap-1.5">
                                            <Label htmlFor={`website-${c.id}`}>{t('labelWebsite')}</Label>
                                            <input
                                                id={`website-${c.id}`}
                                                type="url"
                                                value={draft.website}
                                                onChange={(e) => updateDraft(c.id, { website: e.target.value })}
                                                className="connex-input"
                                            />
                                        </div>
                                        <div className="grid gap-1.5">
                                            <Label htmlFor={`industry-${c.id}`}>{t('labelIndustry')}</Label>
                                            <input
                                                id={`industry-${c.id}`}
                                                type="text"
                                                value={draft.industry}
                                                onChange={(e) => updateDraft(c.id, { industry: e.target.value })}
                                                className="connex-input"
                                            />
                                        </div>
                                        <div className="grid gap-1.5">
                                            <Label htmlFor={`phone-${c.id}`}>{t('labelPhone')}</Label>
                                            <input
                                                id={`phone-${c.id}`}
                                                type="tel"
                                                value={draft.phone}
                                                onChange={(e) => updateDraft(c.id, { phone: e.target.value })}
                                                className="connex-input"
                                            />
                                        </div>
                                        <div className="grid gap-1.5">
                                            <Label htmlFor={`address-${c.id}`}>{t('labelAddress')}</Label>
                                            <input
                                                id={`address-${c.id}`}
                                                type="text"
                                                value={draft.address}
                                                onChange={(e) => updateDraft(c.id, { address: e.target.value })}
                                                className="connex-input"
                                            />
                                        </div>
                                    </div>
                                </div>
                            );
                        })}
                    </div>
                </div>

                <SheetFooter className="border-t">
                    <SheetClose asChild>
                        <Button variant="outline" disabled={isSaving}>{t('cancel')}</Button>
                    </SheetClose>
                    <Button onClick={saveEdits} disabled={isSaving} className="bg-brand text-white hover:bg-brand-dark">
                        {isSaving ? <Loader2Icon className="size-4 animate-spin" /> : t('save')}
                    </Button>
                </SheetFooter>
            </SheetContent>
        </Sheet>
    );
}