'use client';

import { type ReactNode } from 'react';
import { useTranslations } from 'next-intl';
import { BuildingOffice2Icon } from '@heroicons/react/24/outline';

import { Input } from '@/components/ui/input';
import { type Company } from '@/app/lib/types';
import { type SelectionId } from '@/app/components/records/types';
import CompanyAvatar from '@/app/components/records/companies/CompanyAvatar';
import { toastError } from '@/app/lib/toast';
import {
    QuickEditField,
    QuickEditMediaUpload,
    QuickEditRecordCard,
    QuickEditSheetShell,
} from '@/app/components/records/quick-edit/QuickEditSheetShell';

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
    customFieldsSlot?: ReactNode;
};

export default function QuickEditCompanySheet({
    open,
    onOpenChange,
    selectedCompanies,
    drafts,
    updateDraft,
    logoFiles,
    updateLogoFile,
    isSaving,
    saveEdits,
    customFieldsSlot,
}: Props) {
    const t = useTranslations('CompaniesQuickEditSheet');
    const total = selectedCompanies.length;

    return (
        <QuickEditSheetShell
            open={open}
            onOpenChange={onOpenChange}
            icon={<BuildingOffice2Icon />}
            title={total === 1 ? t('titleSingle') : t('titleMultiple', { count: total })}
            description={t('description')}
            count={total}
            isSaving={isSaving}
            onSave={saveEdits}
            saveLabel={t('save')}
            cancelLabel={t('cancel')}
        >
            {selectedCompanies.map((c, idx) => {
                const draft = drafts[c.id];
                if (!draft) return null;
                const pendingLogo = logoFiles?.[c.id] ?? null;
                const media = updateLogoFile ? (
                    <QuickEditMediaUpload
                        id={`logo-${c.id}`}
                        label={t('changeLogo')}
                        shape="squircle"
                        file={pendingLogo}
                        existingUrl={c.logoUrl ?? null}
                        fallback={
                            <div className="flex h-full w-full items-center justify-center bg-muted-foreground/20">
                                <BuildingOffice2Icon className="size-8 text-muted-foreground" />
                            </div>
                        }
                        onSelect={(file) => updateLogoFile(c.id, file)}
                        onInvalidSelect={() => toastError(t('unsupportedLogo'))}
                    />
                ) : (
                    <CompanyAvatar company={c} type="large" />
                );

                return (
                    <QuickEditRecordCard
                        key={c.id}
                        index={idx}
                        total={total}
                        media={media}
                        title={c.name}
                        subtitle={draft.website || draft.industry || undefined}
                    >
                        <QuickEditField label={t('labelName')} htmlFor={`name-${c.id}`} required>
                            <Input
                                id={`name-${c.id}`}
                                type="text"
                                value={draft.name}
                                onChange={(e) => updateDraft(c.id, { name: e.target.value })}
                                required
                            />
                        </QuickEditField>
                        <QuickEditField label={t('labelWebsite')} htmlFor={`website-${c.id}`}>
                            <Input
                                id={`website-${c.id}`}
                                type="url"
                                value={draft.website}
                                onChange={(e) => updateDraft(c.id, { website: e.target.value })}
                            />
                        </QuickEditField>
                        <QuickEditField label={t('labelIndustry')} htmlFor={`industry-${c.id}`}>
                            <Input
                                id={`industry-${c.id}`}
                                type="text"
                                value={draft.industry}
                                onChange={(e) => updateDraft(c.id, { industry: e.target.value })}
                            />
                        </QuickEditField>
                        <QuickEditField label={t('labelPhone')} htmlFor={`phone-${c.id}`}>
                            <Input
                                id={`phone-${c.id}`}
                                type="tel"
                                value={draft.phone}
                                onChange={(e) => updateDraft(c.id, { phone: e.target.value })}
                            />
                        </QuickEditField>
                        <QuickEditField label={t('labelAddress')} htmlFor={`address-${c.id}`}>
                            <Input
                                id={`address-${c.id}`}
                                type="text"
                                value={draft.address}
                                onChange={(e) => updateDraft(c.id, { address: e.target.value })}
                            />
                        </QuickEditField>
                    </QuickEditRecordCard>
                );
            })}
            {customFieldsSlot}
        </QuickEditSheetShell>
    );
}
