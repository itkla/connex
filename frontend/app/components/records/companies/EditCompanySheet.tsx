'use client';

import { useState } from 'react';
import { useRouter } from 'next/navigation';
import { toast } from 'sonner';

import QuickEditCompanySheet, { type CompanyDraft } from '@/app/components/records/companies/QuickEditCompanySheet';
import { getCompanyById, updateCompany } from '@/app/lib/api';
import { type Company, type UpdateCompanyPayload } from '@/app/lib/types';
import { uploadCompanyLogo } from '@/app/lib/utils';

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
    const [draft, setDraft] = useState<CompanyDraft>(() => toDraft(company));
    const [logoFile, setLogoFile] = useState<File | null>(null);
    const [isSaving, setIsSaving] = useState(false);

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

        if (!textChanged && !logoChanged) {
            toast.info('No changes to save');
            handleOpenChange(false);
            return;
        }

        if (!draft.name.trim()) {
            // TODO: replace this message with the validation error message from the backend
            toast.error('Name is required');
            return;
        }

        setIsSaving(true);
        try {
            let logoUrl: string | undefined;
            if (logoChanged && logoFile) {
                logoUrl = await uploadCompanyLogo(company.id, logoFile);
            }

            const payload: UpdateCompanyPayload = {
                name: draft.name.trim(),
                website: draft.website.trim() || undefined,
                industry: draft.industry.trim() || undefined,
                phone: draft.phone.trim() || undefined,
                address: draft.address.trim() || undefined,
                logoUrl: logoUrl ?? company.logoUrl ?? undefined,
            };
            await updateCompany(company.id, payload);

            toast.success('Company updated', {
                style: { backgroundColor: 'var(--color-brand)', color: 'white' },
            });
            handleOpenChange(false);

            const updatedCompany = await getCompanyById(company.id);
            if (updatedCompany) {
                setDraft(toDraft(updatedCompany));
                setLogoFile(null);
            }

            router.refresh();
        } catch (err) {
            toast.error(err instanceof Error ? err.message : 'Failed to save', {
                style: { backgroundColor: 'var(--color-destructive)', color: 'white' },
            });
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
        />
    );
}
