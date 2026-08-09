'use client';

import { Dispatch, FormEvent, SetStateAction, WheelEvent, useEffect, useRef } from 'react';
import { useTranslations } from 'next-intl';
import { useFieldErrors } from '@/app/hooks/useFieldErrors';
import { useDuplicatePreflight } from '@/app/hooks/useDuplicatePreflight';
import DuplicatePreflightWarning from '@/app/components/records/DuplicatePreflightWarning';
import { ResponsiveDialog, ResponsiveDialogContent, ResponsiveDialogTitle, ResponsiveDialogDescription } from '@/components/ui/responsive-dialog';
import { Button } from '@/components/ui/button';
import { Loader2Icon } from 'lucide-react';
import { Combobox, ComboboxItem, ComboboxList, ComboboxContent, ComboboxEmpty, ComboboxInput } from '@/components/ui/combobox';
import { InputGroupAddon } from '@/components/ui/input-group';
import { Label } from '@/components/ui/label';
import {
    DialogStatusCover,
    resolveDialogStatus,
    fieldInputClass,
    fieldErrorClass,
    fieldLeadIconClass,
} from '@/components/ui/dialog-status-cover';
import { cn } from '@/lib/utils';
import { isFieldError } from '@/app/lib/api';
import { isSubmitShortcut } from '@/app/lib/submitShortcut';
import { type Company, type CreateDealPayload, type Pipeline, type Stage } from '@/app/lib/types';
import { useCompanySearch } from '@/app/hooks/useCompanySearch';
import { useUnsavedChangesGuard } from '@/app/hooks/useUnsavedChangesGuard';
import ConfirmDiscardDialog from '@/app/components/ConfirmDiscardDialog';
import { toastError } from '@/app/lib/toast';
import {
    TagIcon,
    BanknotesIcon,
    FunnelIcon,
    FlagIcon,
    BuildingOffice2Icon,
    CalendarIcon,
} from '@heroicons/react/24/outline';

const comboInputClass =
    'rounded-lg border-0 bg-muted shadow-none ring-1 ring-border dark:bg-muted has-[[data-slot=input-group-control]:focus-visible]:ring-2 has-[[data-slot=input-group-control]:focus-visible]:ring-brand';
const comboLeadIconClass =
    'size-4 text-muted-foreground transition-colors group-focus-within/input-group:text-brand';

/**
 * Whether a deal-create payload has diverged from its seeded baseline, so a container can drive the
 * accidental-discard guard. Compares every field with null/undefined normalized, so an untouched
 * form (still equal to its seed) is never reported dirty.
 */
export function isDealPayloadDirty(payload: CreateDealPayload, baseline: CreateDealPayload): boolean {
    return (
        payload.name !== baseline.name ||
        payload.value !== baseline.value ||
        payload.actualValue !== baseline.actualValue ||
        payload.currency !== baseline.currency ||
        (payload.pipeline ?? null) !== (baseline.pipeline ?? null) ||
        (payload.stage ?? null) !== (baseline.stage ?? null) ||
        (payload.company ?? null) !== (baseline.company ?? null) ||
        (payload.ownerId ?? null) !== (baseline.ownerId ?? null) ||
        (payload.expectedCloseDate ?? '') !== (baseline.expectedCloseDate ?? '') ||
        (payload.closedAt ?? '') !== (baseline.closedAt ?? '') ||
        (payload.closedReason ?? '') !== (baseline.closedReason ?? '') ||
        (payload.won ?? null) !== (baseline.won ?? null)
    );
}

type Props = {
    open: boolean;
    onOpenChange: (open: boolean) => void;
    payload: CreateDealPayload;
    setPayload: Dispatch<SetStateAction<CreateDealPayload>>;
    pipelines: Pipeline[];
    stagesByPipeline: Record<number, Stage[]>;
    isCreating: boolean;
    isSuccess?: boolean;
    /** Whether the payload has diverged from its seeded baseline; drives the accidental-discard guard. */
    isDirty?: boolean;
    createNewDeal: (duplicateReviewToken: string) => void | Promise<void>;
    requestInit?: RequestInit;
};

export default function NewDealDialog({
    open,
    onOpenChange,
    payload,
    setPayload,
    pipelines,
    stagesByPipeline,
    isCreating,
    isSuccess = false,
    isDirty = false,
    createNewDeal,
    requestInit,
}: Props) {
    const t = useTranslations('DealsNewDialog');
    const guard = useUnsavedChangesGuard({ isDirty, onClose: () => onOpenChange(false), enabled: open && !isCreating });

    const handleOpenChange = (next: boolean) => {
        if (!next && isCreating) return;
        guard.onOpenChange(next);
    };

    return (
        <>
            <ResponsiveDialog open={open} onOpenChange={handleOpenChange}>
                <ResponsiveDialogContent className="gap-0 overflow-hidden p-0 sm:max-w-lg">
                    <ResponsiveDialogTitle className="sr-only">{t('title')}</ResponsiveDialogTitle>
                    <ResponsiveDialogDescription className="sr-only">{t('description')}</ResponsiveDialogDescription>
                    <NewDealForm
                        active={open}
                        onCancel={guard.requestClose}
                        payload={payload}
                        setPayload={setPayload}
                        pipelines={pipelines}
                        stagesByPipeline={stagesByPipeline}
                        isCreating={isCreating}
                        isSuccess={isSuccess}
                        createNewDeal={createNewDeal}
                        requestInit={requestInit}
                    />
                </ResponsiveDialogContent>
            </ResponsiveDialog>
            <ConfirmDiscardDialog open={guard.confirm.open} onKeepEditing={guard.confirm.onKeepEditing} onDiscard={guard.confirm.onDiscard} />
        </>
    );
}

type NewDealFormProps = {
    /** Whether the surface is active; gates the company search the way `open` did in the dialog. */
    active: boolean;
    payload: CreateDealPayload;
    setPayload: Dispatch<SetStateAction<CreateDealPayload>>;
    pipelines: Pipeline[];
    stagesByPipeline: Record<number, Stage[]>;
    isCreating: boolean;
    isSuccess?: boolean;
    createNewDeal: (duplicateReviewToken: string) => void | Promise<void>;
    requestInit?: RequestInit;
    /** Invoked by the Cancel button — closes the dialog, or steps back to the selector in the morphing launcher. */
    onCancel: () => void;
};

/**
 * The deal quick-create form body — free of any dialog/drawer shell so it can render inside the
 * standalone {@link NewDealDialog} (desktop dialog / mobile drawer) or embedded in the morphing
 * Quick Create drawer. All submit/data ownership stays with the caller; this is a controlled form.
 */
export function NewDealForm({
    active,
    payload,
    setPayload,
    pipelines,
    stagesByPipeline,
    isCreating,
    isSuccess = false,
    createNewDeal,
    requestInit,
    onCancel,
}: NewDealFormProps) {
    const t = useTranslations('DealsNewDialog');
    const { fieldErrors, reset: resetFieldErrors, clearError, captureFieldErrors } = useFieldErrors();
    const submissionPendingRef = useRef(false);
    const companySearch = useCompanySearch(active, [payload.company]);
    const duplicatePreflight = useDuplicatePreflight('deal', {
        name: payload.name.trim(),
        companyId: payload.company,
    }, active, requestInit);

    useEffect(() => {
        if (!active) {
            submissionPendingRef.current = false;
            resetFieldErrors();
        }
    }, [active, resetFieldErrors]);

    useEffect(() => {
        if (companySearch.error) toastError(t('companySearchFailed'));
    }, [companySearch.error, t]);

    const handleCreate = async () => {
        if (submissionPendingRef.current || duplicatePreflight.blocked) return;
        submissionPendingRef.current = true;
        resetFieldErrors();
        try {
            const duplicateDecision = await duplicatePreflight.reviewNow();
            if (!duplicateDecision.allowed || !duplicateDecision.duplicateReviewToken) return;
            await createNewDeal(duplicateDecision.duplicateReviewToken);
        } catch (err) {
            captureFieldErrors(err);
            if (isFieldError(err)) {
                const firstKey = Object.keys(err.fieldErrors)[0];
                if (firstKey) {
                    requestAnimationFrame(() => document.getElementById(`deal-${firstKey}`)?.focus());
                }
            }
        } finally {
            submissionPendingRef.current = false;
        }
    };

    const handleSubmit = (e: FormEvent) => {
        e.preventDefault();
        if (isCreating || submissionPendingRef.current || duplicatePreflight.blocked) return;
        void handleCreate();
    };

    const handleListWheel = (e: WheelEvent<HTMLDivElement>) => {
        const lineHeightPx = 16;
        const delta = e.deltaMode === 1 ? e.deltaY * lineHeightPx : e.deltaY;
        e.currentTarget.scrollTop += delta;
    };

    const selectedPipeline = pipelines.find((p) => p.id === payload.pipeline) ?? null;
    const selectedCompany = companySearch.companies.find((c) => c.id === payload.company) ?? null;
    const stages = payload.pipeline ? stagesByPipeline[payload.pipeline] ?? [] : [];
    const selectedStage = stages.find((s) => s.id === payload.stage) ?? null;
    const hasErrors = Object.keys(fieldErrors).length > 0
        || duplicatePreflight.status === 'error';
    const status = resolveDialogStatus({ isLoading: isCreating, hasErrors, isSuccess });

    return (
        <>
            <DialogStatusCover status={status} />

            <div className="px-6 pb-6">
                <div className="ncd-rise -mt-12 mb-5 flex flex-col gap-2" style={{ animationDelay: '40ms' }}>
                    <h2 className="font-heading text-xl font-semibold leading-none tracking-tight">{t('title')}</h2>
                    <p className="text-sm text-muted-foreground">{t('description')}</p>
                </div>

                <form
                    onSubmit={handleSubmit}
                    onKeyDown={(e) => {
                        if (isSubmitShortcut(e) && !isCreating && !isSuccess) {
                            e.preventDefault();
                            e.currentTarget.requestSubmit();
                        }
                    }}
                    className="grid gap-5"
                >
                    <div className="ncd-rise grid gap-1.5" style={{ animationDelay: '90ms' }}>
                        <Label htmlFor="deal-name">{t('name')}</Label>
                        <div className="group relative">
                            <TagIcon className={fieldLeadIconClass} />
                            <input
                                id="deal-name"
                                type="text"
                                value={payload.name}
                                onChange={(e) => {
                                    setPayload((prev) => ({ ...prev, name: e.target.value }));
                                    clearError('name');
                                }}
                                className={cn(fieldInputClass, 'pl-9 pr-3', fieldErrors.name && fieldErrorClass)}
                                placeholder={t('namePlaceholder')}
                                aria-invalid={Boolean(fieldErrors.name)}
                                aria-describedby={[
                                    fieldErrors.name && 'deal-name-error',
                                    duplicatePreflight.status !== 'idle' && 'deal-duplicate-preflight',
                                ].filter(Boolean).join(' ') || undefined}
                                autoFocus
                                required
                            />
                        </div>
                        {fieldErrors.name && (
                            <p id="deal-name-error" className="text-sm text-destructive">{fieldErrors.name}</p>
                        )}
                        <DuplicatePreflightWarning
                            id="deal-duplicate-preflight"
                            kind="deal"
                            status={duplicatePreflight.status}
                            response={duplicatePreflight.response}
                            acknowledged={duplicatePreflight.acknowledged}
                            onAcknowledgedChange={duplicatePreflight.setAcknowledged}
                            onRetry={duplicatePreflight.retry}
                        />
                    </div>

                    <div className="ncd-rise grid grid-cols-[1fr_120px] gap-3" style={{ animationDelay: '140ms' }}>
                        <div className="grid gap-1.5">
                            <Label htmlFor="deal-value">{t('value')}</Label>
                            <div className="group relative">
                                <BanknotesIcon className={fieldLeadIconClass} />
                                <input
                                    id="deal-value"
                                    type="number"
                                    min="0"
                                    step="0.01"
                                    value={Number.isFinite(payload.value) ? payload.value : 0}
                                    onChange={(e) => {
                                        setPayload((prev) => ({ ...prev, value: Number(e.target.value) }));
                                        clearError('value');
                                    }}
                                    className={cn(fieldInputClass, 'pl-9 pr-3', fieldErrors.value && fieldErrorClass)}
                                    aria-invalid={Boolean(fieldErrors.value)}
                                    aria-describedby={fieldErrors.value ? 'deal-value-error' : undefined}
                                    placeholder="0"
                                />
                            </div>
                            {fieldErrors.value && (
                                <p id="deal-value-error" className="text-sm text-destructive">{fieldErrors.value}</p>
                            )}
                        </div>
                        <div className="grid gap-1.5">
                            <Label htmlFor="deal-currency">{t('currency')}</Label>
                            <input
                                id="deal-currency"
                                type="text"
                                maxLength={8}
                                value={payload.currency}
                                onChange={(e) => setPayload((prev) => ({ ...prev, currency: e.target.value.toUpperCase() }))}
                                className={cn(fieldInputClass, 'px-3')}
                                placeholder="USD"
                            />
                        </div>
                    </div>

                    <div className="ncd-rise grid grid-cols-2 gap-3" style={{ animationDelay: '190ms' }}>
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
                                    className={cn(comboInputClass, fieldErrors.pipeline && 'ring-2 ring-destructive')}
                                >
                                    <InputGroupAddon align="inline-start">
                                        <FunnelIcon className={comboLeadIconClass} />
                                    </InputGroupAddon>
                                </ComboboxInput>
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
                                <p className="text-sm text-destructive">{fieldErrors.pipeline}</p>
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
                                    className={cn(comboInputClass, fieldErrors.stage && 'ring-2 ring-destructive')}
                                >
                                    <InputGroupAddon align="inline-start">
                                        <FlagIcon className={comboLeadIconClass} />
                                    </InputGroupAddon>
                                </ComboboxInput>
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
                                <p className="text-sm text-destructive">{fieldErrors.stage}</p>
                            )}
                        </div>
                    </div>

                    <div className="ncd-rise grid gap-1.5" style={{ animationDelay: '240ms' }}>
                        <Label htmlFor="deal-company">{t('company')}</Label>
                        <Combobox
                            items={companySearch.companies}
                            filter={null}
                            itemToStringLabel={(c: Company) => c.name}
                            value={selectedCompany}
                            onInputValueChange={companySearch.onInputValueChange}
                            onValueChange={(c) =>
                                setPayload((prev) => ({ ...prev, company: (c as Company | null)?.id ?? null }))
                            }
                        >
                            <ComboboxInput
                                id="deal-company"
                                placeholder={t('selectCompanyOptional')}
                                showClear
                                className={comboInputClass}
                            >
                                <InputGroupAddon align="inline-start">
                                    <BuildingOffice2Icon className={comboLeadIconClass} />
                                </InputGroupAddon>
                            </ComboboxInput>
                            <ComboboxContent className="pointer-events-auto">
                                <ComboboxList onWheel={handleListWheel}>
                                    <ComboboxEmpty>{t('noCompaniesFound')}</ComboboxEmpty>
                                    {companySearch.companies.map((c) => (
                                        <ComboboxItem key={c.id} value={c}>
                                            {c.name}
                                        </ComboboxItem>
                                    ))}
                                </ComboboxList>
                            </ComboboxContent>
                        </Combobox>
                    </div>

                    <div className="ncd-rise grid gap-1.5" style={{ animationDelay: '290ms' }}>
                        <Label htmlFor="deal-close">{t('expectedCloseDate')}</Label>
                        <div className="group relative">
                            <CalendarIcon className={fieldLeadIconClass} />
                            <input
                                id="deal-close"
                                type="date"
                                value={payload.expectedCloseDate ?? ''}
                                onChange={(e) =>
                                    setPayload((prev) => ({ ...prev, expectedCloseDate: e.target.value || undefined }))
                                }
                                className={cn(fieldInputClass, 'pl-9 pr-3')}
                            />
                        </div>
                    </div>

                    <div className="ncd-rise mt-5 flex flex-col-reverse gap-2 sm:flex-row sm:justify-end" style={{ animationDelay: '340ms' }}>
                        <Button type="button" variant="outline" disabled={isCreating} onClick={onCancel}>{t('cancel')}</Button>
                        <Button
                            type="submit"
                            variant="brand"
                            disabled={isCreating || isSuccess || duplicatePreflight.blocked}
                            className="min-w-24 shadow-sm transition hover:shadow-md"
                        >
                            {isCreating ? <Loader2Icon className="size-4 animate-spin" /> : t('create')}
                        </Button>
                    </div>
                </form>
            </div>
        </>
    );
}
