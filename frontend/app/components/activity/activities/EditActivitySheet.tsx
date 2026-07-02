'use client';

import { useState } from 'react';
import { useRouter } from 'next/navigation';
import { useTranslations } from 'next-intl';
import { toast } from 'sonner';
import { toastError, toastSuccess } from '@/app/lib/toast';
import { Loader2Icon } from 'lucide-react';
import { ChatBubbleLeftRightIcon } from '@heroicons/react/24/outline';

import {
    Sheet,
    SheetContent,
    SheetHeader,
    SheetTitle,
    SheetDescription,
    SheetFooter,
    SheetClose,
} from '@/components/ui/sheet';
import { Button } from '@/components/ui/button';
import { Label } from '@/components/ui/label';
import MentionEditor from '@/app/components/activity/notes/MentionEditor';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';

import { ApiError, updateActivity } from '@/app/lib/api';
import { ActivityTypePicker, normalizeType, type ActivityType } from '@/app/components/activity/activities/activityTypes';
import { type Activity, type Contact, type Deal, type UpdateActivityPayload } from '@/app/lib/types';
import { parseMysqlDateTime, toDatetimeLocalValue, toMysqlDateTime } from '@/app/lib/utils';

const inputClass =
    'w-full rounded-lg bg-muted px-3 py-2 text-sm text-foreground placeholder:text-muted-foreground outline-none ring-1 ring-border transition focus:ring-2 focus:ring-brand';

const NONE_VALUE = 'none';

type ActivityDraft = {
    type: ActivityType;
    subject: string;
    notes: string;
    timestamp: string;
    personId: number | null;
    dealId: number | null;
};

// function toDatetimeLocalValue(value?: string | null): string {
//     if (!value) return '';
//     const ts = parseMysqlDateTime(value);
//     if (Number.isNaN(ts)) return '';
//     const d = new Date(ts);
//     const pad = (n: number) => String(n).padStart(2, '0');
//     return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`;
// }

// function normalizeType(value?: string | null): string {
//     if (!value) return ACTIVITY_TYPES[0];
//     const match = ACTIVITY_TYPES.find((t) => t.toLowerCase() === value.toLowerCase());
//     return match ?? ACTIVITY_TYPES[0];
// }

function toDraft(a: Activity): ActivityDraft {
    return {
        type: normalizeType(a.type),
        subject: a.subject ?? '',
        notes: a.notes ?? '',
        timestamp: toDatetimeLocalValue(a.timestamp),
        personId: a.personId ?? null,
        dealId: a.dealId ?? null,
    };
}

export default function EditActivitySheet({
    activity,
    open,
    onOpenChange,
    persons,
    deals,
}: {
    activity: Activity;
    open: boolean;
    onOpenChange: (open: boolean) => void;
    persons: Contact[];
    deals: Deal[];
}) {
    const router = useRouter();
    const t = useTranslations('ActivityEditActivitySheet');
    const [draft, setDraft] = useState<ActivityDraft>(() => toDraft(activity));
    const [isSaving, setIsSaving] = useState(false);

    const handleOpenChange = (next: boolean) => {
        onOpenChange(next);
        if (!next) setDraft(toDraft(activity));
    };

    const saveUpdates = async () => {
        if (!draft.subject.trim()) {
            toast.error(t('subjectRequired'));
            return;
        }
        setIsSaving(true);
        try {
            const payload: UpdateActivityPayload = {
                type: draft.type,
                subject: draft.subject.trim(),
                createdById: activity.createdById,
                notes: draft.notes.trim() || undefined,
                timestamp: draft.timestamp ? toMysqlDateTime(draft.timestamp) : undefined,
                personId: draft.personId,
                dealId: draft.dealId,
            };
            await updateActivity(activity.id, payload);
            toastSuccess(t('activityUpdated'));
            handleOpenChange(false);
            router.refresh();
        } catch (err) {
            const message = err instanceof ApiError ? err.message : err instanceof Error ? err.message : t('updateFailed');
            toastError(message);
        } finally {
            setIsSaving(false);
        }
    };

    return (
        <Sheet open={open} onOpenChange={handleOpenChange}>
            <SheetContent side="right" className="flex w-full flex-col sm:max-w-lg">
                <SheetHeader className="border-b">
                    <div className="flex items-start gap-3">
                        <span className="flex size-9 shrink-0 items-center justify-center rounded-xl bg-brand-light text-brand-dark">
                            <ChatBubbleLeftRightIcon className="size-5" />
                        </span>
                        <div className="space-y-1">
                            <SheetTitle>{t('title')}</SheetTitle>
                            <SheetDescription>{t('description')}</SheetDescription>
                        </div>
                    </div>
                </SheetHeader>

                <div className="flex-1 overflow-y-auto px-4 py-2">
                    <div className="grid gap-4 pt-6">
                        <div className="grid gap-1.5">
                            <Label>{t('typeLabel')}</Label>
                            <ActivityTypePicker
                                value={draft.type}
                                onChange={(value) => setDraft((d) => ({ ...d, type: value }))}
                                getLabel={(ty) => t(`type${ty}` as 'typeCall')}
                            />
                        </div>

                        <div className="grid gap-1.5">
                            <Label htmlFor="activity-subject">{t('subjectLabel')}</Label>
                            <input
                                id="activity-subject"
                                type="text"
                                value={draft.subject}
                                onChange={(e) => setDraft((d) => ({ ...d, subject: e.target.value }))}
                                className={inputClass}
                                required
                                autoFocus
                            />
                        </div>

                        <div className="grid gap-1.5">
                            <Label htmlFor="activity-timestamp">{t('timestampLabel')}</Label>
                            <input
                                id="activity-timestamp"
                                type="datetime-local"
                                value={draft.timestamp}
                                onChange={(e) => setDraft((d) => ({ ...d, timestamp: e.target.value }))}
                                className={inputClass}
                            />
                        </div>

                        <div className="grid gap-1.5">
                            <Label htmlFor="activity-person">{t('personLabel')}</Label>
                            <Select
                                value={draft.personId != null ? draft.personId.toString() : NONE_VALUE}
                                onValueChange={(value) =>
                                    setDraft((d) => ({ ...d, personId: value === NONE_VALUE ? null : Number(value) }))
                                }
                            >
                                <SelectTrigger id="activity-person" className={inputClass}>
                                    <SelectValue placeholder={t('selectPersonPlaceholder')} />
                                </SelectTrigger>
                                <SelectContent>
                                    <SelectItem value={NONE_VALUE}>{t('noPerson')}</SelectItem>
                                    {persons.map((person) => (
                                        <SelectItem key={person.id} value={person.id.toString()}>
                                            {person.name}
                                        </SelectItem>
                                    ))}
                                </SelectContent>
                            </Select>
                        </div>

                        <div className="grid gap-1.5">
                            <Label htmlFor="activity-deal">{t('dealLabel')}</Label>
                            <Select
                                value={draft.dealId != null ? draft.dealId.toString() : NONE_VALUE}
                                onValueChange={(value) =>
                                    setDraft((d) => ({ ...d, dealId: value === NONE_VALUE ? null : Number(value) }))
                                }
                            >
                                <SelectTrigger id="activity-deal" className={inputClass}>
                                    <SelectValue placeholder={t('selectDealPlaceholder')} />
                                </SelectTrigger>
                                <SelectContent>
                                    <SelectItem value={NONE_VALUE}>{t('noDeal')}</SelectItem>
                                    {deals.map((deal) => (
                                        <SelectItem key={deal.id} value={deal.id.toString()}>
                                            {deal.name}
                                        </SelectItem>
                                    ))}
                                </SelectContent>
                            </Select>
                        </div>

                        <div className="grid gap-1.5">
                            <Label htmlFor="activity-notes">{t('notesLabel')}</Label>
                            <MentionEditor
                                id="activity-notes"
                                value={draft.notes}
                                onChange={(value) => setDraft((d) => ({ ...d, notes: value }))}
                                className={inputClass}
                            />
                        </div>
                    </div>
                </div>

                <SheetFooter className="border-t">
                    <SheetClose asChild>
                        <Button variant="outline" disabled={isSaving}>
                            {t('cancel')}
                        </Button>
                    </SheetClose>
                    <Button
                        onClick={saveUpdates}
                        disabled={isSaving}
                        className="bg-brand text-white transition-transform hover:bg-brand-dark active:scale-[0.98]"
                    >
                        {isSaving ? <Loader2Icon className="size-4 animate-spin" /> : t('save')}
                    </Button>
                </SheetFooter>
            </SheetContent>
        </Sheet>
    );
}