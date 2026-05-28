'use client';

import { useTranslations } from 'next-intl';
import { Loader2Icon } from 'lucide-react';
import { type WheelEvent } from 'react';

import {
    Sheet,
    SheetContent,
    SheetDescription,
    SheetHeader,
    SheetTitle,
    SheetFooter,
    SheetClose,
} from '@/components/ui/sheet';
import { Button } from '@/components/ui/button';
import { Label } from '@/components/ui/label';
import { Textarea } from '@/components/ui/textarea';
import {
    Combobox,
    ComboboxContent,
    ComboboxEmpty,
    ComboboxInput,
    ComboboxItem,
    ComboboxList,
} from '@/components/ui/combobox';

import type { Contact, Deal, Note, NoteDraft } from '@/app/lib/types';
import type { SelectionId } from '@/app/components/records/types';

type Props = {
    open: boolean;
    onOpenChange: (open: boolean) => void;
    selectedIds: Set<SelectionId>;
    selectedNotes: Note[];
    drafts: Record<number, NoteDraft>;
    updateDraft: (id: number, patch: Partial<NoteDraft>) => void;
    persons: Contact[];
    deals: Deal[];
    isSaving: boolean;
    saveEdits: () => void;
};

export default function QuickEditNoteSheet({
    open,
    onOpenChange,
    selectedIds,
    selectedNotes,
    drafts,
    updateDraft,
    persons,
    deals,
    isSaving,
    saveEdits,
}: Props) {
    const t = useTranslations('NotesQuickEditSheet');

    const handleListWheel = (e: WheelEvent<HTMLDivElement>) => {
        const lineHeightPx = 16;
        const delta = e.deltaMode === 1 ? e.deltaY * lineHeightPx : e.deltaY;
        e.currentTarget.scrollTop += delta;
    };

    return (
        <Sheet open={open} onOpenChange={onOpenChange}>
            <SheetContent side="right" className="flex w-full flex-col sm:max-w-lg">
                <SheetHeader className="border-b">
                    <SheetTitle>
                        {selectedIds.size === 1
                            ? t('titleSingle')
                            : t('titleMultiple', { count: selectedIds.size })}
                    </SheetTitle>
                    <SheetDescription>{t('description')}</SheetDescription>
                </SheetHeader>

                <div className="flex-1 overflow-y-auto px-4 py-2">
                    <div className="flex flex-col gap-6">
                        {selectedNotes.map((note, idx) => {
                            const draft = drafts[note.id];
                            if (!draft) return null;
                            const selectedPerson =
                                draft.person != null
                                    ? persons.find((p) => p.id === draft.person) ?? null
                                    : null;
                            const selectedDeal =
                                draft.deal != null
                                    ? deals.find((d) => d.id === draft.deal) ?? null
                                    : null;
                            return (
                                <div key={note.id} className={idx > 0 ? 'border-t pt-6' : ''}>
                                    <div className="mb-3 text-xs font-medium tracking-[0.12em] text-neutral-500 uppercase">
                                        {t('noteIndex', { index: idx + 1 })}
                                    </div>
                                    <div className="grid gap-3">
                                        <div className="grid gap-1.5">
                                            <Label htmlFor={`content-${note.id}`}>{t('contentLabel')}</Label>
                                            <Textarea
                                                id={`content-${note.id}`}
                                                value={draft.content}
                                                onChange={(e) => updateDraft(note.id, { content: e.target.value })}
                                                rows={4}
                                                required
                                            />
                                        </div>
                                        <div className="grid grid-cols-1 gap-3 md:grid-cols-2">
                                            <div className="grid gap-1.5">
                                                <Label htmlFor={`person-${note.id}`}>{t('personLabel')}</Label>
                                                <Combobox
                                                    items={persons}
                                                    itemToStringLabel={(p: Contact) => p.name}
                                                    value={selectedPerson}
                                                    onValueChange={(p) =>
                                                        updateDraft(note.id, {
                                                            person: (p as Contact | null)?.id ?? null,
                                                        })
                                                    }
                                                >
                                                    <ComboboxInput
                                                        id={`person-${note.id}`}
                                                        placeholder={t('personPlaceholder')}
                                                        className="ring-1 ring-black/5"
                                                    />
                                                    <ComboboxContent className="pointer-events-auto">
                                                        <ComboboxList onWheel={handleListWheel}>
                                                            <ComboboxEmpty>{t('noPersonFound')}</ComboboxEmpty>
                                                            {persons.map((p) => (
                                                                <ComboboxItem key={p.id} value={p}>
                                                                    {p.name}
                                                                </ComboboxItem>
                                                            ))}
                                                        </ComboboxList>
                                                    </ComboboxContent>
                                                </Combobox>
                                            </div>
                                            <div className="grid gap-1.5">
                                                <Label htmlFor={`deal-${note.id}`}>{t('dealLabel')}</Label>
                                                <Combobox
                                                    items={deals}
                                                    itemToStringLabel={(d: Deal) => d.name}
                                                    value={selectedDeal}
                                                    onValueChange={(d) =>
                                                        updateDraft(note.id, {
                                                            deal: (d as Deal | null)?.id ?? null,
                                                        })
                                                    }
                                                >
                                                    <ComboboxInput
                                                        id={`deal-${note.id}`}
                                                        placeholder={t('dealPlaceholder')}
                                                        className="ring-1 ring-black/5"
                                                    />
                                                    <ComboboxContent className="pointer-events-auto">
                                                        <ComboboxList onWheel={handleListWheel}>
                                                            <ComboboxEmpty>{t('noDealFound')}</ComboboxEmpty>
                                                            {deals.map((d) => (
                                                                <ComboboxItem key={d.id} value={d}>
                                                                    {d.name}
                                                                </ComboboxItem>
                                                            ))}
                                                        </ComboboxList>
                                                    </ComboboxContent>
                                                </Combobox>
                                            </div>
                                        </div>
                                    </div>
                                </div>
                            );
                        })}
                    </div>
                </div>

                <SheetFooter className="border-t">
                    <SheetClose asChild>
                        <Button variant="outline" disabled={isSaving}>
                            {t('cancel')}
                        </Button>
                    </SheetClose>
                    <Button
                        onClick={saveEdits}
                        disabled={isSaving}
                        className="bg-brand text-white hover:bg-brand-dark"
                    >
                        {isSaving ? <Loader2Icon className="size-4 animate-spin" /> : t('save')}
                    </Button>
                </SheetFooter>
            </SheetContent>
        </Sheet>
    );
}