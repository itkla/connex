'use client';

import { useRef, useState } from 'react';
import { useRouter } from 'next/navigation';
import { useTranslations } from 'next-intl';
import { toast } from 'sonner';
import { toastError, toastSuccess } from '@/app/lib/toast';

import QuickEditCompanySheet, { type CompanyDraft } from '@/app/components/records/companies/QuickEditCompanySheet';
import { CustomFieldsEditSection, type CustomFieldsEditHandle } from '@/app/components/records/CustomFieldsEditSection';
import { getCompanyById, updateCompany, uploadCompanyLogo } from '@/app/lib/api';
import { type Company, type UpdateCompanyPayload } from '@/app/lib/types';

function toDraft(c: Company): CompanyDraft {
    return {
        name: c.name ?? '',
        website: c.website ?? '',
        industry: c.industry ?? '',
        phone: c.phone ?? '',
        address: c.address ?? '',
    };
}

function diffDraft(a: CompanyDraft, b: CompanyDraft): boolean {
    return (
        a.name !== b.name ||
        a.website !== b.website ||
        a.industry !== b.industry ||
        a.phone !== b.phone ||
        a.address !== b.address
    );
}

export default function EditCompanySheet({
    company,
    open,
    onOpenChange,
}: {
    company: Company;
    open: boolean;
    onOpenChange: (open: boolean) => void;
}) {
    const router = useRouter();
    const t = useTranslations('CompaniesEditSheet');
    const [draft, setDraft] = useState<CompanyDraft>(() => toDraft(company));
    const [logoFile, setLogoFile] = useState<File | null>(null);
    const [isSaving, setIsSaving] = useState(false);
    const cfRef = useRef<CustomFieldsEditHandle>(null);
    const [customFieldsDirty, setCustomFieldsDirty] = useState(false);

    const handleOpenChange = (next: boolean) => {
        onOpenChange(next);
        if (!next) {
            setDraft(toDraft(company));
            setLogoFile(null);
        }
    };

    const saveEdits = async () => {
        const original = toDraft(company);
        const textChanged = diffDraft(original, draft);
        const logoChanged = logoFile !== null;
        const customChanged = cfRef.current?.hasChanges() ?? false;

        if (!textChanged && !logoChanged && !customChanged) {
            toast.info(t('toastNoChanges'));
            handleOpenChange(false);
            return;
        }

        if (!draft.name.trim()) {
            // TODO: replace this message with the validation error message from the backend
            toast.error(t('toastNameRequired'));
            return;
        }

        setIsSaving(true);
        let committedChanges = false;
        try {
            if (textChanged) {
                const payload: UpdateCompanyPayload = {
                    name: draft.name.trim(),
                    website: draft.website.trim() || undefined,
                    industry: draft.industry.trim() || undefined,
                    phone: draft.phone.trim() || undefined,
                    address: draft.address.trim() || undefined,
                };
                await updateCompany(company.id, payload);
                committedChanges = true;
            }

            if (customChanged) {
                await cfRef.current?.save();
                committedChanges = true;
            }

            if (logoChanged && logoFile) {
                await uploadCompanyLogo(company.id, logoFile);
            }

            toastSuccess(t('toastCompanyUpdated'));
            setLogoFile(null);
            onOpenChange(false);
            router.refresh();
        } catch (err) {
            if (committedChanges) {
                const updatedCompany = await getCompanyById(company.id).catch(() => null);
                if (updatedCompany) setDraft(toDraft(updatedCompany));
                toastError(t('toastPartiallySaved'));
                router.refresh();
            } else {
                toastError(err instanceof Error ? err.message : t('toastSaveFailed'));
            }
        } finally {
            setIsSaving(false);
        }
    };

    return (
        <QuickEditCompanySheet
            open={open}
            onOpenChange={handleOpenChange}
            selectedIds={new Set([company.id])}
            selectedCompanies={[company]}
            drafts={{ [company.id]: draft }}
            updateDraft={(_id, patch) => setDraft((prev) => ({ ...prev, ...patch }))}
            logoFiles={{ [company.id]: logoFile }}
            updateLogoFile={(_id, file) => setLogoFile(file)}
            isSaving={isSaving}
            saveEdits={saveEdits}
            customFieldsDirty={customFieldsDirty}
            customFieldsSlot={(
                <CustomFieldsEditSection
                    ref={cfRef}
                    entityType="company"
                    entityId={company.id}
                    onDirtyChange={setCustomFieldsDirty}
                />
            )}
        />
    );
}
