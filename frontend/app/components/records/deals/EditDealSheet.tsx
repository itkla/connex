'use client';

import { useEffect, useRef, useState } from 'react';
import { useRouter } from 'next/navigation';
import { toast } from 'sonner';
import { toastError, toastSuccess } from '@/app/lib/toast';
import { useTranslations } from 'next-intl';

import QuickEditDealSheet, { type DealDraft } from '@/app/components/records/deals/QuickEditDealSheet';
import { CustomFieldsEditSection, type CustomFieldsEditHandle } from '@/app/components/records/CustomFieldsEditSection';
import { updateDeal } from '@/app/lib/api';
import { type Deal, type Pipeline, type Stage, type UpdateDealPayload } from '@/app/lib/types';

function toDraft(d: Deal): DealDraft {
    return {
        name: d.name ?? '',
        value: d.value ?? 0,
        actualValue: d.actualValue ?? 0,
        currency: d.currency ?? 'USD',
        pipeline: d.pipeline ?? 0,
        stage: d.stage ?? 0,
        company: d.company ?? null,
        expectedCloseDate: d.expectedCloseDate ?? '',
        closedAt: d.closedAt ?? null,
        closedReason: d.closedReason ?? null,
        won: d.won ?? null,
    };
}

export default function EditDealSheet({
    deal,
    open,
    onOpenChange,
    pipelines,
    stagesByPipeline,
}: {
    deal: Deal;
    open: boolean;
    onOpenChange: (open: boolean) => void;
    pipelines: Pipeline[];
    stagesByPipeline: Record<number, Stage[]>;
}) {
    const router = useRouter();
    const t = useTranslations('DealsEditSheet');
    const [draft, setDraft] = useState<DealDraft>(() => toDraft(deal));
    const [isSaving, setIsSaving] = useState(false);
    const cfRef = useRef<CustomFieldsEditHandle>(null);

    const wasOpen = useRef(open);
    useEffect(() => {
        if (open && !wasOpen.current) setDraft(toDraft(deal));
        wasOpen.current = open;
    }, [open, deal]);

    const saveEdits = async () => {
        if (!draft.name.trim()) {
            toast.error(t('nameRequired'));
            return;
        }
        if (!draft.pipeline || !draft.stage) {
            toast.error(t('pipelineStageRequired'));
            return;
        }

        setIsSaving(true);
        try {
            const payload: UpdateDealPayload = {
                name: draft.name.trim(),
                value: draft.value,
                actualValue: draft.actualValue,
                currency: draft.currency.trim() || 'USD',
                pipeline: draft.pipeline,
                stage: draft.stage,
                company: draft.company,
                expectedCloseDate: draft.expectedCloseDate || null,
                closedAt: draft.closedAt || null,
                closedReason: draft.closedReason || null,
                won: draft.won,
            };
            await updateDeal(deal.id, payload);
            await cfRef.current?.save();
            toastSuccess(t('dealUpdated'));
            onOpenChange(false);
            router.refresh();
        } catch (err) {
            toastError(err instanceof Error ? err.message : t('failedToSave'));
        } finally {
            setIsSaving(false);
        }
    };

    return (
        <QuickEditDealSheet
            open={open}
            onOpenChange={onOpenChange}
            selectedIds={new Set([deal.id])}
            selectedDeals={[deal]}
            drafts={{ [deal.id]: draft }}
            updateDraft={(_id, patch) => setDraft((prev) => ({ ...prev, ...patch }))}
            pipelines={pipelines}
            stagesByPipeline={stagesByPipeline}
            isSaving={isSaving}
            saveEdits={saveEdits}
            customFieldsSlot={<CustomFieldsEditSection ref={cfRef} entityType="deal" entityId={deal.id} />}
        />
    );
}
