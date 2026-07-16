'use client';

import { useEffect, useState, type WheelEvent } from 'react';
import { useRouter } from 'next/navigation';
import { useTranslations } from 'next-intl';
import { toast } from 'sonner';
import { toastError, toastSuccess } from '@/app/lib/toast';
import { Loader2Icon } from 'lucide-react';

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
import { updateContact } from '@/app/lib/api';
import { type Company, type Contact } from '@/app/lib/types';
import { useCompanySearch } from '@/app/hooks/useCompanySearch';

type Props = {
    open: boolean;
    onOpenChange: (open: boolean) => void;
    contacts: Contact[];
    onSuccess?: () => void;
};

export default function ChangeCompanyDialog({ open, onOpenChange, contacts, onSuccess }: Props) {
    const router = useRouter();
    const t = useTranslations('ContactsChangeCompanyDialog');
    const defaultCompanyId = contacts.length === 1
        ? contacts[0].companyId ?? contacts[0].company?.id ?? null
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
    const currentCompany = contacts.length === 1 ? contacts[0].company ?? null : null;
    const companySearch = useCompanySearch(
        open,
        [defaultCompanyId, selectedCompanyId],
        currentCompany ? [currentCompany] : [],
    );
    const selected = companySearch.companies.find(
        (company) => company.id === selectedCompanyId,
    ) ?? (currentCompany?.id === selectedCompanyId ? currentCompany : null);

    if (open !== prevOpen) {
        setPrevOpen(open);
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
        if (!selected) {
            toast.error(t('toastPickCompany'));
            return;
        }
        setIsSaving(true);
        try {
            await Promise.all(contacts.map((c) => updateContact(c.id, {
                name: c.name,
                email: c.email || undefined,
                phone: c.phone || undefined,
                title: c.title || undefined,
                companyId: selected.id,
            })));
            toastSuccess(
                contacts.length === 1
                    ? t('toastAssociatedSingle', { contactName: contacts[0].name, companyName: selected.name })
                    : t('toastAssociatedMultiple', { count: contacts.length, companyName: selected.name }),
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
            toastError(err instanceof Error ? err.message : t('toastFailedUpdate'));
        } finally {
            setIsSaving(false);
        }
    };

    const status = resolveDialogStatus({ isLoading: isSaving, isSuccess: succeeded });

    const handleOpenChange = (next: boolean) => {
        if (!next && isSaving) return;
        if (!next) {
            setSucceeded(false);
            setSelection({ scope: selectionScope, companyId: defaultCompanyId });
        }
        onOpenChange(next);
    };

    return (
        <Dialog open={open} onOpenChange={handleOpenChange}>
            <DialogContent className="gap-0 overflow-hidden p-0 sm:max-w-lg">
                <DialogStatusCover status={status} />

                <div className="px-6 pb-6">
                    <DialogHeader className="ncd-rise -mt-12 mb-5" style={{ animationDelay: '40ms' }}>
                        <DialogTitle className="text-xl font-semibold tracking-tight">
                            {contacts.length === 1 ? t('titleSingle') : t('titleMultiple', { count: contacts.length })}
                        </DialogTitle>
                        <DialogDescription>
                            {contacts.length === 1
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
                        <div className="ncd-rise grid gap-2" style={{ animationDelay: '90ms' }}>
                            <Label htmlFor="company">{t('companyLabel')}</Label>
                            <Combobox
                                items={companySearch.companies}
                                filter={null}
                                itemToStringLabel={(c: Company) => c.name}
                                value={selected}
                                onInputValueChange={companySearch.onInputValueChange}
                                onValueChange={(c) => setSelection({
                                    scope: selectionScope,
                                    companyId: (c as Company | null)?.id ?? null,
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
                                    <ComboboxList onWheel={handleListWheel}>
                                        <ComboboxEmpty>{t('noCompaniesFound')}</ComboboxEmpty>
                                        {companySearch.companies.map((company) => (
                                            <ComboboxItem key={company.id} value={company}>
                                                {company.name}
                                            </ComboboxItem>
                                        ))}
                                    </ComboboxList>
                                </ComboboxContent>
                            </Combobox>
                        </div>

                        <DialogFooter className="ncd-rise mt-5" style={{ animationDelay: '140ms' }}>
                            <DialogClose asChild>
                                <Button type="button" variant="outline" disabled={isSaving}>{t('cancel')}</Button>
                            </DialogClose>
                            <Button
                                type="submit"
                                variant="brand"
                                disabled={isSaving || succeeded || !selected}
                                className="min-w-24 shadow-sm transition hover:shadow-md"
                            >
                                {isSaving ? <Loader2Icon className="size-4 animate-spin" /> : t('save')}
                            </Button>
                        </DialogFooter>
                    </form>
                </div>
            </DialogContent>
        </Dialog>
    );
}
