import { radarPathBridges } from '@/app/components/radar/radarHorizon';
import { radarRecordLabel } from '@/app/components/radar/radarLabels';
import type {
    RadarEvidenceReference,
    RadarSignal,
    RadarSubject,
    RadarSubjectType,
} from '@/app/lib/types';

/** A record Radar cites, resolved to somewhere the user can actually go. */
export type RadarNamedLink = {
    href: string;
    label: string;
};

const RECORD_PATHS: Record<RadarSubjectType, string> = {
    person: '/records/contacts',
    company: '/records/companies',
    deal: '/records/deals',
};

function isSubjectType(value: string): value is RadarSubjectType {
    return value === 'person' || value === 'company' || value === 'deal';
}

function referenceKey(type: string, id: number): string {
    return `${type}:${id}`;
}

/**
 * The record route for one Radar subject type, mirroring the hrefs the backend's own context
 * endpoint hands back so a link and an "open the current record" round trip land in one place.
 */
export function radarRecordHref(type: string, id: number): string | null {
    if (!isSubjectType(type) || !Number.isInteger(id) || id <= 0) return null;
    return `${RECORD_PATHS[type]}/${id}`;
}

/**
 * The record route for a signal's subject. Radar only ships signals whose subject it re-read.
 *
 * Named for the record it opens, not the subject it describes, because `radarLinks.ts` owns
 * `radarSubjectHref` — the href back to *Radar* filtered to one subject. The two run in opposite
 * directions and must not share a name.
 */
export function radarSubjectRecordHref(subject: RadarSubject): string | null {
    return radarRecordHref(subject.type, subject.id);
}

/**
 * Every record name one signal can prove, keyed by reference.
 *
 * Radar's evidence references carry an id and a type; the names come from the parts of the payload
 * that are already named — the subject the backend re-read this request, and the intro-path
 * connectors the detector recorded by name. A reference this map cannot name is left unnamed
 * rather than rendered as a bare id.
 */
export function radarSignalNames(signal: RadarSignal): ReadonlyMap<string, string> {
    const names = new Map<string, string>();
    const subjectLabel = radarRecordLabel(signal.subject.label);
    if (subjectLabel !== null) {
        names.set(referenceKey(signal.subject.type, signal.subject.id), subjectLabel);
    }
    for (const bridge of radarPathBridges(signal)) {
        names.set(referenceKey('person', bridge.bridgePersonId), bridge.bridgeName);
    }
    return names;
}

/**
 * Resolves one cited reference into a named link, or null when it cannot be both named and
 * reached.
 *
 * The label is taken from the reference itself when the API supplies one — the additive
 * `label` field is the backend half of this contract — and otherwise from the names the signal
 * already proves. Returning null is the deliberate outcome for anything else: an unnamed source
 * record is provenance, not something to show a user, and "contact #42" is banned copy.
 */
export function radarReferenceLink(
    reference: RadarEvidenceReference,
    names: ReadonlyMap<string, string>,
): RadarNamedLink | null {
    const href = radarRecordHref(reference.type, reference.id);
    if (href === null) return null;
    const supplied = radarRecordLabel(reference.label);
    const label = supplied ?? names.get(referenceKey(reference.type, reference.id)) ?? null;
    return label === null ? null : { href, label };
}

/** Every named link one evidence entry cites, de-duplicated, in the order the detector wrote them. */
export function radarReferenceLinks(
    references: readonly RadarEvidenceReference[],
    names: ReadonlyMap<string, string>,
): RadarNamedLink[] {
    const links: RadarNamedLink[] = [];
    const seen = new Set<string>();
    for (const reference of references) {
        const link = radarReferenceLink(reference, names);
        if (!link || seen.has(link.href)) continue;
        seen.add(link.href);
        links.push(link);
    }
    return links;
}
