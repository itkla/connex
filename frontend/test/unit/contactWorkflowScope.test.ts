import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';
import { contactWorkflowScope } from '@/app/components/records/contacts/contactWorkflowScope';

const selection = {
    allMatchingActive: true,
    activeSavedViewId: null,
    showArchived: false,
    warmthNarrowed: false,
    recordIds: [7, 8],
    filter: { titles: ['Buyer'] },
};

describe('contact workflow selection boundaries', () => {
    it('keeps a tag-and-title select-all bounded to the actual matching contacts', () => {
        const tagged = { ...selection, tagIds: [12] };
        expect(contactWorkflowScope(tagged)).toEqual({ kind: 'explicit_selection', recordIds: [7, 8] });
        expect(tagged.filter).toEqual({ titles: ['Buyer'] });
    });

    it('keeps tag-filtered saved views bounded to the selected IDs', () => {
        expect(contactWorkflowScope({ ...selection, activeSavedViewId: 42, tagIds: [12, 34] })).toEqual({
            kind: 'explicit_selection', recordIds: [7, 8],
        });
    });

    it('never broadens an unavailable tag into all contacts with the same title', () => {
        expect(contactWorkflowScope({ ...selection, tagIds: [-1], recordIds: [] })).toEqual({
            kind: 'explicit_selection', recordIds: [],
        });
    });

    it('preserves the existing warmth and partial-selection boundaries', () => {
        expect(contactWorkflowScope({ ...selection, activeSavedViewId: 42, warmthNarrowed: true })).toEqual({ kind: 'explicit_selection', recordIds: [7, 8] });
        expect(contactWorkflowScope({ ...selection, allMatchingActive: false })).toEqual({ kind: 'explicit_selection', recordIds: [7, 8] });
    });

    it('still resolves supported filters and saved views when no tag restriction is active', () => {
        expect(contactWorkflowScope(selection)).toEqual({ kind: 'filter_match', filter: { titles: ['Buyer'] } });
        expect(contactWorkflowScope({ ...selection, activeSavedViewId: 42, tagIds: [] })).toEqual({ kind: 'saved_view', savedViewId: 42 });
        expect(contactWorkflowScope({ ...selection, showArchived: true })).toEqual({ kind: 'explicit_selection', recordIds: [7, 8] });
    });

    it('passes the tag IDs and selected matching IDs from the live contacts browser', () => {
        const browser = readFileSync('app/components/records/contacts/ContactsBrowser.tsx', 'utf8');
        expect(browser).toContain('const scope = contactWorkflowScope({');
        expect(browser).toContain('tagIds: filterParams.tagIds');
        expect(browser).toContain('recordIds: selectedContactIds');
    });
});
