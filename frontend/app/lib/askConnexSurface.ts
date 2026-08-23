import { viewPreferenceStorageKey } from '@/app/hooks/viewPreference';
import type { AiChatSession } from '@/app/lib/types';
import { parseMysqlDateTime } from '@/app/lib/utils';

/**
 * How wide the quick drawer runs.
 *
 * Two discrete states rather than a drag handle: a freeform width is a preference the user has to
 * re-establish on every screen, and the only two widths that matter are "beside the record I am
 * reading" and "wide enough to read an answer document". Anything wider than comfortable is the
 * full workspace's job.
 */
export type AskConnexWidth = 'compact' | 'comfortable';

/** The width states, in the order the control offers them. */
export const ASK_CONNEX_WIDTHS: readonly AskConnexWidth[] = ['compact', 'comfortable'];

/** The default width for a member who has never chosen one. */
export const ASK_CONNEX_DEFAULT_WIDTH: AskConnexWidth = 'compact';

/**
 * The rendered width of each state, in rem.
 *
 * The panel and the app shell's grid column must agree exactly or the drawer either overlaps the
 * page or leaves a gap beside it, so both read this one table rather than each spelling a width of
 * their own.
 */
export const ASK_CONNEX_WIDTH_REM: Record<AskConnexWidth, number> = {
    compact: 24,
    comfortable: 32,
};

/** The CSS length one width state occupies, for the panel and the shell column alike. */
export function askConnexWidthLength(width: AskConnexWidth): string {
    return `${ASK_CONNEX_WIDTH_REM[width]}rem`;
}

/** Scoped browser-storage key for the member's chosen drawer width. */
export function askConnexWidthStorageKey(
    userId: number | null,
    workspaceId: number | null,
): string {
    return viewPreferenceStorageKey('ask-connex:width', userId, workspaceId);
}

/** Parses a persisted width at the browser-storage trust boundary. */
export function parseStoredAskConnexWidth(value: string | null): AskConnexWidth | null {
    return value === 'compact' || value === 'comfortable' ? value : null;
}

/**
 * How many structured rows or list items one answer block shows before it stops and offers the
 * full workspace instead.
 *
 * A bounded preview is the honest presentation at drawer width: a forty-row timeline squeezed into
 * a 24rem column is not a timeline, and scrolling it sideways is worse than not showing it. The cap
 * applies to the drawer only — the workspace renders every row.
 */
export const ASK_CONNEX_DRAWER_ROW_CAP = 5;

/** One bounded list plus the count it withheld. */
export type BoundedAnswerEntries<T> = {
    entries: T[];
    hidden: number;
};

/**
 * Bounds one answer list to the surface's row cap.
 *
 * A null cap is the full workspace, which withholds nothing. Bounding never applies when it would
 * hide a single entry: replacing one row with a "1 more" affordance costs the reader the row and
 * gives back nothing.
 */
export function boundedAnswerEntries<T>(
    entries: readonly T[],
    cap: number | null,
): BoundedAnswerEntries<T> {
    if (cap === null || entries.length <= cap + 1) {
        return { entries: [...entries], hidden: 0 };
    }
    return { entries: entries.slice(0, cap), hidden: entries.length - cap };
}

/** The recency bands the session rail groups by, in display order. */
export const ASK_CONNEX_SESSION_GROUPS = ['invitations', 'last24h', 'last7d', 'earlier'] as const;

/** One recency band in the session rail. */
export type AskConnexSessionGroupKey = (typeof ASK_CONNEX_SESSION_GROUPS)[number];

/** One rail section: a band and the sessions that fall in it, newest first. */
export type AskConnexSessionGroup = {
    key: AskConnexSessionGroupKey;
    sessions: AiChatSession[];
};

const DAY_MS = 24 * 60 * 60 * 1000;

/**
 * The instant a session was last active, falling back to when it was last changed.
 *
 * Parsed the way every other timestamp on this client is parsed. The wire format is MySQL's
 * offset-less `YYYY-MM-DD HH:MM:SS` in UTC, which `Date.parse` reads as *local* time — east of
 * Greenwich that dates a chat hours into the future and bands it one step too recent, disagreeing
 * with the relative-time label printed on the same row.
 */
export function askConnexSessionActivity(session: AiChatSession): number {
    const parsed = parseMysqlDateTime(session.lastMessageAt ?? session.updatedAt);
    return Number.isNaN(parsed) ? 0 : parsed;
}

/**
 * Orders and bands sessions for the rail.
 *
 * Chronology is the only organization the session list DTO can support honestly: it carries when a
 * chat was last active, whether it is shared, and whether the member was invited to it, and nothing
 * about running answers, pending approvals, or linked records. Bands the reader can verify from the
 * row itself are worth showing; a band derived from data the client does not have would be a label
 * that is right by accident.
 *
 * The bands are rolling windows measured back from now, and they are named as such: a chat active
 * twenty hours ago is not "today" in any calendar the reader keeps, and calling it that contradicts
 * the "20 hr. ago" printed beside it.
 *
 * @param sessions chats the member has joined
 * @param invitations chats the member has been invited to but not joined
 * @param now the render clock, so grouping is stable within one frame
 */
export function groupAskConnexSessions(
    sessions: readonly AiChatSession[],
    invitations: readonly AiChatSession[],
    now: number,
): AskConnexSessionGroup[] {
    const byRecency = [...sessions].sort(
        (left, right) => askConnexSessionActivity(right) - askConnexSessionActivity(left),
    );
    const last24h: AiChatSession[] = [];
    const last7d: AiChatSession[] = [];
    const earlier: AiChatSession[] = [];
    for (const session of byRecency) {
        const age = now - askConnexSessionActivity(session);
        if (age < DAY_MS) last24h.push(session);
        else if (age < 7 * DAY_MS) last7d.push(session);
        else earlier.push(session);
    }
    const invited = [...invitations].sort(
        (left, right) => askConnexSessionActivity(right) - askConnexSessionActivity(left),
    );
    const groups: AskConnexSessionGroup[] = [];
    if (invited.length > 0) groups.push({ key: 'invitations', sessions: invited });
    if (last24h.length > 0) groups.push({ key: 'last24h', sessions: last24h });
    if (last7d.length > 0) groups.push({ key: 'last7d', sessions: last7d });
    if (earlier.length > 0) groups.push({ key: 'earlier', sessions: earlier });
    return groups;
}

/** Filters sessions by a rail search query against their titles. */
export function filterAskConnexSessions(
    sessions: readonly AiChatSession[],
    query: string,
): AiChatSession[] {
    const normalized = query.trim().toLocaleLowerCase();
    if (normalized.length === 0) return [...sessions];
    return sessions.filter((session) => session.title.toLocaleLowerCase().includes(normalized));
}

/**
 * The live state of the chat currently on screen, for the header and its rail row.
 *
 * Only the active chat has one: its running answer and its pending proposals are state this client
 * is already following. Every other row in the rail would need the server to say so, and it does
 * not, so they carry no state chip rather than a guessed one.
 */
export type AskConnexActiveState = 'running' | 'awaitingApproval' | 'failed' | null;

/**
 * Derives the active chat's state from what the client actually knows.
 *
 * Ordered by what the member has to act on soonest: an answer still being written is the live fact,
 * a proposal waiting on a decision is the thing blocked on them, and a settled failure is the thing
 * they may want to retry.
 */
export function askConnexActiveState({
    phase,
    pendingApprovals,
}: {
    phase: string;
    pendingApprovals: number;
}): AskConnexActiveState {
    if (phase === 'accepted' || phase === 'running') return 'running';
    if (pendingApprovals > 0) return 'awaitingApproval';
    if (phase === 'failed' || phase === 'timed_out') return 'failed';
    return null;
}

/**
 * The recovery routes a settled answer can honestly offer.
 *
 * Retry re-asks the same question and needs the question to still be readable. Continuing needs a
 * partial answer to continue from — a failure that produced no words has nothing to build on.
 * Narrowing is offered only where a smaller request is genuinely the remedy.
 */
export type AskConnexRecovery = {
    retry: boolean;
    continueFromPartial: boolean;
    narrowScope: boolean;
    /** Whether narrowing is the route to lead with rather than one of several. */
    narrowScopeFirst: boolean;
};

/**
 * What kind of thing went wrong, and therefore which routes out of it exist.
 *
 * - `breadth` — the question was answerable but covered more than one pass can read. Only a
 *   smaller request helps; re-asking it unchanged meets the same guard.
 * - `synthesis` — retrieval succeeded and writing the answer up ran out of room. The sources were
 *   reachable, so narrowing *what it covers* is the wrong advice; a shorter question or another
 *   attempt is the route.
 * - `capacity` — an allowance or a provider ran out. Nothing about this request changes that, so
 *   the only real route is the same question later, and an immediate retry control would be a
 *   button that can only fail.
 * - `availability` — the feature is switched off here. Nothing the member does in this panel
 *   changes it.
 * - `authorization` — the member's authority to read what the turn produced was withdrawn while it
 *   ran. The server purges the durable partial for these, so this client must not keep offering
 *   routes that build on text it is no longer entitled to.
 * - `unsupportedInput` — a source that was sent cannot be read at all. The remedy is removing that
 *   source, not asking for less or asking again.
 * - `generic` — anything else, including every reason this build has never heard of.
 */
export type AskConnexFailureClass =
    | 'breadth'
    | 'synthesis'
    | 'capacity'
    | 'availability'
    | 'authorization'
    | 'unsupportedInput'
    | 'generic';

/** Which explanation a settled failure is stated with. */
export type AskConnexFailureMessage =
    | 'generic'
    | 'breadthSteps'
    | 'breadthResults'
    | 'skillBudget'
    | 'toolAuthority'
    | 'budget'
    | 'capacity'
    | 'workspaceDisabled'
    | 'accessRevoked'
    | 'restrictionsChanged'
    | 'imageUnsupported';

/** One terminal reason, classified: what kind of failure it is and how it is explained. */
export type AskConnexTerminalKind = {
    category: AskConnexFailureClass;
    message: AskConnexFailureMessage;
};

/**
 * Every terminal reason this client recognizes, classified.
 *
 * The server's stable vocabulary lives in `AiChatTurnTerminalCoordinator` and
 * `AiAssistantTerminalReasons`; a reason absent from this table is treated as generic rather than
 * assigned advice by default, because advice derived from a reason nobody classified is a guess
 * the reader cannot tell apart from a fact.
 */
const TERMINAL_KINDS: Readonly<Record<string, AskConnexTerminalKind>> = {
    step_cap_exceeded: { category: 'breadth', message: 'breadthSteps' },
    agent_backstop_exceeded: { category: 'breadth', message: 'breadthSteps' },
    tool_result_budget_exhausted: { category: 'breadth', message: 'breadthResults' },
    skill_budget_exceeded: { category: 'synthesis', message: 'skillBudget' },
    budget_exhausted: { category: 'capacity', message: 'budget' },
    quota_exhausted: { category: 'capacity', message: 'capacity' },
    org_invocation_quota_exhausted: { category: 'capacity', message: 'capacity' },
    invocation_capacity_exhausted: { category: 'capacity', message: 'capacity' },
    generation_capacity: { category: 'capacity', message: 'capacity' },
    workspace_disabled: { category: 'availability', message: 'workspaceDisabled' },
    access_revoked: { category: 'authorization', message: 'accessRevoked' },
    restrictions_changed: { category: 'authorization', message: 'restrictionsChanged' },
    image_input_unsupported: { category: 'unsupportedInput', message: 'imageUnsupported' },
    tool_outside_skill_authority: { category: 'generic', message: 'toolAuthority' },
    provider_error: { category: 'generic', message: 'generic' },
    malformed_output: { category: 'generic', message: 'generic' },
    schema_repair_failed: { category: 'generic', message: 'generic' },
    attachment_auto_write_blocked: { category: 'generic', message: 'generic' },
    no_progress: { category: 'generic', message: 'generic' },
    internal_error: { category: 'generic', message: 'generic' },
    generation_timeout: { category: 'generic', message: 'generic' },
    turn_deadline_exceeded: { category: 'generic', message: 'generic' },
    provider_idle_timeout: { category: 'generic', message: 'generic' },
    request_failed: { category: 'generic', message: 'generic' },
    reconciliation_failed: { category: 'generic', message: 'generic' },
};

/** Every terminal reason this client classifies, sorted, so a test can enumerate the vocabulary. */
export const ASK_CONNEX_TERMINAL_REASONS: readonly string[] = Object.keys(TERMINAL_KINDS).toSorted();

const GENERIC_KIND: AskConnexTerminalKind = { category: 'generic', message: 'generic' };

/** Classifies one terminal reason, falling back to the generic failure for anything unrecognized. */
export function askConnexTerminalKind(reason: string | null): AskConnexTerminalKind {
    if (reason === null) return GENERIC_KIND;
    return TERMINAL_KINDS[reason] ?? GENERIC_KIND;
}

/**
 * Whether a terminal reason withdrew the member's authority to read what the turn produced.
 *
 * The server purges the durable partial for these, so a client holding a locally buffered copy is
 * holding text the member is no longer entitled to — it drops it rather than continuing to show
 * and offer routes from it.
 */
export function isAskConnexAuthorizationWithdrawal(reason: string | null): boolean {
    return askConnexTerminalKind(reason).category === 'authorization';
}

/** Which routes each class of failure offers, before the surface's own preconditions apply. */
const RECOVERY_BY_CLASS: Readonly<Record<AskConnexFailureClass, AskConnexRecovery>> = {
    breadth: { retry: false, continueFromPartial: false, narrowScope: true, narrowScopeFirst: true },
    synthesis: { retry: true, continueFromPartial: true, narrowScope: false, narrowScopeFirst: false },
    capacity: { retry: false, continueFromPartial: false, narrowScope: false, narrowScopeFirst: false },
    availability: { retry: false, continueFromPartial: false, narrowScope: false, narrowScopeFirst: false },
    authorization: { retry: false, continueFromPartial: false, narrowScope: false, narrowScopeFirst: false },
    unsupportedInput: { retry: false, continueFromPartial: false, narrowScope: false, narrowScopeFirst: false },
    generic: { retry: true, continueFromPartial: true, narrowScope: false, narrowScopeFirst: false },
};

/**
 * Which recovery routes a settled answer offers.
 *
 * A breadth failure withholds continuing as well as retrying: the continue prefill re-asks the
 * question that just exceeded the guard, so it would meet the same limit that narrowing exists to
 * get under.
 *
 * @param phase the settled phase of the answer
 * @param reason its terminal reason, when the server gave one
 * @param canRetry whether the original question is still readable and the surface can send
 * @param hasPartial whether words were retained from the answer before it stopped
 */
export function askConnexRecovery(
    phase: string,
    reason: string | null,
    canRetry: boolean,
    hasPartial: boolean,
): AskConnexRecovery {
    if (phase !== 'failed' && phase !== 'timed_out') {
        return { retry: false, continueFromPartial: false, narrowScope: false, narrowScopeFirst: false };
    }
    const offered = RECOVERY_BY_CLASS[askConnexTerminalKind(reason).category];
    return {
        retry: offered.retry && canRetry,
        continueFromPartial: offered.continueFromPartial && canRetry && hasPartial,
        narrowScope: offered.narrowScope && canRetry,
        narrowScopeFirst: offered.narrowScopeFirst,
    };
}
