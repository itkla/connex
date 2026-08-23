import type {
    AiChatPageContextKind,
    AiChatQueryScope,
    AiChatQueryScopeRequest,
    AiChatScopeDealStatus,
    AiChatScopeOwnerMode,
    AiChatScopeWarmthBand,
    Pipeline,
    SavedView,
    Stage,
    WorkspaceMember,
} from '@/app/lib/types';

/** The widest trailing window a declared scope may cover, matching the server's own ceiling. */
export const ASK_CONNEX_SCOPE_MAX_PERIOD_DAYS = 365;

/** Trailing windows the editor offers directly, in days. */
export const ASK_CONNEX_SCOPE_PERIOD_PRESETS: readonly number[] = [7, 30, 90, 365];

/** Warmth bands a declared scope may cover, warmest first. */
export const ASK_CONNEX_SCOPE_WARMTH_BANDS: readonly AiChatScopeWarmthBand[] = [
    'hot',
    'warm',
    'cool',
    'cold',
];

/** Record types a declared scope may cover. */
export const ASK_CONNEX_SCOPE_RECORD_KINDS: readonly AiChatPageContextKind[] = [
    'person',
    'company',
    'deal',
];

/** Deal statuses a declared scope may cover. */
export const ASK_CONNEX_SCOPE_DEAL_STATUSES: readonly AiChatScopeDealStatus[] = [
    'open',
    'won',
    'lost',
];

/** Most members a declared scope may name, matching the server's own cap. */
export const ASK_CONNEX_SCOPE_MAX_MEMBERS = 50;

/**
 * Most stages a declared scope may name, matching the server's own cap.
 *
 * Held here as well as there because the server rejects a longer list with a validation failure that
 * names no reason the member could act on, which would reach the screen as an unexplained dead end.
 * The form stops at the same number and says so instead.
 */
export const ASK_CONNEX_SCOPE_MAX_STAGES = 20;

/** How a period is stated: not at all, as a trailing window, or as two dates. */
export type AskConnexScopePeriodMode = 'any' | 'days' | 'range';

/**
 * What the member has said about the records a request should cover, as the editor holds it.
 *
 * This is deliberately the editor's own shape rather than the request body: it keeps a half-written
 * date range and a period mode the request has no field for, so the form can be corrected in place
 * instead of losing what was typed the moment it stops being valid.
 */
export type AskConnexScopeDraft = {
    periodMode: AskConnexScopePeriodMode;
    periodDays: number | null;
    periodStart: string;
    periodEnd: string;
    ownerMode: AiChatScopeOwnerMode;
    ownerMemberIds: readonly number[];
    warmthBands: readonly AiChatScopeWarmthBand[];
    recordKinds: readonly AiChatPageContextKind[];
    dealStatuses: readonly AiChatScopeDealStatus[];
    stageIds: readonly number[];
    savedViewId: number | null;
};

/** No filters: the request covers whatever the question and its context imply. */
export const EMPTY_ASK_CONNEX_SCOPE_DRAFT: AskConnexScopeDraft = {
    periodMode: 'any',
    periodDays: null,
    periodStart: '',
    periodEnd: '',
    ownerMode: 'all_team',
    ownerMemberIds: [],
    warmthBands: [],
    recordKinds: [],
    dealStatuses: [],
    stageIds: [],
    savedViewId: null,
};

/**
 * The draft the editor's "Clear filters" hands back.
 *
 * The pristine draft in one step, rather than unsetting the fields the form happens to be showing:
 * the deal-only filters are hidden while the record types exclude deals, and a clear assembled from
 * the visible fields would leave a stage or a deal status set behind the form that no longer offers
 * it, which the next request would then carry without ever having said so.
 */
export function clearedAskConnexScopeDraft(): AskConnexScopeDraft {
    return EMPTY_ASK_CONNEX_SCOPE_DRAFT;
}

/**
 * The options the editor offers, as the workspace they were read from provides them.
 *
 * Member names, stage names, and saved views are all workspace-owned, so they travel with the
 * workspace they came from and never outlive it.
 */
export type AskConnexScopeOptions = {
    members: WorkspaceMember[];
    pipelines: Pipeline[];
    stages: Stage[];
    savedViews: SavedView[];
};

/** No options read yet, and what a workspace whose options do not belong to it shows instead. */
export const NO_ASK_CONNEX_SCOPE_OPTIONS: AskConnexScopeOptions = {
    members: [],
    pipelines: [],
    stages: [],
    savedViews: [],
};

/**
 * The options that may be shown in the workspace currently active.
 *
 * Switching workspaces is a client-side transition, so the editor's loaded options survive it in
 * memory. Names read under one workspace's permissions must never be offered as choices under
 * another's, so options are held with the workspace that produced them and anything else resolves
 * to none at all until that workspace's own options have been read.
 *
 * @param loaded the options already read, together with the workspace they were read from
 * @param activeWorkspaceId the workspace the editor is being shown in
 * @returns the options that belong to the active workspace, or none
 */
export function askConnexScopeOptionsFor(
    loaded: { workspaceId: number; options: AskConnexScopeOptions } | null,
    activeWorkspaceId: number | null,
): AskConnexScopeOptions {
    if (loaded === null || activeWorkspaceId === null) return NO_ASK_CONNEX_SCOPE_OPTIONS;
    return loaded.workspaceId === activeWorkspaceId ? loaded.options : NO_ASK_CONNEX_SCOPE_OPTIONS;
}

/**
 * How each stage is named in the editor.
 *
 * Pipelines routinely reuse the same stage names, and two chips reading "Proposal" that filter
 * different pipelines is a choice nobody can make correctly. A name that occurs in more than one
 * pipeline therefore carries the pipeline it belongs to; a name that is already unique is left
 * alone rather than padded with a qualifier that distinguishes nothing.
 *
 * @param stages every stage the workspace offers
 * @param pipelines the pipelines those stages belong to, for the names the stage list omits
 * @returns each stage's id mapped to the label the editor shows for it
 */
export function askConnexScopeStageLabels(
    stages: readonly Stage[],
    pipelines: readonly Pipeline[],
): Map<number, string> {
    const pipelineNames = new Map(pipelines.map((pipeline) => [pipeline.id, pipeline.name]));
    const nameCounts = new Map<string, number>();
    for (const stage of stages) {
        nameCounts.set(stage.name, (nameCounts.get(stage.name) ?? 0) + 1);
    }
    const labels = new Map<number, string>();
    for (const stage of stages) {
        const pipelineName = pipelineNames.get(stage.pipeline);
        labels.set(
            stage.id,
            (nameCounts.get(stage.name) ?? 0) > 1 && pipelineName !== undefined
                ? `${pipelineName} · ${stage.name}`
                : stage.name,
        );
    }
    return labels;
}

const ISO_DATE = /^\d{4}-\d{2}-\d{2}$/;

function declaredPeriod(draft: AskConnexScopeDraft): boolean {
    if (draft.periodMode === 'days') return draft.periodDays !== null;
    if (draft.periodMode === 'range') {
        return ISO_DATE.test(draft.periodStart) || ISO_DATE.test(draft.periodEnd);
    }
    return false;
}

/**
 * Whether deal-only filters may be offered.
 *
 * Stages and deal statuses are refused outright against a cohort that excludes deals, so the editor
 * offers them only where they can be honoured — a control that is present but always refused is
 * worse than one that is not there.
 */
export function askConnexScopeAllowsDeals(draft: AskConnexScopeDraft): boolean {
    return draft.recordKinds.length === 0 || draft.recordKinds.includes('deal');
}

/**
 * Applies a record-type selection, dropping the deal-only filters it would invalidate.
 *
 * Narrowing to contacts while a stage is still selected would produce a request the server refuses,
 * so the selection that caused it takes the dependent filters with it rather than leaving the member
 * to discover the contradiction from a refusal.
 */
export function withAskConnexScopeRecordKinds(
    draft: AskConnexScopeDraft,
    recordKinds: readonly AiChatPageContextKind[],
): AskConnexScopeDraft {
    const next = { ...draft, recordKinds: [...recordKinds] };
    return askConnexScopeAllowsDeals(next)
        ? next
        : { ...next, dealStatuses: [], stageIds: [] };
}

/** Whether the member has stated any filter at all. */
export function askConnexScopeDeclared(draft: AskConnexScopeDraft): boolean {
    return declaredPeriod(draft)
        || draft.ownerMode !== 'all_team'
        || draft.warmthBands.length > 0
        || draft.recordKinds.length > 0
        || draft.dealStatuses.length > 0
        || draft.stageIds.length > 0
        || draft.savedViewId !== null;
}

/** How many filters are set, for the summary the cockpit control carries. */
export function askConnexScopeFilterCount(draft: AskConnexScopeDraft): number {
    let count = 0;
    if (declaredPeriod(draft)) count += 1;
    if (draft.ownerMode !== 'all_team') count += 1;
    if (draft.warmthBands.length > 0) count += 1;
    if (draft.recordKinds.length > 0) count += 1;
    if (draft.dealStatuses.length > 0) count += 1;
    if (draft.stageIds.length > 0) count += 1;
    if (draft.savedViewId !== null) count += 1;
    return count;
}

/**
 * The problem in the draft a member can fix in the form, or null when there is none.
 *
 * Only the mistakes the client can be certain about are reported here: a backwards date range and a
 * window outside the accepted length. Everything else is the server's judgement and is reported from
 * its own answer, because a second copy of those rules here would eventually disagree with them.
 */
export type AskConnexScopeProblem = 'periodOrder' | 'periodLength' | 'membersMissing';

/** Validates what the form itself can decide, so a fixable mistake never becomes a refusal. */
export function askConnexScopeProblem(draft: AskConnexScopeDraft): AskConnexScopeProblem | null {
    if (draft.periodMode === 'range'
        && ISO_DATE.test(draft.periodStart)
        && ISO_DATE.test(draft.periodEnd)
        && draft.periodStart > draft.periodEnd) {
        return 'periodOrder';
    }
    if (draft.periodMode === 'days'
        && draft.periodDays !== null
        && (draft.periodDays < 1 || draft.periodDays > ASK_CONNEX_SCOPE_MAX_PERIOD_DAYS)) {
        return 'periodLength';
    }
    if (draft.ownerMode === 'members' && draft.ownerMemberIds.length === 0) {
        return 'membersMissing';
    }
    return null;
}

/**
 * Converts the editor's state into the request the server validates, or null when nothing was
 * declared or the draft still carries a problem the member can fix.
 *
 * Only stated filters are sent. An unstated filter is absent rather than sent as an empty list, so
 * "no owner filter" and "an owner filter that happens to name nobody" stay distinguishable all the
 * way to the interpretation that comes back.
 */
export function askConnexScopeRequest(
    draft: AskConnexScopeDraft,
): AiChatQueryScopeRequest | null {
    if (!askConnexScopeDeclared(draft) || askConnexScopeProblem(draft) !== null) return null;
    const request: AiChatQueryScopeRequest = {};
    if (draft.periodMode === 'days' && draft.periodDays !== null) {
        request.periodDays = draft.periodDays;
    }
    if (draft.periodMode === 'range') {
        if (ISO_DATE.test(draft.periodStart)) request.periodStart = draft.periodStart;
        if (ISO_DATE.test(draft.periodEnd)) request.periodEnd = draft.periodEnd;
    }
    if (draft.ownerMode !== 'all_team') {
        request.ownerMode = draft.ownerMode;
        if (draft.ownerMode === 'members') {
            request.ownerMemberIds = [...draft.ownerMemberIds];
        }
    }
    if (draft.warmthBands.length > 0) request.warmthBands = [...draft.warmthBands];
    if (draft.recordKinds.length > 0) request.recordKinds = [...draft.recordKinds];
    if (draft.dealStatuses.length > 0) request.dealStatuses = [...draft.dealStatuses];
    if (draft.stageIds.length > 0) request.stageIds = [...draft.stageIds];
    if (draft.savedViewId !== null) request.savedViewId = draft.savedViewId;
    return request;
}

/**
 * Every reason the server gives for a filter it could not apply or a scope it will not run.
 *
 * Each one is stated to the member in plain language: a reason nobody translated would reach the
 * screen as an internal code, which is the one thing an explanation may never be. The list is
 * exported so a test can enumerate it and fail when a reason arrives without copy.
 */
export const ASK_CONNEX_SCOPE_REASONS = [
    'deal_status_unsupported_for_attention',
    'period_capped',
    'record_kind_ambiguous_for_cohort',
    'record_kind_outside_declared_scope',
    'saved_view_scope_changed',
    'saved_view_scope_unsupported',
    'stage_scope_unsupported_for_cohort',
    'tool_cannot_honor_declared_scope',
    'warmth_outside_declared_scope',
    'warmth_unsupported_for_deals',
] as const;

/** One reason a declared filter was capped, dropped, or refused. */
export type AskConnexScopeReason = (typeof ASK_CONNEX_SCOPE_REASONS)[number];

const SCOPE_REASONS: ReadonlySet<string> = new Set(ASK_CONNEX_SCOPE_REASONS);

/** Narrows one disclosed reason, discarding anything this build has no explanation for. */
export function askConnexScopeReason(value: string): AskConnexScopeReason | null {
    return SCOPE_REASONS.has(value) ? (value as AskConnexScopeReason) : null;
}

/**
 * One disclosed reason as the summary states it: a named one, or the generic line.
 *
 * `other` is not a reason the contract produces. It is what this client says about a reason it does
 * not recognize, so a filter the server declared it could not apply is always stated as such.
 */
export type AskConnexScopeDisclosure = AskConnexScopeReason | 'other';

/**
 * The disclosed reasons the summary states, in the order the server disclosed them.
 *
 * {@link ASK_CONNEX_SCOPE_REASONS} is enumerated client-side, so it is this build's picture of the
 * contract rather than the contract itself. A reason added to the server after this client shipped
 * therefore degrades to the generic line rather than disappearing: a filter the server says it could
 * not apply must never be silently dropped, because the answer would then contradict a scope nobody
 * was told had changed. The generic line is stated once however many unrecognized reasons arrive —
 * repeating the same sentence adds no information.
 *
 * @param scope the server's interpretation of the declared filters
 * @returns each disclosure to state, deduplicated
 */
export function askConnexScopeDisclosures(
    scope: AiChatQueryScope | null,
): AskConnexScopeDisclosure[] {
    if (scope === null) return [];
    const disclosures: AskConnexScopeDisclosure[] = [];
    for (const entry of scope.unavailable) {
        const disclosure: AskConnexScopeDisclosure = askConnexScopeReason(entry) ?? 'other';
        if (!disclosures.includes(disclosure)) disclosures.push(disclosure);
    }
    return disclosures;
}

/**
 * Reads the reason out of a refused scope.
 *
 * The server states the reason as the last word of a message it also writes for its own logs, so
 * only the recognized vocabulary is taken from it and the message itself never reaches the screen.
 * An unrecognized refusal yields null and is explained in general terms instead of being echoed.
 */
export function askConnexScopeRefusal(message: string | null | undefined): AskConnexScopeReason | null {
    if (typeof message !== 'string') return null;
    const separator = message.lastIndexOf(': ');
    const candidate = (separator < 0 ? message : message.slice(separator + 2)).trim();
    return askConnexScopeReason(candidate);
}

/** One filter the interpreted scope actually carries, as the cockpit shows it. */
export type AskConnexScopeChip = {
    /** Stable identity for the chip, unique within one interpreted scope. */
    key: string;
    /** Which filter it is, so its owner can title it in the member's language. */
    kind: 'period' | 'owners' | 'warmth' | 'recordKinds' | 'dealStatuses' | 'stages' | 'savedView';
    /** The values it names, already resolved to labels the server authorized. */
    values: string[];
};

/**
 * Turns an interpreted scope into the chips the cockpit shows.
 *
 * Built from the server's interpretation rather than the draft, so a chip can only ever state a
 * filter the request will really apply — including a period the server capped, which arrives here
 * as the capped window and not the one that was asked for.
 */
export function askConnexScopeChips(scope: AiChatQueryScope | null): AskConnexScopeChip[] {
    if (scope === null || !scope.declared) return [];
    const chips: AskConnexScopeChip[] = [];
    if (scope.periodStart !== null || scope.periodEnd !== null || scope.periodDays !== null) {
        chips.push({
            key: 'period',
            kind: 'period',
            values: scope.periodStart !== null && scope.periodEnd !== null
                ? [scope.periodStart, scope.periodEnd]
                : [],
        });
    }
    if (scope.ownerMode !== 'all_team') {
        chips.push({
            key: 'owners',
            kind: 'owners',
            values: scope.owners.map((owner) => owner.label).filter((label) => label.length > 0),
        });
    }
    if (scope.warmthBands.length > 0) {
        chips.push({ key: 'warmth', kind: 'warmth', values: [...scope.warmthBands] });
    }
    if (scope.recordKinds.length > 0) {
        chips.push({ key: 'recordKinds', kind: 'recordKinds', values: [...scope.recordKinds] });
    }
    if (scope.dealStatuses.length > 0) {
        chips.push({ key: 'dealStatuses', kind: 'dealStatuses', values: [...scope.dealStatuses] });
    }
    if (scope.stages.length > 0) {
        chips.push({
            key: 'stages',
            kind: 'stages',
            values: scope.stages.map((stage) => stage.label).filter((label) => label.length > 0),
        });
    }
    if (scope.savedView !== null) {
        chips.push({ key: 'savedView', kind: 'savedView', values: [scope.savedView.label] });
    }
    return chips;
}

function calendarDate(value: string): Date | null {
    if (!ISO_DATE.test(value)) return null;
    const date = new Date(`${value}T00:00:00Z`);
    return Number.isNaN(date.getTime()) ? null : date;
}

/**
 * States the period a chip carries as one date range in the member's language.
 *
 * A period is one span, not two dates that happen to be listed together, so it is written as the
 * range the request will read — and as dates the member recognizes rather than the wire format the
 * contract exchanges them in. Anything that is not a pair of calendar dates yields nothing, and the
 * chip states its own name alone rather than a half-formed span.
 *
 * @param values the interpreted period's start and end, as the server stated them
 * @param locale the member's locale
 * @returns the formatted range, or an empty string when there is no whole range to state
 */
export function askConnexScopePeriodLabel(values: readonly string[], locale: string): string {
    if (values.length !== 2) return '';
    const start = calendarDate(values[0]);
    const end = calendarDate(values[1]);
    if (start === null || end === null || start > end) return '';
    return new Intl.DateTimeFormat(locale, { dateStyle: 'medium', timeZone: 'UTC' })
        .formatRange(start, end);
}

/**
 * How long the editor waits before asking the server what a scope covers.
 *
 * The preview evaluates a whole cohort and is rate limited, so it follows a settled selection rather
 * than every keystroke and checkbox on the way to one.
 */
export const ASK_CONNEX_SCOPE_PREVIEW_DEBOUNCE_MS = 400;

/** What the interpreted-scope preview is currently doing, as the editor renders it. */
export type AskConnexScopePreviewState =
    | { status: 'idle' }
    | { status: 'loading' }
    | {
        status: 'ready';
        scope: AiChatQueryScope;
        /** The declared capability that would run, or null when the general loop would answer. */
        skillKey: string | null;
        confirmationRecommended: boolean;
    }
    | { status: 'refused'; reason: AskConnexScopeReason | null }
    | { status: 'throttled' }
    | { status: 'unavailable' }
    | { status: 'failed' };

/**
 * Folds the server's echo of the scope it just accepted into what the preview already established.
 *
 * The echo is authoritative about how the filters were interpreted — the window it settled on, the
 * owners and stages it resolved, and every filter it could not apply — so those replace what the
 * preview guessed. It is not an answer to the questions the preview asked: it carries no cohort
 * count, does not name the capability that will run, and does not restate whether this breadth was
 * worth reviewing. Taking it whole would therefore erase the count the member was shown and disarm
 * the confirmation for every later question against the same filters, so those three are kept.
 *
 * An echo that arrives with no settled preview behind it — a count that was rate limited or failed —
 * changes nothing. Promoting it would replace an honest "the count could not be checked" with a
 * claim the filters are ready that nothing ever verified.
 *
 * @param current what the preview established for exactly these filters
 * @param accepted the scope the server echoed back with the accepted turn
 * @returns the interpretation to hold from here
 */
export function askConnexScopeAccepted(
    current: AskConnexScopePreviewState,
    accepted: AiChatQueryScope,
): AskConnexScopePreviewState {
    if (current.status !== 'ready') return current;
    return {
        status: 'ready',
        scope: {
            ...accepted,
            matchedRecordCount: current.scope.matchedRecordCount,
            matchedRecordCountTruncated: current.scope.matchedRecordCountTruncated,
        },
        skillKey: current.skillKey,
        confirmationRecommended: current.confirmationRecommended,
    };
}
