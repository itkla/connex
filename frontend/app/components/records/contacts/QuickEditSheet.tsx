'use client';

import { type ReactNode, useState } from 'react';
import { useTranslations } from 'next-intl';
import { UserIcon } from '@heroicons/react/24/outline';

import { Input } from '@/components/ui/input';
import { type Contact } from '@/app/lib/types';
import type { SelectionId } from '@/app/components/records/types';
import ContactAvatar from '@/app/components/records/contacts/ContactAvatar';
import { toastError } from '@/app/lib/toast';
import {
    QuickEditField,
    QuickEditMediaUpload,
    QuickEditRecordCard,
    QuickEditSheetShell,
} from '@/app/components/records/quick-edit/QuickEditSheetShell';

export type ContactDraft = {
    name: string;
    email: string;
    phone: string;
    title: string;
};

type Props = {
    editSheetOpen: boolean;
    setEditSheetOpen: (open: boolean) => void;
    selectedIds: Set<SelectionId>;
    selectedContacts: Contact[];
    drafts: Record<number, ContactDraft>;
    updateDraft: (id: number, patch: Partial<ContactDraft>) => void;
    imageFiles?: Record<number, File | null>;
    updateImageFile?: (id: number, file: File | null) => void;
    isSaving: boolean;
    saveEdits: () => void;
    customFieldsSlot?: ReactNode;
};

export default function QuickEditSheet({
    editSheetOpen,
    setEditSheetOpen,
    selectedContacts,
    drafts,
    updateDraft,
    imageFiles,
    updateImageFile,
    isSaving,
    saveEdits,
    customFieldsSlot,
}: Props) {
    const t = useTranslations('ContactsQuickEditSheet');
    const total = selectedContacts.length;
    const [pendingMediaIds, setPendingMediaIds] = useState<Set<number>>(new Set());
    const mediaPending = pendingMediaIds.size > 0;

    const setMediaPending = (id: number, pending: boolean) => {
        setPendingMediaIds((current) => {
            const next = new Set(current);
            if (pending) next.add(id);
            else next.delete(id);
            return next;
        });
    };

    return (
        <QuickEditSheetShell
            open={editSheetOpen}
            onOpenChange={(next) => {
                if (!next && mediaPending) return;
                setEditSheetOpen(next);
            }}
            icon={<UserIcon />}
            title={total === 1 ? t('titleSingle') : t('titleMultiple', { count: total })}
            description={t('description')}
            count={total}
            isSaving={isSaving}
            interactionPending={mediaPending}
            onSave={() => {
                if (!mediaPending) saveEdits();
            }}
            saveLabel={t('save')}
            cancelLabel={t('cancel')}
        >
            {selectedContacts.map((c, idx) => {
                const draft = drafts[c.id];
                if (!draft) return null;
                const pendingImage = imageFiles?.[c.id] ?? null;
                const media = updateImageFile ? (
                    <QuickEditMediaUpload
                        id={`pfp-${c.id}`}
                        label={t('changePhoto')}
                        shape="round"
                        file={pendingImage}
                        existingUrl={c.imageUrl ?? null}
                        fallback={
                            <div className="flex h-full w-full items-center justify-center bg-muted-foreground/40">
                                <UserIcon className="size-8 text-muted-foreground" />
                            </div>
                        }
                        onSelect={(file) => updateImageFile(c.id, file)}
                        onInvalidSelect={() => toastError(t('unsupportedImage'))}
                        onPendingChange={(pending) => setMediaPending(c.id, pending)}
                        active={editSheetOpen}
                        disabled={isSaving}
                    />
                ) : (
                    <ContactAvatar contact={c} type="large" />
                );

                return (
                    <QuickEditRecordCard
                        key={c.id}
                        index={idx}
                        total={total}
                        media={media}
                        title={c.name}
                        subtitle={draft.title || draft.email || undefined}
                    >
                        <QuickEditField label={t('name')} htmlFor={`name-${c.id}`} required>
                            <Input
                                id={`name-${c.id}`}
                                type="text"
                                value={draft.name}
                                onChange={(e) => updateDraft(c.id, { name: e.target.value })}
                                required
                            />
                        </QuickEditField>
                        <QuickEditField label={t('email')} htmlFor={`email-${c.id}`}>
                            <Input
                                id={`email-${c.id}`}
                                type="email"
                                value={draft.email}
                                onChange={(e) => updateDraft(c.id, { email: e.target.value })}
                            />
                        </QuickEditField>
                        <QuickEditField label={t('phone')} htmlFor={`phone-${c.id}`}>
                            <Input
                                id={`phone-${c.id}`}
                                type="tel"
                                value={draft.phone}
                                onChange={(e) => updateDraft(c.id, { phone: e.target.value })}
                            />
                        </QuickEditField>
                        <QuickEditField label={t('title')} htmlFor={`title-${c.id}`}>
                            <Input
                                id={`title-${c.id}`}
                                type="text"
                                value={draft.title}
                                onChange={(e) => updateDraft(c.id, { title: e.target.value })}
                            />
                        </QuickEditField>
                    </QuickEditRecordCard>
                );
            })}
            {customFieldsSlot}
        </QuickEditSheetShell>
    );
}
