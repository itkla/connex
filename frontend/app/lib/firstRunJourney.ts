import type { ActivationCounts } from '@/app/lib/activation';
import type { WarmthBandCounts, WarmthSummary } from '@/app/lib/types';

/**
 * Where a brand-new workspace stands on the way to its first warmth reading. The legs are ordered:
 * people come in, an interaction records what happened between you, and warmth follows from that
 * evidence. `warmth` is the arrival, not another chore.
 */
export type FirstRunLeg = 'contacts' | 'evidence' | 'warmth';

/** The ways a workspace can bring its first people in, in the order they are offered. */
export type FirstRunDoor = 'importCsv' | 'newContact';

/** The journey a workspace is currently on, resolved from its own counts. */
export type FirstRunJourney = {
    leg: FirstRunLeg;
    /** The on-ramps this member can use right now. Empty on every leg but `contacts`. */
    doors: FirstRunDoor[];
    /**
     * Whether the journey may mention reading a business card. Card scanning lives inside the
     * contact composer, so it is offered as part of adding someone rather than as a door of its
     * own, and it is withheld entirely on an instance that cannot scan.
     */
    cardScanning: boolean;
};

/**
 * The warmth the workspace's own interactions have produced, or null when nothing recorded backs a
 * reading. Band counts alone are not evidence: a contact nobody has interacted with still falls in
 * a band, so the counts are only returned once at least one relationship carries a recorded touch.
 *
 * @param summary - the workspace-wide warmth summary
 * @param hasRelationshipEvidence - whether any relationship carries a recorded interaction
 * @returns the contact band counts, or null when no reading is provable
 */
export function warmthReadings(
    summary: WarmthSummary,
    hasRelationshipEvidence: boolean,
): WarmthBandCounts | null {
    if (!hasRelationshipEvidence) return null;
    const { hot, warm, cool, cold } = summary.contacts;
    return hot + warm + cool + cold > 0 ? summary.contacts : null;
}

/**
 * The on-ramps a member can use to bring the first people in. Both doors create contacts, so they
 * share one permission and one absence: a member who cannot create contacts is offered nothing
 * rather than an invitation they cannot accept.
 *
 * @param canCreateContacts - whether the member may create contacts
 * @returns the offered doors, empty when the member cannot create contacts
 */
export function firstRunDoors(canCreateContacts: boolean): FirstRunDoor[] {
    return canCreateContacts ? ['importCsv', 'newContact'] : [];
}

/**
 * Resolves the first-run journey from the same counts the setup checklist is built from, so the two
 * can never disagree. Returns null whenever this member cannot move the journey along, which leaves
 * the surface showing its plain empty state instead of a guided one that leads nowhere.
 *
 * @param counts - exact workspace counts
 * @param scanningAvailable - whether this instance can read business cards
 * @returns the current leg with its doors, or null when there is nothing to guide
 */
export function resolveFirstRunJourney(
    counts: ActivationCounts,
    scanningAvailable: boolean,
): FirstRunJourney | null {
    if (counts.contacts === 0) {
        const doors = firstRunDoors(counts.canImportContacts);
        if (doors.length === 0) return null;
        return { leg: 'contacts', doors, cardScanning: scanningAvailable };
    }
    if (!counts.hasInteractions) {
        if (!counts.canCreateActivities || !counts.hasRelationshipTargets) return null;
        return { leg: 'evidence', doors: [], cardScanning: scanningAvailable };
    }
    return { leg: 'warmth', doors: [], cardScanning: scanningAvailable };
}
