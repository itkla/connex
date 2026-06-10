'use client';

import { Dispatch, SetStateAction, WheelEvent, useEffect } from 'react';
import { useTranslations } from 'next-intl';
import { useFieldErrors } from '@/app/hooks/useFieldErrors';
import { Dialog, DialogContent, DialogHeader, DialogTitle, DialogDescription, DialogFooter, DialogClose } from '@/components/ui/dialog';
import { Button } from '@/components/ui/button';
import { Loader2Icon } from 'lucide-react';
import { Combobox, ComboboxItem, ComboboxList, ComboboxContent, ComboboxEmpty, ComboboxInput } from '@/components/ui/combobox';
import { Label } from '@/components/ui/label';
import { type Company, type CreateDealPayload, type Pipeline, type Stage } from '@/app/lib/types';

const inputClass = 'w-full rounded-lg bg-neutral-100 px-3 py-2 text-sm text-black placeholder-neutral-500 outline-none ring-1 ring-black/5 transition focus:ring-2 focus:ring-brand';

type Props = {
    open: boolean;
    onOpenChange: (open: boolean) => void;
    payload: CreateDealPayload;
    setPayload: Dispatch<SetStateAction<CreateDealPayload>>;
    companies: Company[];
    pipelines: Pipeline[];
    stagesByPipeline: Record<number, Stage[]>;
    isCreating: boolean;
    createNewDeal: () => void | Promise<void>;
};

export default function NewDealDialog({
    open,
    onOpenChange,
    payload,
    setPayload,
    companies,
    pipelines,
    stagesByPipeline,
    isCreating,
    createNewDeal,
}: Props) {
    const t = useTranslations('DealsNewDialog');
    const { fieldErrors, reset: resetFieldErrors, clearError, captureFieldErrors } = useFieldErrors();

    useEffect(() => {
        if (!open) resetFieldErrors();
    }, [open, resetFieldErrors]);

    const handleCreate = async () => {
        resetFieldErrors();
        try {
            await createNewDeal();
        } catch (err) {
            captureFieldErrors(err);
        }
    };

    const handleListWheel = (e: WheelEvent<HTMLDivElement>) => {
        const lineHeightPx = 16;
        const delta = e.deltaMode === 1 ? e.deltaY * lineHeightPx : e.deltaY;
        e.currentTarget.scrollTop += delta;
    };

    const selectedPipeline = pipelines.find((p) => p.id === payload.pipeline) ?? null;
    const selectedCompany = companies.find((c) => c.id === payload.company) ?? null;
    const stages = payload.pipeline ? stagesByPipeline[payload.pipeline] ?? [] : [];
    const selectedStage = stages.find((s) => s.id === payload.stage) ?? null;
    const actualValue = Number.isFinite(payload.actualValue) ? payload.actualValue : 0;

    return (
        <Dialog open={open} onOpenChange={onOpenChange}>
            <DialogContent>
                <DialogHeader>
                    <DialogTitle>{t('title')}</DialogTitle>
                    <DialogDescription>
                        {t('description')}
                    </DialogDescription>
                </DialogHeader>

                <div className="grid gap-4">
                    <div className="grid gap-1.5">
                        <Label htmlFor="deal-name">{t('name')}</Label>
                        <input
                            id="deal-name"
                            type="text"
                            value={payload.name}
                            onChange={(e) => {
                                setPayload((prev) => ({ ...prev, name: e.target.value }));
                                clearError('name');
                            }}
                            className={`${inputClass} ${fieldErrors.name ? 'ring-2 ring-red-400 focus:ring-red-500' : ''}`}
                            placeholder={t('namePlaceholder')}
                            aria-invalid={Boolean(fieldErrors.name)}
                            autoFocus
                            required
                        />
                        {fieldErrors.name && (
                            <p className="px-1 text-sm text-red-600">{fieldErrors.name}</p>
                        )}
                    </div>

                    <div className="grid grid-cols-[1fr_120px] gap-3">
                        <div className="grid gap-1.5">
                            <Label htmlFor="deal-value">{t('value')}</Label>
                            <input
                                id="deal-value"
                                type="number"
                                min="0"
                                step="0.01"
                                value={Number.isFinite(payload.value) ? payload.value : 0}
                                onChange={(e) => setPayload((prev) => ({ ...prev, value: Number(e.target.value) }))}
                                className={inputClass}
                                placeholder="0"
                            />
                        </div>
                        <div className="grid gap-1.5">
                            <Label htmlFor="deal-currency">{t('currency')}</Label>
                            <input
                                id="deal-currency"
                                type="text"
                                maxLength={8}
                                value={payload.currency}
                                onChange={(e) => setPayload((prev) => ({ ...prev, currency: e.target.value.toUpperCase() }))}
                                className={inputClass}
                                placeholder="USD"
                            />
                        </div>
                    </div>

                    <div className="grid grid-cols-2 gap-3">
                        <div className="grid gap-1.5">
                            <Label htmlFor="deal-pipeline">{t('pipeline')}</Label>
                            <Combobox
                                items={pipelines}
                                itemToStringLabel={(p: Pipeline) => p.name}
                                value={selectedPipeline}
                                onValueChange={(p) => {
                                    setPayload((prev) => ({
                                        ...prev,
                                        pipeline: (p as Pipeline | null)?.id ?? 0,
                                        stage: 0,
                                    }));
                                    clearError('pipeline');
                                    clearError('stage');
                                }}
                            >
                                <ComboboxInput
                                    id="deal-pipeline"
                                    placeholder={t('selectPipeline')}
                                    aria-invalid={Boolean(fieldErrors.pipeline)}
                                    className={`ring-1 ring-black/5 ${fieldErrors.pipeline ? 'ring-2 ring-red-400' : ''}`}
                                />
                                <ComboboxContent className="pointer-events-auto">
                                    <ComboboxList onWheel={handleListWheel}>
                                        <ComboboxEmpty>{t('noPipelinesFound')}</ComboboxEmpty>
                                        {pipelines.map((p) => (
                                            <ComboboxItem key={p.id} value={p}>
                                                {p.name}
                                            </ComboboxItem>
                                        ))}
                                    </ComboboxList>
                                </ComboboxContent>
                            </Combobox>
                            {fieldErrors.pipeline && (
                                <p className="px-1 text-sm text-red-600">{fieldErrors.pipeline}</p>
                            )}
                        </div>
                        <div className="grid gap-1.5">
                            <Label htmlFor="deal-stage">{t('stage')}</Label>
                            <Combobox
                                items={stages}
                                itemToStringLabel={(s: Stage) => s.name}
                                value={selectedStage}
                                disabled={!payload.pipeline}
                                onValueChange={(s) => {
                                    setPayload((prev) => ({ ...prev, stage: (s as Stage | null)?.id ?? 0 }));
                                    clearError('stage');
                                }}
                            >
                                <ComboboxInput
                                    id="deal-stage"
                                    placeholder={payload.pipeline ? t('selectStage') : t('pickPipelineFirst')}
                                    disabled={!payload.pipeline}
                                    aria-invalid={Boolean(fieldErrors.stage)}
                                    className={`ring-1 ring-black/5 ${fieldErrors.stage ? 'ring-2 ring-red-400' : ''}`}
                                />
                                <ComboboxContent className="pointer-events-auto">
                                    <ComboboxList onWheel={handleListWheel}>
                                        <ComboboxEmpty>{t('noStages')}</ComboboxEmpty>
                                        {stages.map((s) => (
                                            <ComboboxItem key={s.id} value={s}>
                                                {s.name}
                                            </ComboboxItem>
                                        ))}
                                    </ComboboxList>
                                </ComboboxContent>
                            </Combobox>
                            {fieldErrors.stage && (
                                <p className="px-1 text-sm text-red-600">{fieldErrors.stage}</p>
                            )}
                        </div>
                    </div>

                    <div className="grid gap-1.5">
                        <Label htmlFor="deal-company">{t('company')}</Label>
                        <Combobox
                            items={companies}
                            itemToStringLabel={(c: Company) => c.name}
                            value={selectedCompany}
                            onValueChange={(c) =>
                                setPayload((prev) => ({ ...prev, company: (c as Company | null)?.id ?? null }))
                            }
                        >
                            <ComboboxInput id="deal-company" placeholder={t('selectCompanyOptional')} showClear className="ring-1 ring-black/5" />
                            <ComboboxContent className="pointer-events-auto">
                                <ComboboxList onWheel={handleListWheel}>
                                    <ComboboxEmpty>{t('noCompaniesFound')}</ComboboxEmpty>
                                    {companies.map((c) => (
                                        <ComboboxItem key={c.id} value={c}>
                                            {c.name}
                                        </ComboboxItem>
                                    ))}
                                </ComboboxList>
                            </ComboboxContent>
                        </Combobox>
                    </div>

                    <div className="grid gap-1.5">
                        <Label htmlFor="deal-close">{t('expectedCloseDate')}</Label>
                        <input
                            id="deal-close"
                            type="date"
                            value={payload.expectedCloseDate ?? ''}
                            onChange={(e) =>
                                setPayload((prev) => ({ ...prev, expectedCloseDate: e.target.value || undefined }))
                            }
                            className={inputClass}
                        />
                    </div>
                </div>

                <DialogFooter>
                    <DialogClose asChild>
                        <Button variant="outline" disabled={isCreating}>{t('cancel')}</Button>
                    </DialogClose>
                    <Button
                        onClick={handleCreate}
                        disabled={isCreating}
                        className="bg-brand text-white hover:bg-brand-dark"
                    >
                        {isCreating ? <Loader2Icon className="size-4 animate-spin" /> : t('create')}
                    </Button>
                </DialogFooter>
            </DialogContent>
        </Dialog>
    );
}