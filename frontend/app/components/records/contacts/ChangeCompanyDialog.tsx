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

type Props = {
    open: boolean;
    onOpenChange: (open: boolean) => void;
    contacts: Contact[];
    companies: Company[];
    onSuccess?: () => void;
};

export default function ChangeCompanyDialog({ open, onOpenChange, contacts, companies, onSuccess }: Props) {
    const router = useRouter();
    const t = useTranslations('ContactsChangeCompanyDialog');
    const [selected, setSelected] = useState<Company | null>(null);
    const [isSaving, setIsSaving] = useState(false);
    const [succeeded, setSucceeded] = useState(false);

    useEffect(() => {
        if (!open) return;
        setSucceeded(false);
        if (contacts.length === 1) {
            const current = companies.find((c) => c.id === contacts[0].companyId) ?? null;
            setSelected(current);
        } else {
            setSelected(null);
        }
    }, [open, contacts, companies]);

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
                imageUrl: c.imageUrl || undefined,
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
            setTimeout(() => onOpenChange(false), 900);
        } catch (err) {
            toastError(err instanceof Error ? err.message : t('toastFailedUpdate'));
        } finally {
            setIsSaving(false);
        }
    };

    const status = resolveDialogStatus({ isLoading: isSaving, isSuccess: succeeded });

    const handleOpenChange = (next: boolean) => {
        if (!next && isSaving) return;
        if (!next) setSucceeded(false);
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
                                items={companies}
                                itemToStringLabel={(c: Company) => c.name}
                                value={selected}
                                onValueChange={(c) => setSelected((c as Company | null) ?? null)}
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
                                        {companies.map((company) => (
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
                                disabled={isSaving || succeeded || !selected}
                                className="min-w-24 bg-brand text-white shadow-sm transition hover:bg-brand-hover hover:shadow-md"
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