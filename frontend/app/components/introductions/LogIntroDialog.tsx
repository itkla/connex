'use client';

import { useMemo, useState } from 'react';
import { useTranslations } from 'next-intl';

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
    DialogContent,
    DialogDescription,
    DialogFooter,
    DialogHeader,
    DialogTitle,
    DialogTrigger,
} from '@/components/ui/dialog';
import { Button } from '@/components/ui/button';
import { Label } from '@/components/ui/label';
import MentionEditor from '@/app/components/activity/notes/MentionEditor';
import type { Contact } from '@/app/lib/types';

function ContactPicker({
    id,
    label,
    placeholder,
    emptyText,
    items,
    value,
    onChange,
}: {
    id: string;
    label: string;
    placeholder: string;
    emptyText: string;
    items: Contact[];
    value: Contact | null;
    onChange: (contact: Contact | null) => void;
}) {
    return (
        <div className="flex flex-col gap-2">
            <Label htmlFor={id}>{label}</Label>
            <Combobox
                items={items}
                itemToStringLabel={(c: Contact) => c.name}
                value={value}
                onValueChange={(c) => onChange((c as Contact | null) ?? null)}
            >
                <ComboboxInput
                    id={id}
                    placeholder={placeholder}
                    className="rounded-lg border-0 bg-muted shadow-none ring-1 ring-border dark:bg-muted has-[[data-slot=input-group-control]:focus-visible]:ring-2 has-[[data-slot=input-group-control]:focus-visible]:ring-brand"
                />
                <ComboboxContent className="pointer-events-auto">
                    <ComboboxList>
                        <ComboboxEmpty>{emptyText}</ComboboxEmpty>
                        {items.map((c) => (
                            <ComboboxItem key={c.id} value={c}>
                                {c.name}
                            </ComboboxItem>
                        ))}
                    </ComboboxList>
                </ComboboxContent>
            </Combobox>
        </div>
    );
}

/**
 * Records an introduction the user made between two contacts that may not be among the suggestions,
 * so the lineage feed reflects real intros regardless of where they came from.
 */
export default function LogIntroDialog({
    contacts,
    onRecord,
    trigger,
}: {
    contacts: Contact[];
    onRecord: (personAId: number, personBId: number, note?: string) => Promise<void>;
    trigger: React.ReactNode;
}) {
    const t = useTranslations('Introductions');
    const [open, setOpen] = useState(false);
    const [personA, setPersonA] = useState<Contact | null>(null);
    const [personB, setPersonB] = useState<Contact | null>(null);
    const [note, setNote] = useState('');
    const [busy, setBusy] = useState(false);

    const candidatesB = useMemo(
        () => contacts.filter((c) => c.id !== personA?.id),
        [contacts, personA],
    );

    const reset = () => {
        setPersonA(null);
        setPersonB(null);
        setNote('');
    };

    const submit = async () => {
        if (!personA || !personB || personA.id === personB.id || busy) return;
        setBusy(true);
        try {
            await onRecord(personA.id, personB.id, note.trim() || undefined);
            setOpen(false);
            reset();
        } catch {
            // onRecord surfaces its own error toast; keep the dialog open for a retry.
        } finally {
            setBusy(false);
        }
    };

    return (
        <Dialog
            open={open}
            onOpenChange={(next) => {
                setOpen(next);
                if (!next) reset();
            }}
        >
            <DialogTrigger asChild>{trigger}</DialogTrigger>
            <DialogContent>
                <DialogHeader>
                    <DialogTitle>{t('logTitle')}</DialogTitle>
                    <DialogDescription>{t('logDescription')}</DialogDescription>
                </DialogHeader>
                <div className="flex flex-col gap-4">
                    <ContactPicker
                        id="intro-person-a"
                        label={t('personA')}
                        placeholder={t('pickContact')}
                        emptyText={t('noContacts')}
                        items={contacts}
                        value={personA}
                        onChange={setPersonA}
                    />
                    <ContactPicker
                        id="intro-person-b"
                        label={t('personB')}
                        placeholder={t('pickContact')}
                        emptyText={t('noContacts')}
                        items={candidatesB}
                        value={personB}
                        onChange={setPersonB}
                    />
                    <div className="flex flex-col gap-2">
                        <Label htmlFor="intro-note">{t('noteLabel')}</Label>
                        <MentionEditor
                            id="intro-note"
                            value={note}
                            onChange={setNote}
                            placeholder={t('notePlaceholder')}
                            className="min-h-[6rem] rounded-lg bg-muted px-3 py-2 text-sm ring-1 ring-border focus:ring-2 focus:ring-brand"
                        />
                    </div>
                </div>
                <DialogFooter>
                    <Button variant="ghost" onClick={() => setOpen(false)} disabled={busy}>
                        {t('cancel')}
                    </Button>
                    <Button
                        onClick={submit}
                        disabled={!personA || !personB || personA?.id === personB?.id || busy}
                    >
                        {t('logSubmit')}
                    </Button>
                </DialogFooter>
            </DialogContent>
        </Dialog>
    );
}
