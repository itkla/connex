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

    useEffect(() => {
        if (!open) return;
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
            onOpenChange(false);
            onSuccess?.();
            router.refresh();
        } catch (err) {
            toastError(err instanceof Error ? err.message : t('toastFailedUpdate'));
        } finally {
            setIsSaving(false);
        }
    };

    return (
        <Dialog open={open} onOpenChange={onOpenChange}>
            <DialogContent>
                <DialogHeader>
                    <DialogTitle>
                        {contacts.length === 1 ? t('titleSingle') : t('titleMultiple', { count: contacts.length })}
                    </DialogTitle>
                    <DialogDescription>
                        {contacts.length === 1
                            ? t('descriptionSingle', { name: contacts[0].name })
                            : t('descriptionMultiple')}
                    </DialogDescription>
                </DialogHeader>

                <div className="grid gap-2">
                    <Label htmlFor="company">{t('companyLabel')}</Label>
                    <Combobox
                        items={companies}
                        itemToStringLabel={(c: Company) => c.name}
                        value={selected}
                        onValueChange={(c) => setSelected((c as Company | null) ?? null)}
                    >
                        <ComboboxInput id="company" placeholder={t('selectCompanyPlaceholder')} className="ring-1 ring-border" />
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

                <DialogFooter>
                    <DialogClose asChild>
                        <Button variant="outline" disabled={isSaving}>{t('cancel')}</Button>
                    </DialogClose>
                    <Button
                        onClick={handleSave}
                        disabled={isSaving || !selected}
                        className="bg-brand text-white hover:bg-brand-dark"
                    >
                        {isSaving ? <Loader2Icon className="size-4 animate-spin" /> : t('save')}
                    </Button>
                </DialogFooter>
            </DialogContent>
        </Dialog>
    );
}