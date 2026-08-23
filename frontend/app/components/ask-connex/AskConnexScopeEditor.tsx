'use client';

import { useEffect, useState, type ReactNode } from 'react';
import { useTranslations } from 'next-intl';
import { CheckIcon } from '@heroicons/react/24/outline';

import {
    ASK_CONNEX_SCOPE_DEAL_STATUSES,
    ASK_CONNEX_SCOPE_MAX_MEMBERS,
    ASK_CONNEX_SCOPE_MAX_STAGES,
    ASK_CONNEX_SCOPE_PERIOD_PRESETS,
    ASK_CONNEX_SCOPE_RECORD_KINDS,
    ASK_CONNEX_SCOPE_WARMTH_BANDS,
    askConnexScopeAllowsDeals,
    askConnexScopeDeclared,
    askConnexScopeDisclosures,
    askConnexScopeOptionsFor,
    askConnexScopeProblem,
    askConnexScopeSavedViewLabels,
    askConnexScopeStageLabels,
    clearedAskConnexScopeDraft,
    withAskConnexScopeRecordKinds,
    type AskConnexScopeDraft,
    type AskConnexScopeOptions,
    type AskConnexScopePeriodMode,
    type AskConnexScopePreviewState,
} from '@/app/lib/askConnexScope';
import { useWorkspace } from '@/app/hooks/useWorkspace';
import { getActiveWorkspaceMembers, getAllStages, getPipelines, getSavedViews } from '@/app/lib/api';
import type {
    AiAssistantSkill,
    AiChatPageContextKind,
    AiChatScopeDealStatus,
    AiChatScopeOwnerMode,
    AiChatScopeWarmthBand,
    SavedViewRecordType,
} from '@/app/lib/types';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import {
    ResponsiveDialog,
    ResponsiveDialogClose,
    ResponsiveDialogContent,
    ResponsiveDialogDescription,
    ResponsiveDialogFooter,
    ResponsiveDialogHeader,
    ResponsiveDialogTitle,
} from '@/components/ui/responsive-dialog';
import { SegmentedControl } from '@/components/ui/segmented-control';
import { cn } from '@/lib/utils';

function toggle<T>(values: readonly T[], value: T): T[] {
    return values.includes(value)
        ? values.filter((current) => current !== value)
        : [...values, value];
}

/** One field of the form, titled so the compact layout still reads as a labelled group. */
function Field({ label, children }: { label: string; children: ReactNode }) {
    return (
        <fieldset className="space-y-2">
            <legend className="text-xs font-medium text-muted-foreground">{label}</legend>
            {children}
        </fieldset>
    );
}

/**
 * One value in a multiple-choice field.
 *
 * A row of independent toggles, rather than a segmented control, because these fields genuinely
 * accept several answers at once and a control that says "exactly one of these" would be lying
 * about the filter it sets.
 */
function ChoiceButton({
    label,
    selected,
    disabled = false,
    onSelect,
}: {
    label: string;
    selected: boolean;
    disabled?: boolean;
    onSelect: () => void;
}) {
    return (
        <Button
            type="button"
            size="inline"
            variant={selected ? 'secondary' : 'outline'}
            aria-pressed={selected}
            disabled={disabled}
            onClick={onSelect}
            className={cn(
                'font-normal',
                selected && 'bg-brand-light/60 font-medium text-brand-dark hover:bg-brand-light/70',
            )}
        >
            {selected ? <CheckIcon aria-hidden className="size-3" /> : null}
            {label}
        </Button>
    );
}

/**
 * What the declared filters actually cover, stated before anything is asked.
 *
 * Every number here is the server's own interpretation of the filters, so the sentence is the query
 * that will run rather than the one that was typed. Anything the server capped or could not apply is
 * disclosed underneath instead of being left for the answer to contradict later.
 */
export function AskConnexScopeSummary({
    preview,
    skills,
}: {
    preview: AskConnexScopePreviewState;
    skills: readonly AiAssistantSkill[];
}) {
    const t = useTranslations('AskConnex');
    const tRoot = useTranslations();

    if (preview.status === 'idle') {
        return <p className="text-xs leading-relaxed text-muted-foreground">{t('scope.preview.idle')}</p>;
    }
    if (preview.status === 'loading') {
        return (
            <p role="status" className="text-xs leading-relaxed text-muted-foreground">
                {t('scope.preview.loading')}
            </p>
        );
    }
    if (preview.status === 'throttled') {
        return (
            <p role="status" className="text-xs leading-relaxed text-muted-foreground">
                {t('scope.preview.throttled')}
            </p>
        );
    }
    if (preview.status === 'unavailable') {
        return (
            <p role="status" className="text-xs leading-relaxed text-muted-foreground">
                {t('scope.preview.unavailable')}
            </p>
        );
    }
    if (preview.status === 'failed') {
        return (
            <p role="alert" className="text-xs leading-relaxed text-destructive">
                {t('scope.preview.failed')}
            </p>
        );
    }
    if (preview.status === 'refused') {
        return (
            <p role="alert" className="text-xs leading-relaxed text-destructive">
                {preview.reason === null
                    ? t('scope.reasons.unknown')
                    : t(`scope.reasons.${preview.reason}`)}
            </p>
        );
    }

    const { scope } = preview;
    const disclosures = askConnexScopeDisclosures(scope);
    const capability = skills.find((skill) => skill.key === preview.skillKey);
    return (
        <div className="space-y-1.5">
            <p role="status" className="text-xs leading-relaxed text-foreground">
                {scope.matchedRecordCount === null
                    ? t('scope.preview.matchedUnknown')
                    : t('scope.preview.matched', { count: scope.matchedRecordCount })}
                {scope.matchedRecordCountTruncated
                    ? ` ${t('scope.preview.truncated', { count: scope.recordCap })}`
                    : ''}
                {capability === undefined
                    ? ''
                    : ` ${t('scope.preview.capability', { name: tRoot(capability.nameKey) })}`}
            </p>
            {disclosures.length > 0 ? (
                <ul className="space-y-1">
                    {disclosures.map((reason) => (
                        <li key={reason} className="text-xs leading-relaxed text-muted-foreground">
                            {t(`scope.reasons.${reason}`)}
                        </li>
                    ))}
                </ul>
            ) : null}
        </div>
    );
}

/**
 * Where the workspace's own names have got to: still being read, read, or not read at all.
 *
 * Held apart from the names themselves because "none yet" and "none at all" are different answers to
 * the same question, and a request that failed must never settle into the second one — a form that
 * says a workspace has no members when nobody could ask is stating something untrue about it.
 */
export type AskConnexScopeOptionsStatus = 'loading' | 'ready' | 'failed';

/**
 * What became of the read behind the choices, stated once for the whole form.
 *
 * One line rather than one per field, because the members, stages, and saved views are read together
 * and a member who cannot see any of them has one thing to know and one thing to do about it. Says
 * nothing at all once the names are there, so the ordinary case carries no chrome.
 */
function OptionsStatus({ status, onRetry }: {
    status: AskConnexScopeOptionsStatus;
    onRetry: () => void;
}) {
    const t = useTranslations('AskConnex');

    if (status === 'ready') return null;
    if (status === 'loading') {
        return (
            <p role="status" className="text-xs leading-relaxed text-muted-foreground">
                {t('scope.options.loading')}
            </p>
        );
    }
    return (
        <div className="flex flex-wrap items-center gap-2">
            <p role="alert" className="text-xs leading-relaxed text-destructive">
                {t('scope.options.failed')}
            </p>
            <Button type="button" size="inline" variant="outline" onClick={onRetry}>
                {t('scope.options.retry')}
            </Button>
        </div>
    );
}

/**
 * The filter form itself.
 *
 * Ordered by how often a member reaches for each filter, and conditional fields appear rather than
 * being shown disabled: stages and deal status are refused outright against a cohort with no deals
 * in it, so they are offered only where they can be honoured.
 *
 * A field whose choices are workspace names is offered once those names are there. While they are
 * still being read, or after the read failed, the form says so and offers the read again instead of
 * presenting an absence it never established.
 */
export function AskConnexScopeFields({
    draft,
    options,
    optionsStatus,
    onChange,
    onRetryOptions,
}: {
    draft: AskConnexScopeDraft;
    options: AskConnexScopeOptions;
    optionsStatus: AskConnexScopeOptionsStatus;
    onChange: (draft: AskConnexScopeDraft) => void;
    onRetryOptions: () => void;
}) {
    const t = useTranslations('AskConnex');
    const tWarmth = useTranslations('Temperature');
    const problem = askConnexScopeProblem(draft);
    const allowsDeals = askConnexScopeAllowsDeals(draft);
    const stageLabels = askConnexScopeStageLabels(options.stages, options.pipelines);
    const savedViewLabels = askConnexScopeSavedViewLabels(
        options.savedViews,
        (recordType: SavedViewRecordType) => t(`scope.recordKinds.${recordType}`),
    );
    const selectedMemberIds = new Set(draft.ownerMemberIds);
    const selectedStageIds = new Set(draft.stageIds);
    const membersAtCap = draft.ownerMemberIds.length >= ASK_CONNEX_SCOPE_MAX_MEMBERS;
    const stagesAtCap = draft.stageIds.length >= ASK_CONNEX_SCOPE_MAX_STAGES;
    const periodModes: { value: AskConnexScopePeriodMode; label: string }[] = [
        { value: 'any', label: t('scope.period.any') },
        { value: 'days', label: t('scope.period.recent') },
        { value: 'range', label: t('scope.period.range') },
    ];
    const ownerModes: { value: AiChatScopeOwnerMode; label: string }[] = [
        { value: 'all_team', label: t('scope.owners.allTeam') },
        { value: 'me', label: t('scope.owners.me') },
        { value: 'members', label: t('scope.owners.members') },
    ];

    return (
        <div className="space-y-4">
            <Field label={t('scope.period.label')}>
                <SegmentedControl
                    size="inline"
                    ariaLabel={t('scope.period.modeLabel')}
                    value={draft.periodMode}
                    options={periodModes}
                    onChange={(periodMode) => onChange({ ...draft, periodMode })}
                />
                {draft.periodMode === 'days' ? (
                    <div className="flex flex-wrap gap-1.5">
                        {ASK_CONNEX_SCOPE_PERIOD_PRESETS.map((days) => (
                            <ChoiceButton
                                key={days}
                                label={t('scope.period.days', { count: days })}
                                selected={draft.periodDays === days}
                                onSelect={() => onChange({
                                    ...draft,
                                    periodDays: draft.periodDays === days ? null : days,
                                })}
                            />
                        ))}
                    </div>
                ) : null}
                {draft.periodMode === 'range' ? (
                    <div className="flex flex-wrap items-end gap-2">
                        <div className="min-w-32 flex-1 space-y-1">
                            <Label htmlFor="ask-connex-scope-start" className="text-xs font-normal text-muted-foreground">
                                {t('scope.period.start')}
                            </Label>
                            <Input
                                id="ask-connex-scope-start"
                                type="date"
                                value={draft.periodStart}
                                aria-invalid={problem === 'periodOrder' || undefined}
                                onChange={(event) => onChange({ ...draft, periodStart: event.target.value })}
                            />
                        </div>
                        <div className="min-w-32 flex-1 space-y-1">
                            <Label htmlFor="ask-connex-scope-end" className="text-xs font-normal text-muted-foreground">
                                {t('scope.period.end')}
                            </Label>
                            <Input
                                id="ask-connex-scope-end"
                                type="date"
                                value={draft.periodEnd}
                                aria-invalid={problem === 'periodOrder' || undefined}
                                onChange={(event) => onChange({ ...draft, periodEnd: event.target.value })}
                            />
                        </div>
                    </div>
                ) : null}
                {problem === 'periodOrder' || problem === 'periodLength' ? (
                    <p role="alert" className="text-xs text-destructive">{t(`scope.problem.${problem}`)}</p>
                ) : null}
            </Field>

            <Field label={t('scope.owners.label')}>
                <SegmentedControl
                    size="inline"
                    ariaLabel={t('scope.owners.modeLabel')}
                    value={draft.ownerMode}
                    options={ownerModes}
                    onChange={(ownerMode) => onChange({
                        ...draft,
                        ownerMode,
                        ownerMemberIds: ownerMode === 'members' ? draft.ownerMemberIds : [],
                    })}
                />
                {draft.ownerMode === 'members' ? (
                    <div className="max-h-40 space-y-1 overflow-y-auto">
                        {options.members.map((member) => {
                            const selected = selectedMemberIds.has(member.id);
                            const atCap = membersAtCap;
                            return (
                                <Button
                                    key={member.id}
                                    type="button"
                                    size="inline"
                                    variant="ghost"
                                    aria-pressed={selected}
                                    disabled={!selected && atCap}
                                    onClick={() => onChange({
                                        ...draft,
                                        ownerMemberIds: toggle(draft.ownerMemberIds, member.id),
                                    })}
                                    className="h-auto w-full justify-start py-1.5 font-normal"
                                >
                                    <CheckIcon
                                        aria-hidden
                                        className={cn('size-3.5 shrink-0 text-brand-dark', !selected && 'invisible')}
                                    />
                                    <span className="min-w-0 truncate">{member.displayName}</span>
                                </Button>
                            );
                        })}
                        {optionsStatus === 'ready' && options.members.length === 0 ? (
                            <p role="status" className="text-xs text-muted-foreground">
                                {t('scope.owners.none')}
                            </p>
                        ) : null}
                        {problem === 'membersMissing' ? (
                            <p role="alert" className="text-xs text-destructive">{t('scope.problem.membersMissing')}</p>
                        ) : null}
                        {membersAtCap ? (
                            <p role="status" className="text-xs text-muted-foreground">
                                {t('scope.owners.cap', { count: ASK_CONNEX_SCOPE_MAX_MEMBERS })}
                            </p>
                        ) : null}
                    </div>
                ) : null}
            </Field>

            <Field label={t('scope.warmth.label')}>
                <div className="flex flex-wrap gap-1.5">
                    {ASK_CONNEX_SCOPE_WARMTH_BANDS.map((band: AiChatScopeWarmthBand) => (
                        <ChoiceButton
                            key={band}
                            label={tWarmth(band)}
                            selected={draft.warmthBands.includes(band)}
                            onSelect={() => onChange({
                                ...draft,
                                warmthBands: toggle(draft.warmthBands, band),
                            })}
                        />
                    ))}
                </div>
            </Field>

            <Field label={t('scope.recordKinds.label')}>
                <div className="flex flex-wrap gap-1.5">
                    {ASK_CONNEX_SCOPE_RECORD_KINDS.map((kind: AiChatPageContextKind) => (
                        <ChoiceButton
                            key={kind}
                            label={t(`scope.recordKinds.${kind}`)}
                            selected={draft.recordKinds.includes(kind)}
                            onSelect={() => onChange(withAskConnexScopeRecordKinds(
                                draft,
                                toggle(draft.recordKinds, kind),
                            ))}
                        />
                    ))}
                </div>
            </Field>

            {allowsDeals ? (
                <Field label={t('scope.dealStatuses.label')}>
                    <div className="flex flex-wrap gap-1.5">
                        {ASK_CONNEX_SCOPE_DEAL_STATUSES.map((status: AiChatScopeDealStatus) => (
                            <ChoiceButton
                                key={status}
                                label={t(`scope.dealStatuses.${status}`)}
                                selected={draft.dealStatuses.includes(status)}
                                onSelect={() => onChange({
                                    ...draft,
                                    dealStatuses: toggle(draft.dealStatuses, status),
                                })}
                            />
                        ))}
                    </div>
                </Field>
            ) : null}

            {allowsDeals && options.stages.length > 0 ? (
                <Field label={t('scope.stages.label')}>
                    <div className="flex flex-wrap gap-1.5">
                        {options.stages.map((stage) => {
                            const selected = selectedStageIds.has(stage.id);
                            return (
                                <ChoiceButton
                                    key={stage.id}
                                    label={stageLabels.get(stage.id) ?? stage.name}
                                    selected={selected}
                                    disabled={!selected && stagesAtCap}
                                    onSelect={() => onChange({
                                        ...draft,
                                        stageIds: toggle(draft.stageIds, stage.id),
                                    })}
                                />
                            );
                        })}
                    </div>
                    {stagesAtCap ? (
                        <p role="status" className="text-xs text-muted-foreground">
                            {t('scope.stages.cap', { count: ASK_CONNEX_SCOPE_MAX_STAGES })}
                        </p>
                    ) : null}
                </Field>
            ) : null}

            {options.savedViews.length > 0 ? (
                <Field label={t('scope.savedView.label')}>
                    <div className="flex flex-wrap gap-1.5">
                        {options.savedViews.map((view) => (
                            <ChoiceButton
                                key={`${view.recordType}:${view.id}`}
                                label={savedViewLabels.get(view.id) ?? view.name}
                                selected={draft.savedViewId === view.id}
                                onSelect={() => onChange({
                                    ...draft,
                                    savedViewId: draft.savedViewId === view.id ? null : view.id,
                                })}
                            />
                        ))}
                    </div>
                </Field>
            ) : null}

            <OptionsStatus status={optionsStatus} onRetry={onRetryOptions} />
        </div>
    );
}

/**
 * The declared-scope editor: which records a question covers, and what that turns out to mean.
 *
 * An overlay rather than an inline panel because the panel it would live in is a reading column: a
 * form that unfolds inside it would push the conversation off screen every time someone narrowed a
 * question. It commits to a dialog on a pointer and a bottom sheet on a phone through the shared
 * responsive primitive, like every other short focused operation in the product.
 *
 * The form never confirms a request. It states what the filters cover and hands that back; agreeing
 * to send remains the one confirmation the composer already owns.
 */
export default function AskConnexScopeEditor({
    open,
    draft,
    preview,
    skills,
    onOpenChange,
    onDraftChange,
}: {
    open: boolean;
    draft: AskConnexScopeDraft;
    preview: AskConnexScopePreviewState;
    skills: readonly AiAssistantSkill[];
    onOpenChange: (open: boolean) => void;
    onDraftChange: (draft: AskConnexScopeDraft) => void;
}) {
    const t = useTranslations('AskConnex');
    const { activeWorkspaceId, switching } = useWorkspace();
    const [attempt, setAttempt] = useState(0);
    const [loaded, setLoaded] = useState<{
        workspaceId: number;
        attempt: number;
        failed: boolean;
        options: AskConnexScopeOptions;
    } | null>(null);
    const options = askConnexScopeOptionsFor(loaded, activeWorkspaceId);
    const settled = loaded !== null
        && loaded.workspaceId === activeWorkspaceId
        && loaded.attempt === attempt;
    const optionsStatus: AskConnexScopeOptionsStatus = settled
        ? (loaded.failed ? 'failed' : 'ready')
        : 'loading';

    /**
     * Reads the names this workspace offers as choices.
     *
     * Keyed on the workspace rather than read once, because switching workspaces is a client-side
     * transition this component survives: every name here belongs to the workspace whose permissions
     * produced it, so the previous workspace's members, stages, and saved views must leave the form
     * the moment another one becomes active rather than staying on offer as filters that would be
     * refused — or, worse, silently applied to records they never described.
     *
     * A read that did not come back whole is recorded as having failed rather than as a workspace
     * with nothing in it, and the attempt is part of the key, so asking again really does ask again.
     * Only a settled read closes the door on another one: while one is in flight the guard here has
     * nothing to match, which is what keeps this from cancelling its own request.
     */
    useEffect(() => {
        if (!open || switching || activeWorkspaceId === null) return;
        if (loaded?.workspaceId === activeWorkspaceId && loaded.attempt === attempt) return;
        const controller = new AbortController();
        const load = async () => {
            const reads = await Promise.allSettled([
                getActiveWorkspaceMembers({ signal: controller.signal }),
                getPipelines({ signal: controller.signal }),
                getAllStages({ signal: controller.signal }),
                getSavedViews('person', { signal: controller.signal }),
                getSavedViews('company', { signal: controller.signal }),
                getSavedViews('deal', { signal: controller.signal }),
            ]);
            if (controller.signal.aborted) return;
            const [members, pipelines, stages, personViews, companyViews, dealViews] = reads;
            setLoaded({
                workspaceId: activeWorkspaceId,
                attempt,
                failed: reads.some((read) => read.status === 'rejected'),
                options: {
                    members: members.status === 'fulfilled'
                        ? members.value.filter((member) => member.status === 'active')
                        : [],
                    pipelines: pipelines.status === 'fulfilled' ? pipelines.value : [],
                    stages: stages.status === 'fulfilled' ? stages.value : [],
                    savedViews: [
                        ...(personViews.status === 'fulfilled' ? personViews.value : []),
                        ...(companyViews.status === 'fulfilled' ? companyViews.value : []),
                        ...(dealViews.status === 'fulfilled' ? dealViews.value : []),
                    ],
                },
            });
        };
        void load();
        return () => controller.abort();
    }, [activeWorkspaceId, attempt, loaded, open, switching]);

    return (
        <ResponsiveDialog open={open} onOpenChange={onOpenChange}>
            <ResponsiveDialogContent className="sm:max-w-lg">
                <ResponsiveDialogHeader className="px-4 pt-4 sm:px-0 sm:pt-0">
                    <ResponsiveDialogTitle>{t('scope.title')}</ResponsiveDialogTitle>
                    <ResponsiveDialogDescription>{t('scope.description')}</ResponsiveDialogDescription>
                </ResponsiveDialogHeader>
                <div
                    data-base-ui-swipe-ignore
                    className="max-h-[60vh] space-y-4 overflow-y-auto px-4 py-4 sm:px-0"
                >
                    <AskConnexScopeFields
                        draft={draft}
                        options={options}
                        optionsStatus={optionsStatus}
                        onChange={onDraftChange}
                        onRetryOptions={() => setAttempt((current) => current + 1)}
                    />
                    <div className="rounded-lg border border-border bg-muted/40 p-3">
                        <p className="mb-1 text-xs font-medium text-foreground">{t('scope.preview.title')}</p>
                        <AskConnexScopeSummary preview={preview} skills={skills} />
                    </div>
                </div>
                <ResponsiveDialogFooter className="border-t border-border px-4 py-4 sm:border-0 sm:px-0 sm:py-0">
                    <Button
                        type="button"
                        variant="ghost"
                        size="dialog"
                        disabled={!askConnexScopeDeclared(draft)}
                        onClick={() => onDraftChange(clearedAskConnexScopeDraft())}
                    >
                        {t('scope.clear')}
                    </Button>
                    <ResponsiveDialogClose asChild>
                        <Button type="button" size="dialog">{t('scope.done')}</Button>
                    </ResponsiveDialogClose>
                </ResponsiveDialogFooter>
            </ResponsiveDialogContent>
        </ResponsiveDialog>
    );
}
