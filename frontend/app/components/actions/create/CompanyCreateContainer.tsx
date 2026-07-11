'use client';

import { useEffect, useState } from 'react';
import { useRouter } from 'next/navigation';
import { useTranslations } from 'next-intl';

import NewCompanyDialog from '@/app/components/records/companies/NewCompanyDialog';
import type { PendingContact, PendingContactDraft } from '@/app/components/records/companies/CompanyContactsField';
import {
    createCompany,
    createContact,
    getCompanies,
    isFieldError,
    updateCompany,
    updateContact,
} from '@/app/lib/api';
import { toastError, toastSuccess } from '@/app/lib/toast';
import { uploadCompanyLogo, uploadContactPicture } from '@/app/lib/utils';
import type { Company, CreateCompanyPayload } from '@/app/lib/types';

const EMPTY_DRAFT: CreateCompanyPayload = { name: '', website: '', industry: '', phone: '', address: '' };

function cleanCompanyPayload(payload: CreateCompanyPayload): CreateCompanyPayload {
    return {
        name: payload.name.trim(),
        website: payload.website?.trim() || undefined,
        industry: payload.industry?.trim() || undefined,
        phone: payload.phone?.trim() || undefined,
        address: payload.address?.trim() || undefined,
    };
}

/**
 * Shell-owned company quick-create. Reuses {@link NewCompanyDialog} and mirrors the CompaniesBrowser
 * create flow — including logo upload and inline pending contacts — while lazily loading the existing
 * company list (used for duplicate detection) on first open. Field errors are rethrown for the dialog
 * to render; any partial failure creating pending contacts is surfaced without failing the company.
 */
export default function CompanyCreateContainer({
    open,
    onOpenChange,
}: {
    open: boolean;
    onOpenChange: (open: boolean) => void;
}) {
    const router = useRouter();
    const t = useTranslations('Actions');

    const [loaded, setLoaded] = useState(false);
    const [existingCompanies, setExistingCompanies] = useState<Company[]>([]);
    const [payload, setPayload] = useState<CreateCompanyPayload>(EMPTY_DRAFT);
    const [logoFile, setLogoFile] = useState<File | null>(null);
    const [pendingContacts, setPendingContacts] = useState<PendingContact[]>([]);
    const [creating, setCreating] = useState(false);
    const [succeeded, setSucceeded] = useState(false);

    const addPendingContact = (draft: PendingContactDraft) =>
        setPendingContacts((prev) => [...prev, { tempId: crypto.randomUUID(), ...draft }]);
    const updatePendingContact = (tempId: string, draft: PendingContactDraft) =>
        setPendingContacts((prev) => prev.map((c) => (c.tempId === tempId ? { tempId, ...draft } : c)));
    const removePendingContact = (tempId: string) =>
        setPendingContacts((prev) => prev.filter((c) => c.tempId !== tempId));

    const createPendingContact = async (c: PendingContact, companyId: number) => {
        const contactPayload = {
            name: c.name.trim(),
            email: c.email.trim(),
            phone: c.phone.trim(),
            title: c.title.trim(),
            companyId,
        };
        const newContact = await createContact(contactPayload);
        if (c.imageFile) {
            const imageUrl = await uploadContactPicture(newContact.id, c.imageFile).catch(() => null);
            if (imageUrl) await updateContact(newContact.id, { ...contactPayload, imageUrl }).catch(() => undefined);
        }
        return newContact;
    };

    useEffect(() => {
        if (!open || loaded) return;
        let cancelled = false;
        getCompanies()
            .then((next) => {
                if (cancelled) return;
                setExistingCompanies(next);
                setLoaded(true);
            })
            .catch(() => {
                if (cancelled) return;
                setExistingCompanies([]);
                setLoaded(true);
            });
        return () => {
            cancelled = true;
        };
    }, [open, loaded]);

    useEffect(() => {
        if (!open) return;
        const raf = window.requestAnimationFrame(() => {
            setPayload(EMPTY_DRAFT);
            setLogoFile(null);
            setPendingContacts([]);
            setSucceeded(false);
        });
        return () => window.cancelAnimationFrame(raf);
    }, [open]);

    const handleOpenChange = (next: boolean) => {
        if (!next && creating) return;
        onOpenChange(next);
    };

    const createNewCompany = async () => {
        setSucceeded(false);
        setCreating(true);
        try {
            const companyPayload = cleanCompanyPayload(payload);
            const created = await createCompany(companyPayload);
            if (logoFile) {
                const logoUrl = await uploadCompanyLogo(created.id, logoFile);
                await updateCompany(created.id, { ...companyPayload, logoUrl });
            }
            if (pendingContacts.length > 0) {
                const results = await Promise.allSettled(pendingContacts.map((c) => createPendingContact(c, created.id)));
                const failed = results.filter((r) => r.status === 'rejected').length;
                if (failed === pendingContacts.length) {
                    toastError(t('feedback.companyContactsAllFailed', { count: failed }));
                } else if (failed > 0) {
                    toastError(
                        t('feedback.companyContactsPartial', {
                            succeeded: pendingContacts.length - failed,
                            total: pendingContacts.length,
                        }),
                    );
                }
            }
            toastSuccess(t('feedback.companyCreated'));
            setCreating(false);
            setSucceeded(true);
            setTimeout(() => {
                onOpenChange(false);
                router.refresh();
            }, 900);
        } catch (err) {
            setCreating(false);
            if (isFieldError(err)) throw err;
            toastError(err instanceof Error ? err.message : t('feedback.createFailed'));
        }
    };

    return (
        <NewCompanyDialog
            open={open}
            onOpenChange={handleOpenChange}
            payload={payload}
            setPayload={setPayload}
            logoFile={logoFile}
            setLogoFile={setLogoFile}
            isCreating={creating}
            isSuccess={succeeded}
            existingCompanies={existingCompanies}
            createNewCompany={createNewCompany}
            pendingContacts={pendingContacts}
            addPendingContact={addPendingContact}
            updatePendingContact={updatePendingContact}
            removePendingContact={removePendingContact}
        />
    );
}
