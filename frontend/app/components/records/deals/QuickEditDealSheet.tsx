'use client';

// TODO: use quickEditSheet composition and extend it

import { type ReactNode, WheelEvent } from 'react';
import { useTranslations } from 'next-intl';
import { Sheet, SheetContent, SheetHeader, SheetTitle, SheetDescription, SheetFooter, SheetClose } from '@/components/ui/sheet';
import { Button } from '@/components/ui/button';
import { Loader2Icon } from 'lucide-react';
import { Combobox, ComboboxItem, ComboboxList, ComboboxContent, ComboboxEmpty, ComboboxInput } from '@/components/ui/combobox';
import { Label } from '@/components/ui/label';
import { type Company, type Deal, type Pipeline, type Stage } from '@/app/lib/types';
import { type SelectionId } from '@/app/components/records/types';
import { cn } from '@/lib/utils';
import { toMysqlDateTime, toDatetimeLocalValue } from '@/app/lib/utils';

export type DealDraft = {
    name: string;
    value: number;
    actualValue: number;
    currency: string;
    pipeline: number;
    stage: number;
    company: number | null;
    expectedCloseDate: string;
    closedAt: string | null;
    closedReason: string | null;
    won: boolean | null; // outcome: true=won, false=lost, null=open
};

type Props = {
    open: boolean;
    onOpenChange: (open: boolean) => void;
    selectedIds: Set<SelectionId>;
    selectedDeals: Deal[];
    drafts: Record<number, DealDraft>;
    updateDraft: (id: number, patch: Partial<DealDraft>) => void;
    companies: Company[];
    pipelines: Pipeline[];
    stagesByPipeline: Record<number, Stage[]>;
    isSaving: boolean;
    saveEdits: () => void;
    customFieldsSlot?: ReactNode;
};

export default function QuickEditDealSheet({
    open,
    onOpenChange,
    selectedIds,
    selectedDeals,
    drafts,
    updateDraft,
    companies,
    pipelines,
    stagesByPipeline,
    isSaving,
    saveEdits,
    customFieldsSlot,
}: Props) {
    const t = useTranslations('DealsQuickEditSheet');
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
                        {selectedIds.size === 1 ? t('titleSingle') : t('titleMulti', { count: selectedIds.size })}
                    </SheetTitle>
                    <SheetDescription>
                        {t('subtitle')}
                    </SheetDescription>
                </SheetHeader>

                <div className="flex-1 overflow-y-auto px-4 py-2">
                    <div className="flex flex-col gap-6">
                        {selectedDeals.map((d, idx) => {
                            const draft = drafts[d.id];
                            if (!draft) return null;
                            const selectedPipeline = pipelines.find((p) => p.id === draft.pipeline) ?? null;
                            const selectedCompany = companies.find((c) => c.id === draft.company) ?? null;
                            const stages = draft.pipeline ? stagesByPipeline[draft.pipeline] ?? [] : [];
                            const selectedStage = stages.find((s) => s.id === draft.stage) ?? null;
                            const expectedCloseDate = draft.expectedCloseDate ? draft.expectedCloseDate.slice(0, 10) : '';
                            const closedAt = toDatetimeLocalValue(draft.closedAt);
                            const actualValue = draft.actualValue ?? 0;

                            return (
                                <div key={d.id} className={idx > 0 ? 'border-t pt-6' : ''}>
                                    <div className="mb-3 text-lg font-medium text-foreground">{d.name}</div>

                                    <div className="grid gap-3">
                                        <div className="grid gap-1.5">
                                            <Label htmlFor={`deal-name-${d.id}`}>{t('name')}</Label>
                                            <input
                                                id={`deal-name-${d.id}`}
                                                type="text"
                                                value={draft.name}
                                                onChange={(e) => updateDraft(d.id, { name: e.target.value })}
                                                className="connex-input"
                                                required
                                            />
                                        </div>

                                        <div className="grid grid-cols-[1fr_120px] gap-3">
                                            <div className="grid gap-1.5">
                                                <Label htmlFor={`deal-value-${d.id}`}>{t('value')}</Label>
                                                <input
                                                    id={`deal-value-${d.id}`}
                                                    type="number"
                                                    min="0"
                                                    step="0.01"
                                                    value={draft.value}
                                                    onChange={(e) => updateDraft(d.id, { value: Number(e.target.value) })}
                                                    className="connex-input"
                                                />
                                            </div>
                                            <div className="grid gap-1.5">
                                                <Label htmlFor={`deal-currency-${d.id}`}>{t('currency')}</Label>
                                                <input
                                                    id={`deal-currency-${d.id}`}
                                                    type="text"
                                                    maxLength={8}
                                                    value={draft.currency}
                                                    onChange={(e) => updateDraft(d.id, { currency: e.target.value.toUpperCase() })}
                                                    className="connex-input"
                                                />
                                            </div>
                                        </div>

                                        <div className="grid grid-cols-2 gap-3">
                                            <div className="grid gap-1.5">
                                                <Label htmlFor={`deal-pipeline-${d.id}`}>{t('pipeline')}</Label>
                                                <Combobox
                                                    items={pipelines}
                                                    itemToStringLabel={(p: Pipeline) => p.name}
                                                    value={selectedPipeline}
                                                    onValueChange={(p) => {
                                                        const next = (p as Pipeline | null)?.id ?? 0;
                                                        updateDraft(d.id, { pipeline: next, stage: 0 });
                                                    }}
                                                >
                                                    <ComboboxInput id={`deal-pipeline-${d.id}`} placeholder={t('selectPipeline')} className="ring-1 ring-border" />
                                                    <ComboboxContent className="pointer-events-auto">
                                                        <ComboboxList onWheel={handleListWheel}>
                                                            <ComboboxEmpty>{t('noPipelines')}</ComboboxEmpty>
                                                            {pipelines.map((p) => (
                                                                <ComboboxItem key={p.id} value={p}>
                                                                    {p.name}
                                                                </ComboboxItem>
                                                            ))}
                                                        </ComboboxList>
                                                    </ComboboxContent>
                                                </Combobox>
                                            </div>
                                            <div className="grid gap-1.5">
                                                <Label htmlFor={`deal-stage-${d.id}`}>{t('stage')}</Label>
                                                <Combobox
                                                    items={stages}
                                                    itemToStringLabel={(s: Stage) => s.name}
                                                    value={selectedStage}
                                                    disabled={!draft.pipeline}
                                                    onValueChange={(s) => {
                                                        const stage = s as Stage | null;
                                                        // A terminal stage forces the outcome (mirrors the server). A normal
                                                        // stage leaves the explicit won/lost choice untouched.
                                                        const patch: Partial<DealDraft> = { stage: stage?.id ?? 0 };
                                                        if (stage?.success || stage?.failure) {
                                                            patch.won = !!stage.success;
                                                            patch.closedAt = draft.closedAt ?? toMysqlDateTime(new Date());
                                                        }
                                                        updateDraft(d.id, patch);
                                                    }}
                                                >
                                                    <ComboboxInput
                                                        id={`deal-stage-${d.id}`}
                                                        placeholder={draft.pipeline ? t('selectStage') : t('pickPipelineFirst')}
                                                        disabled={!draft.pipeline}
                                                        className="ring-1 ring-border"
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
                                            </div>
                                        </div>

                                        <div className="grid gap-1.5">
                                            <Label htmlFor={`deal-company-${d.id}`}>{t('company')}</Label>
                                            <Combobox
                                                items={companies}
                                                itemToStringLabel={(c: Company) => c.name}
                                                value={selectedCompany}
                                                onValueChange={(c) =>
                                                    updateDraft(d.id, { company: (c as Company | null)?.id ?? null })
                                                }
                                            >
                                                <ComboboxInput id={`deal-company-${d.id}`} placeholder={t('selectCompany')} showClear className="ring-1 ring-border" />
                                                <ComboboxContent className="pointer-events-auto">
                                                    <ComboboxList onWheel={handleListWheel}>
                                                        <ComboboxEmpty>{t('noCompanies')}</ComboboxEmpty>
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
                                            <Label htmlFor={`deal-close-${d.id}`}>{t('expectedCloseDate')}</Label>
                                            <input
                                                id={`deal-close-${d.id}`}
                                                type="date"
                                                // on load, set the fetched expectedCloseDate value
                                                // value={draft.expectedCloseDate}
                                                value={expectedCloseDate}
                                                onChange={(e) => updateDraft(d.id, { expectedCloseDate: e.target.value })}
                                                className="connex-input"
                                            />
                                        </div>

                                        <div className="grid gap-1.5">
                                            <Label>{t('outcomeLabel')}</Label>
                                            <div className="grid grid-cols-3 gap-1.5">
                                                {([
                                                    { key: 'open', won: null, label: t('outcomeOpen') },
                                                    { key: 'won', won: true, label: t('outcomeWon') },
                                                    { key: 'lost', won: false, label: t('outcomeLost') },
                                                ] as const).map((opt) => (
                                                    <button
                                                        key={opt.key}
                                                        type="button"
                                                        onClick={() =>
                                                            updateDraft(
                                                                d.id,
                                                                opt.won === null
                                                                    ? { won: null, closedAt: null, closedReason: null }
                                                                    : { won: opt.won, closedAt: draft.closedAt ?? toMysqlDateTime(new Date()) },
                                                            )
                                                        }
                                                        className={cn(
                                                            'rounded-lg px-3 py-2 text-sm font-medium ring-1 transition',
                                                            draft.won === opt.won
                                                                ? opt.key === 'won'
                                                                    ? 'bg-brand text-white ring-brand'
                                                                    : opt.key === 'lost'
                                                                      ? 'bg-red-600 text-white ring-red-600'
                                                                      : 'bg-foreground text-background ring-foreground'
                                                                : 'bg-muted text-muted-foreground ring-border hover:bg-muted/70',
                                                        )}
                                                    >
                                                        {opt.label}
                                                    </button>
                                                ))}
                                            </div>

                                            {/* when closed (won/lost), capture when / how much / why */}
                                            {draft.won !== null && (
                                                <>
                                                <div className="grid gap-1.5">
                                                    <Label htmlFor={`deal-closed-at-${d.id}`}>{t('closedAt')}</Label>
                                                    <input
                                                        id={`deal-closed-at-${d.id}`}
                                                        type="datetime-local"
                                                        value={closedAt}
                                                        onChange={(e) =>
                                                            updateDraft(d.id, {
                                                                closedAt: e.target.value ? toMysqlDateTime(e.target.value) : null,
                                                            })
                                                        }
                                                        className="connex-input"
                                                    />
                                                </div>
                                                <div className="grid gap-1.5">
                                                    <Label htmlFor={`deal-actual-value-${d.id}`}>{t('actualValue')}</Label>
                                                    <input
                                                        id={`deal-actual-value-${d.id}`}
                                                        type="number"
                                                        min="0"
                                                        step="0.01"
                                                        value={actualValue}
                                                        onChange={(e) => updateDraft(d.id, { actualValue: Number(e.target.value) })}
                                                        className="connex-input"
                                                    />
                                                </div>
                                                <div className="grid gap-1.5">
                                                    <Label htmlFor={`deal-closed-reason-${d.id}`}>{t('closedReason')}</Label>
                                                    <textarea
                                                        id={`deal-closed-reason-${d.id}`}
                                                        value={draft.closedReason ?? ''}
                                                        onChange={(e) => updateDraft(d.id, { closedReason: e.target.value })}
                                                        className="connex-input"
                                                        rows={3}
                                                        maxLength={255}
                                                    />
                                                </div>
                                                </>
                                            )}
                                        </div>
                                    </div>
                                </div>
                            );
                        })}
                    </div>
                    {customFieldsSlot}
                </div>

                <SheetFooter className="border-t">
                    <SheetClose asChild>
                        <Button variant="outline" disabled={isSaving}>{t('cancel')}</Button>
                    </SheetClose>
                    <Button onClick={saveEdits} disabled={isSaving} className="bg-brand text-white hover:bg-brand-dark">
                        {isSaving ? <Loader2Icon className="size-4 animate-spin" /> : t('save')}
                    </Button>
                </SheetFooter>
            </SheetContent>
        </Sheet>
    );
}