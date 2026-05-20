'use client';

import { useEffect, useState, type WheelEvent } from 'react';
import { useRouter } from 'next/navigation';
import { toast } from 'sonner';
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
            toast.error('Pick a company');
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
            toast.success(
                contacts.length === 1
                    ? `${contacts[0].name} associated with ${selected.name}`
                    : `${contacts.length} contacts associated with ${selected.name}`,
                { style: { backgroundColor: 'var(--color-brand)', color: 'white' } },
            );
            onOpenChange(false);
            onSuccess?.();
            router.refresh();
        } catch (err) {
            toast.error(err instanceof Error ? err.message : 'Failed to update company', {
                style: { backgroundColor: 'var(--color-destructive)', color: 'white' },
            });
        } finally {
            setIsSaving(false);
        }
    };

    return (
        <Dialog open={open} onOpenChange={onOpenChange}>
            <DialogContent>
                <DialogHeader>
                    <DialogTitle>
                        {contacts.length === 1 ? 'Change company' : `Change company for ${contacts.length} contacts`}
                    </DialogTitle>
                    <DialogDescription>
                        {contacts.length === 1
                            ? `Associate ${contacts[0].name} with a company.`
                            : 'All selected contacts will be associated with the chosen company.'}
                    </DialogDescription>
                </DialogHeader>

                <div className="grid gap-2">
                    <Label htmlFor="company">Company</Label>
                    <Combobox
                        items={companies}
                        itemToStringLabel={(c: Company) => c.name}
                        value={selected}
                        onValueChange={(c) => setSelected((c as Company | null) ?? null)}
                    >
                        <ComboboxInput id="company" placeholder="Select company" className="ring-1 ring-black/5" />
                        <ComboboxContent className="pointer-events-auto">
                            <ComboboxList onWheel={handleListWheel}>
                                <ComboboxEmpty>No companies found.</ComboboxEmpty>
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
                        <Button variant="outline" disabled={isSaving}>Cancel</Button>
                    </DialogClose>
                    <Button
                        onClick={handleSave}
                        disabled={isSaving || !selected}
                        className="bg-brand text-white hover:bg-brand-dark"
                    >
                        {isSaving ? <Loader2Icon className="size-4 animate-spin" /> : 'Save'}
                    </Button>
                </DialogFooter>
            </DialogContent>
        </Dialog>
    );
}