import type { ActiveRecordRef } from '@/app/lib/actions/types';
import type {
    Company,
    Contact,
    Deal,
    DealRisk,
    DealRiskFactor,
    IntroSuggestion,
    RelationshipTemperature,
} from '@/app/lib/types';

/** The setup steps a workspace can be measured against, in the order they are shown. */
export type ActivationStepId =
    | 'contacts'
    | 'companies'
    | 'interactions'
    | 'pipeline'
    | 'team'
    | 'connections';

/**
 * One checklist step resolved against the workspace's real data. `done` is never a stored flag: it
 * is recomputed from counts on every render, so a step can go back to undone if the data does.
 */
export type ActivationStep = {
    id: ActivationStepId;
    done: boolean;
    /** Whether the workspace is still considered mid-setup while this step is undone. */
    required: boolean;
    /** Action-registry id run for the step's call to action, or null when it navigates instead. */
    actionId: string | null;
    /** Route the step's call to action navigates to, or null when it runs an action instead. */
    href: string | null;
    /** Exact count backing a completed step. Null whenever no exact count is available. */
    count: number | null;
    /** Whether the launched activity must link a contact or deal to create relationship evidence. */
    requireRelationshipTarget: boolean;
};

/** The counts the checklist is derived from. Every field is an exact server-side count. */
export type ActivationCounts = {
    contacts: number;
    companies: number;
    /** Whether any interaction has been logged. Deliberately a flag: no bounded page can count them. */
    hasInteractions: boolean;
    /** Whether an activity can be linked to at least one current contact or deal. */
    hasRelationshipTargets: boolean;
    pipelines: number;
    stages: number;
    members: number;
    connectedAccounts: number;
    connectedCaptureReady: number;
    connectedCaptureAvailable: boolean;
    /** Whether the instance offers mailbox connections at all; the step is hidden when it does not. */
    connectedAccountsAvailable: boolean;
    canImportContacts: boolean;
    canImportCompanies: boolean;
    canCreateActivities: boolean;
    canManagePipelines: boolean;
    /** Whether the current member can invite teammates; the step is hidden when they cannot. */
    canManageMembers: boolean;
    canCreateTasks: boolean;
};

/** A single piece of recorded proof behind an insight. Each variant maps to one real stored field. */
export type ActivationEvidence =
    | { kind: 'riskFactor'; factor: DealRiskFactor }
    | { kind: 'lastTouch'; at: string }
    | { kind: 'touchCount'; count: number }
    | { kind: 'goesCold'; at: string }
    | { kind: 'mutualConnections'; count: number }
    | { kind: 'sharedCompany'; company: string };

/** The relationship signals the activation surface can present, strongest claim first. */
export type ActivationInsightKind = 'dealRisk' | 'coolingContact' | 'introPath';

/**
 * The first relationship signal a workspace can support, together with the evidence that justifies
 * it. `evidence` is guaranteed non-empty by {@link selectFirstInsight}: a signal without recorded
 * proof is not an insight, it is a guess, and is never returned.
 */
export type ActivationInsight = {
    kind: ActivationInsightKind;
    title: string;
    subtitle: string | null;
    href: string;
    /** The record a follow-up can be scoped to, when the signal is about one record. */
    record: ActiveRecordRef | null;
    risk: DealRisk | null;
    temperature: RelationshipTemperature | null;
    evidence: ActivationEvidence[];
};

/** The signal candidates already loaded for the dashboard, in the server's own ranking order. */
export type ActivationCandidates = {
    dealRisks: Array<{ deal: Deal; company: Company | null; risk: DealRisk }>;
    coolingContacts: Array<{ contact: Contact; temperature: RelationshipTemperature }>;
    introSuggestions: IntroSuggestion[];
};

/** A missing precondition that stops the workspace from producing an evidence-backed signal. */
export type ActivationGap = 'contacts' | 'interactions' | 'noSignal' | 'unavailable';

function riskEvidence(risk: DealRisk): ActivationEvidence[] {
    return risk.factors.map((factor) => ({ kind: 'riskFactor', factor }) as const);
}

function temperatureEvidence(temperature: RelationshipTemperature): ActivationEvidence[] {
    const evidence: ActivationEvidence[] = [];
    if (temperature.lastTouchAt) {
        evidence.push({ kind: 'lastTouch', at: temperature.lastTouchAt });
    }
    if (temperature.touchCount > 0) {
        evidence.push({ kind: 'touchCount', count: temperature.touchCount });
    }
    if (temperature.goesColdAt) {
        evidence.push({ kind: 'goesCold', at: temperature.goesColdAt });
    }
    return evidence;
}

function introEvidence(suggestion: IntroSuggestion): ActivationEvidence[] {
    const evidence: ActivationEvidence[] = [];
    if (suggestion.mutualConnections > 0) {
        evidence.push({ kind: 'mutualConnections', count: suggestion.mutualConnections });
    }
    if (suggestion.sharedCompany) {
        evidence.push({ kind: 'sharedCompany', company: suggestion.sharedCompany });
    }
    return evidence;
}

/**
 * Picks the strongest relationship signal the workspace can currently justify, or null when none of
 * the candidates carries recorded evidence. Deal risk outranks a cooling relationship, which
 * outranks an introduction path, because that is the order in which the claim is actionable.
 *
 * @param candidates - the signal candidates already loaded for the dashboard
 * @returns the first candidate with evidence, or null when nothing is provable yet
 */
export function selectFirstInsight(candidates: ActivationCandidates): ActivationInsight | null {
    for (const { deal, company, risk } of candidates.dealRisks) {
        if (risk.level === 'none') continue;
        const evidence = riskEvidence(risk);
        if (evidence.length === 0) continue;
        return {
            kind: 'dealRisk',
            title: deal.name,
            subtitle: company?.name ?? null,
            href: `/records/deals/${deal.id}`,
            record: { type: 'deal', id: deal.id, label: deal.name },
            risk,
            temperature: null,
            evidence,
        };
    }

    for (const { contact, temperature } of candidates.coolingContacts) {
        const evidence = temperatureEvidence(temperature);
        if (evidence.length === 0) continue;
        return {
            kind: 'coolingContact',
            title: contact.name,
            subtitle: contact.company?.name ?? (contact.title || null),
            href: `/records/contacts/${contact.id}`,
            record: { type: 'person', id: contact.id, label: contact.name },
            risk: null,
            temperature,
            evidence,
        };
    }

    for (const suggestion of candidates.introSuggestions) {
        const evidence = introEvidence(suggestion);
        if (evidence.length === 0) continue;
        return {
            kind: 'introPath',
            title: `${suggestion.personAName} · ${suggestion.personBName}`,
            subtitle: suggestion.personACompany ?? suggestion.personBCompany ?? null,
            href: '/overview/introductions',
            record: null,
            risk: null,
            temperature: null,
            evidence,
        };
    }

    return null;
}

/**
 * Resolves every setup step from the workspace's counts. The mailbox step is omitted entirely when
 * the instance has no connected-account provider configured, so the checklist never asks for
 * something the deployment cannot do.
 *
 * @param counts - exact workspace counts
 * @returns the ordered checklist steps
 */
export function buildActivationSteps(counts: ActivationCounts): ActivationStep[] {
    const steps: ActivationStep[] = [];

    if (counts.contacts > 0 || counts.canImportContacts) {
        steps.push({
            id: 'contacts',
            done: counts.contacts > 0,
            required: true,
            actionId: 'utility.import-contacts',
            href: null,
            count: counts.contacts,
            requireRelationshipTarget: false,
        });
    }

    if (counts.companies > 0 || counts.canImportCompanies) {
        steps.push({
            id: 'companies',
            done: counts.companies > 0,
            required: false,
            actionId: 'utility.import-companies',
            href: null,
            count: counts.companies,
            requireRelationshipTarget: false,
        });
    }

    if (counts.hasInteractions || (counts.canCreateActivities && counts.hasRelationshipTargets)) {
        steps.push({
            id: 'interactions',
            done: counts.hasInteractions,
            required: true,
            actionId: 'create.activity',
            href: null,
            count: null,
            requireRelationshipTarget: true,
        });
    }

    if ((counts.pipelines > 0 && counts.stages > 0) || counts.canManagePipelines) {
        steps.push({
            id: 'pipeline',
            done: counts.pipelines > 0 && counts.stages > 0,
            required: true,
            actionId: null,
            href: '/records/pipelines',
            count: counts.pipelines > 0 && counts.stages > 0 ? counts.pipelines : null,
            requireRelationshipTarget: false,
        });
    }

    if (counts.members > 1 || counts.canManageMembers) {
        steps.push({
            id: 'team',
            done: counts.members > 1,
            required: false,
            actionId: null,
            href: '/settings/members',
            count: counts.members,
            requireRelationshipTarget: false,
        });
    }

    if (counts.connectedAccountsAvailable) {
        steps.push({
            id: 'connections',
            done: counts.connectedCaptureAvailable
                ? counts.connectedCaptureReady > 0
                : counts.connectedAccounts > 0,
            required: false,
            actionId: null,
            href: '/account/connections',
            count: null,
            requireRelationshipTarget: false,
        });
    }

    return steps;
}

/**
 * Whether the workspace has cleared every required setup step. The activation surface retires once
 * this is true, handing the dashboard's own signal widgets back the space.
 *
 * @param steps - the resolved checklist steps
 * @returns true when no required step is outstanding
 */
export function isActivated(steps: ActivationStep[]): boolean {
    return steps.every((step) => step.done || !step.required);
}

/**
 * Names the preconditions that stop the workspace from producing an evidence-backed signal. Returns
 * an empty list once a signal exists, so the caller never renders a contradiction.
 *
 * @param counts - exact workspace counts
 * @param hasInsight - whether {@link selectFirstInsight} found a provable signal
 * @param signalsAvailable - whether every signal source needed to decide this state loaded
 * @returns the outstanding gaps, most fundamental first
 */
export function activationGaps(
    counts: ActivationCounts,
    hasInsight: boolean,
    signalsAvailable = true,
): ActivationGap[] {
    if (!signalsAvailable) return ['unavailable'];
    if (hasInsight) return [];
    const gaps: ActivationGap[] = [];
    if (counts.contacts === 0) gaps.push('contacts');
    if (!counts.hasInteractions) gaps.push('interactions');
    if (gaps.length === 0) gaps.push('noSignal');
    return gaps;
}
