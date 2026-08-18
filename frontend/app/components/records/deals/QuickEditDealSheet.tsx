'use client';

import { useEffect, type ReactNode, type WheelEvent } from 'react';
import { useTranslations } from 'next-intl';
import { AnimatePresence, motion, useReducedMotion } from 'motion/react';
import { BanknotesIcon } from '@heroicons/react/24/outline';

import { Input } from '@/components/ui/input';
import MentionEditor from '@/app/components/activity/notes/MentionEditor';
import { ENTITY_COMMANDS } from '@/app/components/activity/notes/commands/slashCommandRegistry';
import { Combobox, ComboboxItem, ComboboxList, ComboboxContent, ComboboxEmpty, ComboboxInput } from '@/components/ui/combobox';
import { type Company, type Deal, type Pipeline, type Stage } from '@/app/lib/types';
import { type SelectionId } from '@/app/components/records/types';
import { cn } from '@/lib/utils';
import { toMysqlDateTime, toDatetimeLocalValue } from '@/app/lib/utils';
import {
    EASE_OUT,
    QuickEditField,
    QuickEditRecordCard,
    QuickEditSheetShell,
} from '@/app/components/records/quick-edit/QuickEditSheetShell';
import { quickEditErrorId } from '@/app/hooks/useFieldErrors';
import { useCompanySearch } from '@/app/hooks/useCompanySearch';
import { toastError } from '@/app/lib/toast';
import { actualValueForOutcome } from '@/app/components/records/deals/dealOutcome';

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
    won: boolean | null;
};

type Props = {
    open: boolean;
    onOpenChange: (open: boolean) => void;
    selectedIds: Set<SelectionId>;
    selectedDeals: Deal[];
    drafts: Record<number, DealDraft>;
    updateDraft: (id: number, patch: Partial<DealDraft>) => void;
    pipelines: Pipeline[];
    stagesByPipeline: Record<number, Stage[]>;
    isSaving: boolean;
    saveEdits: () => void;
    /** Per-deal inline validation messages, keyed by deal id then draft field. */
    fieldErrors?: Record<number, Record<string, string>>;
    /** Unsaved state owned by `customFieldsSlot`, folded into the sheet's discard guard. */
    customFieldsDirty?: boolean;
    customFieldsSlot?: ReactNode;
};

type OutcomeOption = { key: 'open' | 'won' | 'lost'; won: boolean | null; label: string };

const OUTCOME_SELECTED: Record<OutcomeOption['key'], string> = {
    open: 'bg-background text-foreground shadow-sm ring-1 ring-border',
    won: 'bg-brand text-brand-foreground shadow-sm',
    lost: 'bg-destructive text-white shadow-sm',
};

export default function QuickEditDealSheet({
    open,
    onOpenChange,
    selectedDeals,
    drafts,
    updateDraft,
    pipelines,
    stagesByPipeline,
    isSaving,
    saveEdits,
    fieldErrors,
    customFieldsDirty,
    customFieldsSlot,
}: Props) {
    const t = useTranslations('DealsQuickEditSheet');
    const reduce = useReducedMotion() ?? false;
    const total = selectedDeals.length;
    const companySearch = useCompanySearch(
        open,
        Object.values(drafts).map((draft) => draft.company),
    );

    useEffect(() => {
        if (companySearch.error) toastError(t('companySearchFailed'));
    }, [companySearch.error, t]);

    const handleListWheel = (e: WheelEvent<HTMLDivElement>) => {
        const lineHeightPx = 16;
        const delta = e.deltaMode === 1 ? e.deltaY * lineHeightPx : e.deltaY;
        e.currentTarget.scrollTop += delta;
    };

    const outcomes: OutcomeOption[] = [
        { key: 'open', won: null, label: t('outcomeOpen') },
        { key: 'won', won: true, label: t('outcomeWon') },
        { key: 'lost', won: false, label: t('outcomeLost') },
    ];

    return (
        <QuickEditSheetShell
            open={open}
            onOpenChange={onOpenChange}
            icon={<BanknotesIcon />}
            title={total === 1 ? t('titleSingle') : t('titleMulti', { count: total })}
            description={t('subtitle')}
            count={total}
            isSaving={isSaving}
            onSave={saveEdits}
            saveLabel={t('save')}
            cancelLabel={t('cancel')}
            dirtySnapshot={{ drafts, customFieldsDirty }}
        >
            {selectedDeals.map((d, idx) => {
                const draft = drafts[d.id];
                if (!draft) return null;
                const selectedPipeline = pipelines.find((p) => p.id === draft.pipeline) ?? null;
                const selectedCompany = companySearch.companies.find((c) => c.id === draft.company) ?? null;
                const stages = draft.pipeline ? stagesByPipeline[draft.pipeline] ?? [] : [];
                const selectedStage = stages.find((s) => s.id === draft.stage) ?? null;
                const expectedCloseDate = draft.expectedCloseDate ? draft.expectedCloseDate.slice(0, 10) : '';
                const closedAt = toDatetimeLocalValue(draft.closedAt);
                const actualValue = actualValueForOutcome(draft.won, draft.actualValue ?? 0);
                const isLost = draft.won === false;

                return (
                    <QuickEditRecordCard
                        key={d.id}
                        index={idx}
                        total={total}
                        title={d.name}
                        subtitle={selectedCompany?.name ?? undefined}
                    >
                        <QuickEditField
                            label={t('name')}
                            htmlFor={`deal-name-${d.id}`}
                            required
                            error={fieldErrors?.[d.id]?.name}
                        >
                            <Input
                                id={`deal-name-${d.id}`}
                                type="text"
                                value={draft.name}
                                onChange={(e) => updateDraft(d.id, { name: e.target.value })}
                                aria-invalid={Boolean(fieldErrors?.[d.id]?.name)}
                                aria-describedby={fieldErrors?.[d.id]?.name ? quickEditErrorId(`deal-name-${d.id}`) : undefined}
                                required
                            />
                        </QuickEditField>

                        <div className="grid grid-cols-[1fr_7rem] gap-3">
                            <QuickEditField label={t('value')} htmlFor={`deal-value-${d.id}`}>
                                <Input
                                    id={`deal-value-${d.id}`}
                                    type="number"
                                    min="0"
                                    step="0.01"
                                    value={draft.value}
                                    disabled={d.valueSource === 'line_items'}
                                    aria-describedby={d.valueSource === 'line_items' ? `deal-value-hint-${d.id}` : undefined}
                                    onChange={(e) => updateDraft(d.id, { value: Number(e.target.value) })}
                                />
                                {d.valueSource === 'line_items' ? (
                                    <p id={`deal-value-hint-${d.id}`} className="mt-1 text-xs text-muted-foreground">
                                        {t('valueDerivedFromLineItems')}
                                    </p>
                                ) : null}
                            </QuickEditField>
                            <QuickEditField label={t('currency')} htmlFor={`deal-currency-${d.id}`}>
                                <Input
                                    id={`deal-currency-${d.id}`}
                                    type="text"
                                    maxLength={8}
                                    value={draft.currency}
                                    onChange={(e) => updateDraft(d.id, { currency: e.target.value.toUpperCase() })}
                                />
                            </QuickEditField>
                        </div>

                        <div className="grid grid-cols-2 gap-3">
                            <QuickEditField
                                label={t('pipeline')}
                                htmlFor={`deal-pipeline-${d.id}`}
                                required
                                error={fieldErrors?.[d.id]?.pipeline}
                            >
                                <Combobox
                                    items={pipelines}
                                    itemToStringLabel={(p: Pipeline) => p.name}
                                    value={selectedPipeline}
                                    onValueChange={(p) => {
                                        const next = (p as Pipeline | null)?.id ?? 0;
                                        updateDraft(d.id, { pipeline: next, stage: 0 });
                                    }}
                                >
                                    <ComboboxInput id={`deal-pipeline-${d.id}`} placeholder={t('selectPipeline')} />
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
                            </QuickEditField>
                            <QuickEditField
                                label={t('stage')}
                                htmlFor={`deal-stage-${d.id}`}
                                required
                                error={fieldErrors?.[d.id]?.stage}
                            >
                                <Combobox
                                    items={stages}
                                    itemToStringLabel={(s: Stage) => s.name}
                                    value={selectedStage}
                                    disabled={!draft.pipeline}
                                    onValueChange={(s) => {
                                        const stage = s as Stage | null;
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
                            </QuickEditField>
                        </div>

                        <QuickEditField label={t('company')} htmlFor={`deal-company-${d.id}`}>
                            <Combobox
                                items={companySearch.companies}
                                filter={null}
                                itemToStringLabel={(c: Company) => c.name}
                                value={selectedCompany}
                                onInputValueChange={companySearch.onInputValueChange}
                                onValueChange={(c) => updateDraft(d.id, { company: (c as Company | null)?.id ?? null })}
                            >
                                <ComboboxInput id={`deal-company-${d.id}`} placeholder={t('selectCompany')} showClear />
                                <ComboboxContent className="pointer-events-auto">
                                    <ComboboxList onWheel={handleListWheel}>
                                        <ComboboxEmpty>{t('noCompanies')}</ComboboxEmpty>
                                        {companySearch.companies.map((c) => (
                                            <ComboboxItem key={c.id} value={c}>
                                                {c.name}
                                            </ComboboxItem>
                                        ))}
                                    </ComboboxList>
                                </ComboboxContent>
                            </Combobox>
                        </QuickEditField>

                        <QuickEditField label={t('expectedCloseDate')} htmlFor={`deal-close-${d.id}`}>
                            <Input
                                id={`deal-close-${d.id}`}
                                type="date"
                                value={expectedCloseDate}
                                onChange={(e) => updateDraft(d.id, { expectedCloseDate: e.target.value })}
                            />
                        </QuickEditField>

                        <QuickEditField label={t('outcomeLabel')}>
                            <div className="grid grid-cols-3 gap-1 rounded-lg bg-muted p-1">
                                {outcomes.map((opt) => (
                                    <button
                                        key={opt.key}
                                        type="button"
                                        aria-pressed={draft.won === opt.won}
                                        onClick={() =>
                                            updateDraft(
                                                d.id,
                                                opt.won === null
                                                    ? { won: null, closedAt: null, closedReason: null }
                                                    : {
                                                        won: opt.won,
                                                        closedAt: draft.closedAt ?? toMysqlDateTime(new Date()),
                                                    },
                                            )
                                        }
                                        className={cn(
                                            'rounded-md px-3 py-1.5 text-sm font-medium transition-colors',
                                            draft.won === opt.won
                                                ? OUTCOME_SELECTED[opt.key]
                                                : 'text-muted-foreground hover:text-foreground',
                                        )}
                                    >
                                        {opt.label}
                                    </button>
                                ))}
                            </div>
                        </QuickEditField>

                        <AnimatePresence initial={false}>
                            {draft.won !== null ? (
                                <motion.div
                                    key="closed-fields"
                                    initial={reduce ? false : { height: 0, opacity: 0 }}
                                    animate={reduce ? undefined : { height: 'auto', opacity: 1 }}
                                    exit={reduce ? undefined : { height: 0, opacity: 0 }}
                                    transition={{ duration: 0.24, ease: EASE_OUT }}
                                    className="overflow-hidden"
                                >
                                    <div className="grid gap-3 pt-1">
                                        <QuickEditField label={t('closedAt')} htmlFor={`deal-closed-at-${d.id}`}>
                                            <Input
                                                id={`deal-closed-at-${d.id}`}
                                                type="datetime-local"
                                                value={closedAt}
                                                onChange={(e) =>
                                                    updateDraft(d.id, {
                                                        closedAt: e.target.value ? toMysqlDateTime(e.target.value) : null,
                                                    })
                                                }
                                            />
                                        </QuickEditField>
                                        <QuickEditField label={t('actualValue')} htmlFor={`deal-actual-value-${d.id}`}>
                                            <Input
                                                id={`deal-actual-value-${d.id}`}
                                                type="number"
                                                min="0"
                                                step="0.01"
                                                value={actualValue}
                                                disabled={isLost}
                                                aria-describedby={isLost ? `deal-actual-value-hint-${d.id}` : undefined}
                                                onChange={(e) => updateDraft(d.id, { actualValue: Number(e.target.value) })}
                                            />
                                            {isLost ? (
                                                <p
                                                    id={`deal-actual-value-hint-${d.id}`}
                                                    className="mt-1 text-xs text-muted-foreground"
                                                >
                                                    {t('actualValueLost')}
                                                </p>
                                            ) : null}
                                        </QuickEditField>
                                        <QuickEditField label={t('closedReason')} htmlFor={`deal-closed-reason-${d.id}`}>
                                            <MentionEditor
                                                id={`deal-closed-reason-${d.id}`}
                                                value={draft.closedReason ?? ''}
                                                onChange={(value) => updateDraft(d.id, { closedReason: value })}
                                                commands={ENTITY_COMMANDS}
                                                className="min-h-[6rem] rounded-lg bg-muted px-3 py-2 text-sm ring-1 ring-border focus:ring-2 focus:ring-brand"
                                            />
                                        </QuickEditField>
                                    </div>
                                </motion.div>
                            ) : null}
                        </AnimatePresence>
                    </QuickEditRecordCard>
                );
            })}
            {customFieldsSlot}
        </QuickEditSheetShell>
    );
}
