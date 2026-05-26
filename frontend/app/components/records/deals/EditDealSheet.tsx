'use client';

import { useState } from 'react';
import { useRouter } from 'next/navigation';
import { toast } from 'sonner';

import QuickEditDealSheet, { type DealDraft } from '@/app/components/records/deals/QuickEditDealSheet';
import { updateDeal } from '@/app/lib/api';
import { type Company, type Deal, type Pipeline, type Stage, type UpdateDealPayload } from '@/app/lib/types';

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
    };
}

export default function EditDealSheet({
    deal,
    open,
    onOpenChange,
    companies,
    pipelines,
    stagesByPipeline,
}: {
    deal: Deal;
    open: boolean;
    onOpenChange: (open: boolean) => void;
    companies: Company[];
    pipelines: Pipeline[];
    stagesByPipeline: Record<number, Stage[]>;
}) {
    const router = useRouter();
    const [draft, setDraft] = useState<DealDraft>(() => toDraft(deal));
    const [isSaving, setIsSaving] = useState(false);

    const handleOpenChange = (next: boolean) => {
        onOpenChange(next);
        if (!next) setDraft(toDraft(deal));
    };

    const saveEdits = async () => {
        if (!draft.name.trim()) {
            toast.error('Name is required');
            return;
        }
        if (!draft.pipeline || !draft.stage) {
            toast.error('Pipeline and stage are required');
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
            };
            await updateDeal(deal.id, payload);
            toast.success('Deal updated', {
                style: { backgroundColor: 'var(--color-brand)', color: 'white' },
            });
            handleOpenChange(false);
            router.refresh();
        } catch (err) {
            toast.error(err instanceof Error ? err.message : 'Failed to save', {
                style: { backgroundColor: 'var(--color-destructive)', color: 'white' },
            });
        } finally {
            setIsSaving(false);
        }
    };

    return (
        <QuickEditDealSheet
            open={open}
            onOpenChange={handleOpenChange}
            selectedIds={new Set([deal.id])}
            selectedDeals={[deal]}
            drafts={{ [deal.id]: draft }}
            updateDraft={(_id, patch) => setDraft((prev) => ({ ...prev, ...patch }))}
            companies={companies}
            pipelines={pipelines}
            stagesByPipeline={stagesByPipeline}
            isSaving={isSaving}
            saveEdits={saveEdits}
        />
    );
}