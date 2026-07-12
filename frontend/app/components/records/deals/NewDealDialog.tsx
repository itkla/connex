'use client';

import { Dispatch, FormEvent, SetStateAction, WheelEvent, useEffect } from 'react';
import { useTranslations } from 'next-intl';
import { useFieldErrors } from '@/app/hooks/useFieldErrors';
import { ResponsiveDialog, ResponsiveDialogContent, ResponsiveDialogHeader, ResponsiveDialogTitle, ResponsiveDialogDescription, ResponsiveDialogFooter, ResponsiveDialogClose } from '@/components/ui/responsive-dialog';
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
import { type Company, type CreateDealPayload, type Pipeline, type Stage } from '@/app/lib/types';
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

type Props = {
    open: boolean;
    onOpenChange: (open: boolean) => void;
    payload: CreateDealPayload;
    setPayload: Dispatch<SetStateAction<CreateDealPayload>>;
    companies: Company[];
    pipelines: Pipeline[];
    stagesByPipeline: Record<number, Stage[]>;
    isCreating: boolean;
    isSuccess?: boolean;
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
    isSuccess = false,
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
            if (isFieldError(err)) {
                const firstKey = Object.keys(err.fieldErrors)[0];
                if (firstKey) {
                    requestAnimationFrame(() => document.getElementById(`deal-${firstKey}`)?.focus());
                }
            }
        }
    };

    const handleSubmit = (e: FormEvent) => {
        e.preventDefault();
        if (isCreating) return;
        handleCreate();
    };

    const handleListWheel = (e: WheelEvent<HTMLDivElement>) => {
        const lineHeightPx = 16;
        const delta = e.deltaMode === 1 ? e.deltaY * lineHeightPx : e.deltaY;
        e.currentTarget.scrollTop += delta;
    };

    const handleOpenChange = (next: boolean) => {
        if (!next && isCreating) return;
        onOpenChange(next);
    };

    const selectedPipeline = pipelines.find((p) => p.id === payload.pipeline) ?? null;
    const selectedCompany = companies.find((c) => c.id === payload.company) ?? null;
    const stages = payload.pipeline ? stagesByPipeline[payload.pipeline] ?? [] : [];
    const selectedStage = stages.find((s) => s.id === payload.stage) ?? null;
    const actualValue = Number.isFinite(payload.actualValue) ? payload.actualValue : 0;

    const hasErrors = Object.keys(fieldErrors).length > 0;
    const status = resolveDialogStatus({ isLoading: isCreating, hasErrors, isSuccess });

    return (
        <ResponsiveDialog open={open} onOpenChange={handleOpenChange}>
            <ResponsiveDialogContent className="gap-0 overflow-hidden p-0 sm:max-w-lg">
                <DialogStatusCover status={status} />

                <div className="px-6 pb-6">
                    <ResponsiveDialogHeader className="ncd-rise -mt-12 mb-5" style={{ animationDelay: '40ms' }}>
                        <ResponsiveDialogTitle className="text-xl font-semibold tracking-tight">{t('title')}</ResponsiveDialogTitle>
                        <ResponsiveDialogDescription>{t('description')}</ResponsiveDialogDescription>
                    </ResponsiveDialogHeader>

                    <form onSubmit={handleSubmit} className="grid gap-5">
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
                                    aria-describedby={fieldErrors.name ? 'deal-name-error' : undefined}
                                    autoFocus
                                    required
                                />
                            </div>
                            {fieldErrors.name && (
                                <p id="deal-name-error" className="text-sm text-destructive">{fieldErrors.name}</p>
                            )}
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
                                items={companies}
                                itemToStringLabel={(c: Company) => c.name}
                                value={selectedCompany}
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
                                        {companies.map((c) => (
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

                        <ResponsiveDialogFooter className="ncd-rise mt-5" style={{ animationDelay: '340ms' }}>
                            <ResponsiveDialogClose asChild>
                                <Button type="button" variant="outline" disabled={isCreating}>{t('cancel')}</Button>
                            </ResponsiveDialogClose>
                            <Button
                                type="submit"
                                variant="brand"
                                disabled={isCreating || isSuccess}
                                className="min-w-24 shadow-sm transition hover:shadow-md"
                            >
                                {isCreating ? <Loader2Icon className="size-4 animate-spin" /> : t('create')}
                            </Button>
                        </ResponsiveDialogFooter>
                    </form>
                </div>
            </ResponsiveDialogContent>
        </ResponsiveDialog>
    );
}
