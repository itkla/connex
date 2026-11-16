import type { Contact, Deal, Note, NoteReference } from '@/app/lib/types';

export type NoteRecordLink = NoteReference & { type: 'person' | 'deal' | 'company'; href: string };

const RECORD_PATHS = { person: 'contacts', deal: 'deals', company: 'companies' } as const;

/** Lists each resolved record link once, keeping explicit contact and deal associations first. */
export function noteRecordLinks(
    note: Pick<Note, 'person' | 'deal' | 'references'>,
    person?: Contact | null,
    deal?: Deal | null,
): NoteRecordLink[] {
    const references: NoteReference[] = [
        ...(person && person.id === note.person ? [{ type: 'person' as const, id: person.id, label: person.name }] : []),
        ...(deal && deal.id === note.deal ? [{ type: 'deal' as const, id: deal.id, label: deal.name }] : []),
        ...(note.references ?? []),
    ];
    const links = new Map<string, NoteRecordLink>();
    for (const reference of references) {
        const { type, id, label } = reference;
        if (type !== 'person' && type !== 'deal' && type !== 'company') continue;
        const key = `${type}:${id}`;
        if (!links.has(key)) links.set(key, { type, id, label, href: `/records/${RECORD_PATHS[type]}/${id}` });
    }
    return [...links.values()];
}
