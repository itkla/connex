/** @vitest-environment jsdom */

import { cleanup, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import Link from 'next/link';
import NoteRecordLinks from '@/app/components/activity/notes/NoteRecordLinks';
import { noteRecordLinks } from '@/app/lib/noteRecordLinks';

afterEach(cleanup);

describe('shared note record metadata', () => {
    it('shows contact, deal and company labels on a card without nested links', () => {
        const links = noteRecordLinks({ references: [
            { type: 'person', id: 42, label: 'Contact' },
            { type: 'deal', id: 73, label: 'Deal' },
            { type: 'company', id: 9, label: 'Company' },
            { type: 'company', id: 9, label: 'Company' },
            { type: 'user', id: 1, label: 'Author' },
        ] });
        const view = render(<Link href="/activity/notes/1"><NoteRecordLinks links={links} interactive={false} /></Link>);
        expect(view.container.querySelectorAll('a')).toHaveLength(1);
        for (const name of ['Contact', 'Deal', 'Company']) expect(screen.getAllByText(name)).toHaveLength(1);
        expect(screen.queryByText('Author')).toBeNull();
        view.rerender(<NoteRecordLinks links={links} />);
        expect(screen.getByRole('link', { name: 'Contact' }).getAttribute('href')).toBe('/records/contacts/42');
        expect(screen.getByRole('link', { name: 'Deal' }).getAttribute('href')).toBe('/records/deals/73');
        expect(screen.getByRole('link', { name: 'Company' }).getAttribute('href')).toBe('/records/companies/9');
    });
});
