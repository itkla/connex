import type { AiWatch, AiWatchSubjectKind, AiWatchType } from '@/app/lib/types';

/**
 * How a watch's trigger is stated back to its owner.
 *
 * A watch is only trustworthy if the sentence the owner reads is the condition the server
 * evaluates, so this derives the sentence from the typed threshold fields alone. There is
 * deliberately no free-text description and no server-authored summary to drift from.
 */
export type AskConnexWatchTrigger = {
    /** i18n key under `AskConnex.commandCenter.watchTrigger` for the condition sentence. */
    key: string;
    /** Interpolation values the sentence needs, already narrowed to the declared threshold. */
    values: Record<string, string | number>;
};

/** Translator shape the caller supplies, matching `next-intl`'s scoped translator. */
type Translator = (key: string, values?: Record<string, string | number>) => string;

const SUBJECT_ROUTES: Readonly<Record<AiWatchSubjectKind, string>> = {
    person: '/records/contacts/',
    company: '/records/companies/',
    deal: '/records/deals/',
};

/**
 * The watch types this client can state a trigger for.
 *
 * Keyed by the server's own durable type keys so a type the server stops evaluating, or one this
 * build has no copy for, is rendered as an inspectable unknown rather than as a confident sentence
 * about a condition the client is guessing at.
 */
const TRIGGER_KEYS: Readonly<Record<AiWatchType, string>> = {
    relationship_cooling: 'cooling',
    no_interaction: 'noInteraction',
    commitment_overdue: 'commitmentOverdue',
    deal_risk_threshold: 'dealRisk',
};

/**
 * Derives the trigger sentence for one watch from its typed threshold.
 *
 * A watch whose declared threshold is missing the value its own type reads falls back to the
 * unknown sentence rather than interpolating a blank: stating "Warmth reaches" with nothing after
 * it would be worse than admitting the trigger cannot be read.
 *
 * @param watch the durable watch
 * @returns the i18n key and values its condition sentence is rendered from
 */
export function askConnexWatchTrigger(watch: AiWatch): AskConnexWatchTrigger {
    const fragment = TRIGGER_KEYS[watch.watchType];
    if (fragment === undefined) return { key: 'unknown', values: {} };
    if (watch.watchType === 'relationship_cooling') {
        if (watch.thresholdBand === null) return { key: 'unknown', values: {} };
        return { key: fragment, values: { band: watch.thresholdBand } };
    }
    if (watch.watchType === 'no_interaction') {
        if (watch.thresholdDays === null) return { key: 'unknown', values: {} };
        return { key: fragment, values: { days: watch.thresholdDays } };
    }
    if (watch.watchType === 'deal_risk_threshold') {
        if (watch.thresholdLevel === null) return { key: 'unknown', values: {} };
        return { key: fragment, values: { level: watch.thresholdLevel } };
    }
    return { key: fragment, values: {} };
}

/**
 * Renders one watch's trigger sentence, with the warmth band and risk level in product vocabulary.
 *
 * @param watch the durable watch
 * @param t translator scoped to `AskConnex.commandCenter`
 * @returns the localized condition sentence
 */
export function askConnexWatchTriggerText(watch: AiWatch, t: Translator): string {
    const trigger = askConnexWatchTrigger(watch);
    const values: Record<string, string | number> = { ...trigger.values };
    if (typeof values.band === 'string') values.band = t(`band.${values.band}`);
    if (typeof values.level === 'string') values.level = t(`level.${values.level}`);
    return t(`watchTrigger.${trigger.key}`, values);
}

/**
 * The record page one watch's subject lives on.
 *
 * The deep link goes to the record rather than to a pre-filled assistant question: the record is
 * where the source-owned evidence lives, and its existing Ask Connex entry point is what asks for an
 * explanation of the same evidence.
 *
 * @param watch the durable watch
 * @returns the in-app path of the watched record
 */
export function askConnexWatchHref(watch: AiWatch): string {
    return `${SUBJECT_ROUTES[watch.subjectKind]}${watch.subjectId}`;
}

/** The record kinds each watch type may be created against, mirroring the server's declaration. */
export const ASK_CONNEX_WATCH_SUBJECTS: Readonly<
    Record<AiWatchType, readonly AiWatchSubjectKind[]>
> = {
    relationship_cooling: ['person', 'company'],
    no_interaction: ['person', 'company'],
    commitment_overdue: ['person', 'company', 'deal'],
    deal_risk_threshold: ['deal'],
};

/**
 * Every watch type, in the order the picker offers them.
 *
 * Declared as a literal rather than derived from the subject map's keys: object key order is a
 * runtime detail that carries no type, so reading it back would need a cast and would silently
 * reorder the picker if the map were ever rewritten.
 */
export const ASK_CONNEX_WATCH_TYPES: readonly AiWatchType[] = [
    'relationship_cooling',
    'no_interaction',
    'commitment_overdue',
    'deal_risk_threshold',
];

/**
 * How long a watch waits before the same condition may alert again, and when it stops evaluating.
 *
 * These are part of the contract the member applies, not decoration: a watch that says it triggers
 * on a condition but silently waits a week between alerts, or quietly stops in a month, has a
 * displayed trigger narrower than its real behaviour. Both are stated wherever the trigger is.
 *
 * @param watch the durable watch
 * @param t translator scoped to `AskConnex.commandCenter`
 * @param formatExpiry renders an ISO-8601 local date in the reader's own locale
 * @returns the localized cooldown and expiry sentences
 */
export function askConnexWatchLimitsText(
    watch: Pick<AiWatch, 'cooldownDays' | 'expiresOn'>,
    t: Translator,
    formatExpiry: (isoDate: string) => string,
): { cooldown: string; expiry: string } {
    return {
        cooldown: t('cooldownEvery', { days: watch.cooldownDays }),
        expiry: watch.expiresOn === null
            ? t('expiresNever')
            : t('expiresOnDate', { date: formatExpiry(watch.expiresOn) }),
    };
}

/**
 * Whether one watch type can be created against one record kind.
 *
 * @param watchType the declared watch type
 * @param subjectKind the record kind in view
 * @returns whether the pair is a combination the server accepts
 */
export function askConnexWatchSupports(
    watchType: AiWatchType,
    subjectKind: AiWatchSubjectKind,
): boolean {
    return ASK_CONNEX_WATCH_SUBJECTS[watchType]?.includes(subjectKind) ?? false;
}
