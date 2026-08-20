import { WARMTH_NONE_FACET_KEY } from '@/app/components/records/warmthFilters';
import type { ActivationCounts } from '@/app/lib/activation';
import type { FacetCount, TemperatureBand, WarmthBandCounts } from '@/app/lib/types';

/**
 * The ways into a record type with nothing in it yet. Both doors belong to the same permission and
 * the same record type, so the contacts and companies browsers share them and supply their own
 * labels.
 */
export type FirstRunDoor = 'import' | 'create';

/** The guided entry a workspace with no contacts is offered, resolved from its own counts. */
export type FirstRunEntry = {
    doors: FirstRunDoor[];
    /**
     * Whether the entry may mention reading a business card. Card scanning lives inside the contact
     * composer, so it is offered as part of adding someone rather than as a door of its own, and it
     * is withheld entirely on an instance that cannot scan.
     */
    cardScanning: boolean;
};

const BANDS: readonly TemperatureBand[] = ['hot', 'warm', 'cool', 'cold'];

/**
 * The doors a member can use to bring the first records in. Both create records of the same type,
 * so they share one permission and one absence: a member who cannot create them is offered nothing
 * rather than an invitation they cannot accept.
 *
 * @param canCreateRecords - whether the member may create records of this type
 * @returns the offered doors, empty when the member cannot create them
 */
export function firstRunDoors(canCreateRecords: boolean): FirstRunDoor[] {
    return canCreateRecords ? ['import', 'create'] : [];
}

/**
 * Resolves the guided entry from the same counts the setup checklist is built from, so the two can
 * never disagree. Returns null once contacts exist, and for a member who cannot create them, which
 * leaves the surface showing its plain empty state instead of a guided one that leads nowhere.
 *
 * @param counts - exact workspace counts
 * @param scanningAvailable - whether this instance can read business cards
 * @returns the entry to offer, or null when there is nothing to guide
 */
export function resolveFirstRunEntry(
    counts: ActivationCounts,
    scanningAvailable: boolean,
): FirstRunEntry | null {
    if (counts.contacts > 0) return null;
    const doors = firstRunDoors(counts.canImportContacts);
    if (doors.length === 0) return null;
    return { doors, cardScanning: scanningAvailable };
}

/**
 * The warmth a workspace's own interactions have produced, read from the contacts warmth facet.
 * Contacts with no interaction history are counted by the backend under their own key, disjoint
 * from every band, and are dropped here: a relationship nobody has touched has no reading, and
 * counting it as cold would claim evidence that does not exist. Returns null when the facet was not
 * requested and when no contact carries a reading, so the caller never claims an arrival it cannot
 * show.
 *
 * @param bands - the contacts warmth facet, or undefined when it was not requested
 * @returns the band counts backed by recorded interactions, or null when none are
 */
export function warmthArrival(bands: FacetCount[] | undefined): WarmthBandCounts | null {
    if (!bands) return null;
    const counts: WarmthBandCounts = { hot: 0, warm: 0, cool: 0, cold: 0 };
    let read = 0;
    for (const facet of bands) {
        if (facet.key === WARMTH_NONE_FACET_KEY || facet.count <= 0) continue;
        const band = BANDS.find((candidate) => candidate === facet.key);
        if (!band) continue;
        counts[band] = facet.count;
        read += facet.count;
    }
    return read > 0 ? counts : null;
}
