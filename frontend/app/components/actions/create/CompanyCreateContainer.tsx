'use client';

import { useCallback, useEffect, useRef, useState } from 'react';
import { useRouter } from 'next/navigation';
import { useTranslations } from 'next-intl';

import NewCompanyDialog, { NewCompanyForm } from '@/app/components/records/companies/NewCompanyDialog';
import type { PendingContact, PendingContactDraft } from '@/app/components/records/companies/CompanyContactsField';
import {
    createCompany,
    createContact,
    getCompanies,
    isFieldError,
    uploadCompanyLogo,
    uploadContactPicture,
} from '@/app/lib/api';
import { toastError, toastSuccess } from '@/app/lib/toast';
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
    embedded = false,
    onCancel,
    requestInit,
}: {
    open: boolean;
    onOpenChange: (open: boolean) => void;
    /** Renders the shell-less {@link NewCompanyForm} directly, for embedding in the morphing launcher. */
    embedded?: boolean;
    /** Cancel handler for embedded mode — steps back to the launcher selector. */
    onCancel?: () => void;
    requestInit?: RequestInit;
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
    const closeTimerRef = useRef<number | null>(null);
    const closeGenerationRef = useRef(0);

    const invalidatePendingClose = useCallback(() => {
        closeGenerationRef.current += 1;
        if (closeTimerRef.current == null) return;
        window.clearTimeout(closeTimerRef.current);
        closeTimerRef.current = null;
    }, []);

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
            duplicateReviewToken: c.duplicateReviewToken ?? undefined,
        };
        const newContact = await createContact(contactPayload, requestInit);
        if (requestInit?.signal?.aborted) return newContact;
        if (c.imageFile) {
            await uploadContactPicture(newContact.id, c.imageFile, requestInit).catch(() => undefined);
        }
        return newContact;
    };

    useEffect(() => {
        if (!open || loaded) return;
        let cancelled = false;
        getCompanies(requestInit)
            .then((next) => {
                if (cancelled || requestInit?.signal?.aborted) return;
                setExistingCompanies(next);
                setLoaded(true);
            })
            .catch(() => {
                if (cancelled || requestInit?.signal?.aborted) return;
                setExistingCompanies([]);
                setLoaded(true);
            });
        return () => {
            cancelled = true;
        };
    }, [open, loaded, requestInit]);

    useEffect(() => {
        invalidatePendingClose();
        if (!open) return;
        const raf = window.requestAnimationFrame(() => {
            setPayload(EMPTY_DRAFT);
            setLogoFile(null);
            setPendingContacts([]);
            setSucceeded(false);
        });
        return () => window.cancelAnimationFrame(raf);
    }, [open, invalidatePendingClose]);

    useEffect(() => () => invalidatePendingClose(), [invalidatePendingClose]);

    const handleOpenChange = (next: boolean) => {
        if (!next && creating) return;
        invalidatePendingClose();
        onOpenChange(next);
    };

    const createNewCompany = async (
        duplicateReviewToken: string | null,
        reviewedContacts: PendingContact[] = pendingContacts,
    ) => {
        setSucceeded(false);
        setCreating(true);
        try {
            const companyPayload = {
                ...cleanCompanyPayload(payload),
                duplicateReviewToken: duplicateReviewToken ?? undefined,
            };
            const created = await createCompany(companyPayload, requestInit);
            if (requestInit?.signal?.aborted) return;
            let logoUploadFailed = false;
            if (logoFile) {
                try {
                    await uploadCompanyLogo(created.id, logoFile, requestInit);
                } catch {
                    if (requestInit?.signal?.aborted) return;
                    logoUploadFailed = true;
                }
                if (requestInit?.signal?.aborted) return;
            }
            if (reviewedContacts.length > 0) {
                let failed = 0;
                for (const contact of reviewedContacts) {
                    try {
                        await createPendingContact(contact, created.id);
                    } catch {
                        failed += 1;
                    }
                    if (requestInit?.signal?.aborted) return;
                }
                if (failed === reviewedContacts.length) {
                    toastError(t('feedback.companyContactsAllFailed', { count: failed }));
                } else if (failed > 0) {
                    toastError(
                        t('feedback.companyContactsPartial', {
                            succeeded: reviewedContacts.length - failed,
                            total: reviewedContacts.length,
                        }),
                    );
                }
            }
            toastSuccess(t('feedback.companyCreated'));
            if (logoUploadFailed) toastError(t('feedback.companyLogoUploadFailed'));
            setCreating(false);
            setSucceeded(true);
            invalidatePendingClose();
            const closeGeneration = closeGenerationRef.current;
            closeTimerRef.current = window.setTimeout(() => {
                if (closeGenerationRef.current !== closeGeneration) return;
                closeTimerRef.current = null;
                onOpenChange(false);
                router.refresh();
            }, 900);
        } catch (err) {
            if (requestInit?.signal?.aborted) return;
            setCreating(false);
            if (isFieldError(err)) throw err;
            toastError(err instanceof Error ? err.message : t('feedback.createFailed'));
        }
    };

    if (embedded) {
        return (
            <NewCompanyForm
                active={open}
                onCancel={() => {
                    invalidatePendingClose();
                    if (onCancel) onCancel();
                    else onOpenChange(false);
                }}
                payload={payload}
                setPayload={setPayload}
                logoFile={logoFile}
                setLogoFile={setLogoFile}
                isCreating={creating}
                isSuccess={succeeded}
                existingCompanies={existingCompanies}
                createNewCompany={createNewCompany}
                requestInit={requestInit}
            />
        );
    }

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
            requestInit={requestInit}
        />
    );
}
