'use client';

import { useEffect, useRef, useState, type WheelEvent } from 'react';
import { useRouter } from 'next/navigation';
import { useTranslations } from 'next-intl';
import { toastError, toastSuccess } from '@/app/lib/toast';
import { Loader2Icon } from 'lucide-react';
import { useApiErrorToast } from '@/app/hooks/useApiErrorToast';

import {
    Combobox,
    ComboboxContent,
    ComboboxEmpty,
    ComboboxInput,
    ComboboxItem,
    ComboboxList,
} from '@/components/ui/combobox';
import { InputGroupAddon } from '@/components/ui/input-group';
import { BuildingOffice2Icon } from '@heroicons/react/24/outline';
import {
    Dialog,
    DialogClose,
    DialogContent,
    DialogDescription,
    DialogFooter,
    DialogHeader,
    DialogTitle,
} from '@/components/ui/dialog';
import { Button } from '@/components/ui/button';
import { Label } from '@/components/ui/label';
import { DialogStatusCover, resolveDialogStatus } from '@/components/ui/dialog-status-cover';
import { getContactById } from '@/app/lib/api';
import { renderedWorkspaceId, workspacePinnedRequest } from '@/app/lib/renderedWorkspace';
import { useRecordEditConflict } from '@/app/hooks/useRecordEditConflict';
import ContactEditConflictDialog from './ContactEditConflictDialog';
import { submitContactCompanyUpdate } from './contactEditConflict';
import { type Company, type Contact } from '@/app/lib/types';
import { useCompanySearch } from '@/app/hooks/useCompanySearch';

type Props = {
    open: boolean;
    onOpenChange: (open: boolean) => void;
    contacts: Contact[];
    onSuccess?: () => void;
    removeCompany?: boolean;
};

export default function ChangeCompanyDialog({ open, onOpenChange, contacts, onSuccess, removeCompany = false }: Props) {
    const router = useRouter();
    const t = useTranslations('ContactsChangeCompanyDialog');
    const reportApiError = useApiErrorToast('ContactsChangeCompanyDialog');
    const [bases, setBases] = useState<Contact[]>([]);
    const [isPreparing, setIsPreparing] = useState(open);
    const originWorkspaceRef = useRef(renderedWorkspaceId());
    const { conflict, captureConflict, clearConflict } = useRecordEditConflict(getContactById);
    const idsKey = contacts.map((contact) => contact.id).join(',');
    useEffect(() => {
        if (!open) return;
        let cancelled = false;
        const originWorkspaceId = renderedWorkspaceId();
        originWorkspaceRef.current = originWorkspaceId;
        Promise.all(idsKey.split(',').filter(Boolean).map((id) => getContactById(Number(id), workspacePinnedRequest(originWorkspaceId))))
            .then((loaded) => {
                if (!cancelled && renderedWorkspaceId() === originWorkspaceId) setBases(loaded);
            })
            .catch((error: unknown) => {
                if (!cancelled) reportApiError(error, 'toastFailedLoadContact');
            })
            .finally(() => { if (!cancelled) setIsPreparing(false); });
        return () => { cancelled = true; };
    }, [open, idsKey, reportApiError]);
    const defaultCompanyId = bases.length === 1
        ? bases[0].companyId ?? bases[0].company?.id ?? null
        : null;
    const selectionScope = `${contacts.map((contact) => contact.id).join(',')}:${defaultCompanyId ?? ''}`;
    const [selection, setSelection] = useState<{ scope: string; companyId: number | null }>({
        scope: selectionScope,
        companyId: defaultCompanyId,
    });
    const selectedCompanyId = selection.scope === selectionScope
        ? selection.companyId
        : defaultCompanyId;
    const [isSaving, setIsSaving] = useState(false);
    const [succeeded, setSucceeded] = useState(false);
    const [prevOpen, setPrevOpen] = useState(open);
    const [previousIds, setPreviousIds] = useState(idsKey);
    const currentCompany = bases.length === 1 ? bases[0].company ?? null : null;
    const companySearch = useCompanySearch(
        open,
        [defaultCompanyId, selectedCompanyId],
        currentCompany ? [currentCompany] : [],
    );
    const selected = companySearch.companies.find(
        (company) => company.id === selectedCompanyId,
    ) ?? (currentCompany?.id === selectedCompanyId ? currentCompany : null);

    if (open !== prevOpen || idsKey !== previousIds) {
        setPrevOpen(open);
        setPreviousIds(idsKey);
        setIsPreparing(open);
        if (open) setBases([]);
        clearConflict();
        if (open) {
            setSucceeded(false);
            setSelection({ scope: selectionScope, companyId: defaultCompanyId });
        }
    }

    useEffect(() => {
        if (companySearch.error) toastError(t('companySearchFailed'));
    }, [companySearch.error, t]);

    const handleListWheel = (e: WheelEvent<HTMLDivElement>) => {
        const lineHeightPx = 16;
        const delta = e.deltaMode === 1 ? e.deltaY * lineHeightPx : e.deltaY;
        e.currentTarget.scrollTop += delta;
    };

    const handleSave = async () => {
        if (!removeCompany && !selected) {
            toastError(t('toastPickCompany'));
            return;
        }
        setIsSaving(true);
        try {
            const origin = workspacePinnedRequest(originWorkspaceRef.current);
            for (const base of bases) {
                const targetCompanyId = removeCompany ? null : selected?.id ?? null;
                if ((base.companyId ?? base.company?.id ?? null) === targetCompanyId) continue;
                try {
                    const saved = await submitContactCompanyUpdate(base, targetCompanyId, origin);
                    setBases((current) => current.map((item) => item.id === saved.id ? saved : item));
                } catch (error) {
                    if (!await captureConflict(error, base, origin)) throw error;
                    return;
                }
            }
            toastSuccess(
                removeCompany ? t('toastRemoved', { count: bases.length }) : bases.length === 1
                    ? t('toastAssociatedSingle', { contactName: bases[0].name, companyName: selected?.name ?? '' })
                    : t('toastAssociatedMultiple', { count: bases.length, companyName: selected?.name ?? '' }),
            );
            setIsSaving(false);
            setSucceeded(true);
            onSuccess?.();
            router.refresh();
            setTimeout(() => {
                setSelection({ scope: selectionScope, companyId: defaultCompanyId });
                setSucceeded(false);
                onOpenChange(false);
            }, 900);
        } catch (err) {
            reportApiError(err, 'toastFailedUpdate');
        } finally {
            setIsSaving(false);
        }
    };

    const status = resolveDialogStatus({ isLoading: isSaving, isSuccess: succeeded });

    const handleOpenChange = (next: boolean) => {
        if (!next && (isSaving || isPreparing)) return;
        if (!next) clearConflict();
        if (!next) {
            setSucceeded(false);
            setSelection({ scope: selectionScope, companyId: defaultCompanyId });
        }
        onOpenChange(next);
    };

    return (
        <>
        <Dialog open={open} onOpenChange={handleOpenChange}>
            <DialogContent className="gap-0 overflow-hidden p-0 sm:max-w-lg">
                <DialogStatusCover status={status} />

                <div className="px-6 pb-6">
                    <DialogHeader className="ncd-rise -mt-12 mb-5" style={{ animationDelay: '40ms' }}>
                        <DialogTitle className="text-xl font-semibold tracking-tight">
                            {removeCompany ? t('removeTitle') : contacts.length === 1 ? t('titleSingle') : t('titleMultiple', { count: contacts.length })}
                        </DialogTitle>
                        <DialogDescription>
                            {removeCompany ? t('removeDescription') : contacts.length === 1
                                ? t('descriptionSingle', { name: contacts[0].name })
                                : t('descriptionMultiple')}
                        </DialogDescription>
                    </DialogHeader>

                    <form
                        onSubmit={(e) => {
                            e.preventDefault();
                            if (isSaving) return;
                            handleSave();
                        }}
                    >
                        {!removeCompany && <div className="ncd-rise grid gap-2" style={{ animationDelay: '90ms' }}>
                            <Label htmlFor="company">{t('companyLabel')}</Label>
                            <Combobox
                                items={companySearch.options}
                                disabled={isSaving || isPreparing}
                                serverSearch={companySearch.serverSearch}
                                autoHighlight
                                itemToStringLabel={(c: Company) => c.name}
                                value={selected}
                                onInputValueChange={companySearch.onInputValueChange}
                                onValueChange={(c) => setSelection({
                                    scope: selectionScope,
                                    companyId: c?.id ?? null,
                                })}
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
                                    <ComboboxEmpty>{companySearch.error ? t('companySearchFailed') : t('noCompaniesFound')}</ComboboxEmpty>
                                    <ComboboxList onWheel={handleListWheel}>
                                        {(company: (typeof companySearch.companies)[number]) => (
                                            <ComboboxItem key={company.id} value={company}>
                                                {company.name}
                                            </ComboboxItem>
                                        )}
                                    </ComboboxList>
                                </ComboboxContent>
                            </Combobox>
                        </div>}

                        <DialogFooter className="ncd-rise mt-5" style={{ animationDelay: '140ms' }}>
                            <DialogClose asChild>
                                <Button type="button" variant="outline" disabled={isSaving}>{t('cancel')}</Button>
                            </DialogClose>
                            <Button
                                type="submit"
                                variant="brand"
                                disabled={isSaving || isPreparing || succeeded || bases.length === 0 || (!removeCompany && !selected)}
                                className="min-w-24 shadow-sm transition hover:shadow-md"
                            >
                                {isSaving ? <Loader2Icon className="size-4 animate-spin" /> : t('save')}
                            </Button>
                        </DialogFooter>
                    </form>
                </div>
            </DialogContent>
        </Dialog>
        {open && conflict && <ContactEditConflictDialog
            conflict={conflict}
            onKeepEditing={clearConflict}
            onReload={(current) => {
                setBases((previous) => previous.map((base) => base.id === current.id ? current : base));
                setSelection({ scope: selectionScope, companyId: current.companyId ?? current.company?.id ?? null });
                clearConflict();
            }}
        />}
        </>
    );
}
