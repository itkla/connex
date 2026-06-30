'use client';

import { type WheelEvent } from 'react';
import { useTranslations } from 'next-intl';
import { DocumentTextIcon } from '@heroicons/react/24/outline';

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
import {
    QuickEditField,
    QuickEditRecordCard,
    QuickEditSheetShell,
} from '@/app/components/records/quick-edit/QuickEditSheetShell';

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
    selectedNotes,
    drafts,
    updateDraft,
    persons,
    deals,
    isSaving,
    saveEdits,
}: Props) {
    const t = useTranslations('NotesQuickEditSheet');
    const total = selectedNotes.length;

    const handleListWheel = (e: WheelEvent<HTMLDivElement>) => {
        const lineHeightPx = 16;
        const delta = e.deltaMode === 1 ? e.deltaY * lineHeightPx : e.deltaY;
        e.currentTarget.scrollTop += delta;
    };

    return (
        <QuickEditSheetShell
            open={open}
            onOpenChange={onOpenChange}
            icon={<DocumentTextIcon />}
            title={total === 1 ? t('titleSingle') : t('titleMultiple', { count: total })}
            description={t('description')}
            count={total}
            isSaving={isSaving}
            onSave={saveEdits}
            saveLabel={t('save')}
            cancelLabel={t('cancel')}
        >
            {selectedNotes.map((note, idx) => {
                const draft = drafts[note.id];
                if (!draft) return null;
                const selectedPerson = draft.person != null ? persons.find((p) => p.id === draft.person) ?? null : null;
                const selectedDeal = draft.deal != null ? deals.find((d) => d.id === draft.deal) ?? null : null;
                const title =
                    total > 1
                        ? draft.content.trim().replace(/\s+/g, ' ').slice(0, 60) || t('noteIndex', { index: idx + 1 })
                        : undefined;
                const subtitle = total > 1 ? selectedPerson?.name ?? selectedDeal?.name ?? undefined : undefined;

                return (
                    <QuickEditRecordCard key={note.id} index={idx} total={total} title={title} subtitle={subtitle}>
                        <QuickEditField label={t('contentLabel')} htmlFor={`content-${note.id}`} required>
                            <Textarea
                                id={`content-${note.id}`}
                                value={draft.content}
                                onChange={(e) => updateDraft(note.id, { content: e.target.value })}
                                rows={4}
                                required
                            />
                        </QuickEditField>
                        <div className="grid grid-cols-1 gap-3 md:grid-cols-2">
                            <QuickEditField label={t('personLabel')} htmlFor={`person-${note.id}`}>
                                <Combobox
                                    items={persons}
                                    itemToStringLabel={(p: Contact) => p.name}
                                    value={selectedPerson}
                                    onValueChange={(p) => updateDraft(note.id, { person: (p as Contact | null)?.id ?? null })}
                                >
                                    <ComboboxInput id={`person-${note.id}`} placeholder={t('personPlaceholder')} showClear />
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
                            </QuickEditField>
                            <QuickEditField label={t('dealLabel')} htmlFor={`deal-${note.id}`}>
                                <Combobox
                                    items={deals}
                                    itemToStringLabel={(d: Deal) => d.name}
                                    value={selectedDeal}
                                    onValueChange={(d) => updateDraft(note.id, { deal: (d as Deal | null)?.id ?? null })}
                                >
                                    <ComboboxInput id={`deal-${note.id}`} placeholder={t('dealPlaceholder')} showClear />
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
                            </QuickEditField>
                        </div>
                    </QuickEditRecordCard>
                );
            })}
        </QuickEditSheetShell>
    );
}
