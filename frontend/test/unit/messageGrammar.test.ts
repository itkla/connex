import { createTranslator } from 'next-intl';
import { describe, expect, it } from 'vitest';
import contacts from '@/messages/en/contacts.json';
import companies from '@/messages/en/companies.json';
import deals from '@/messages/en/deals.json';
import pipelines from '@/messages/en/pipelines.json';
import me from '@/messages/en/me.json';
import workspace from '@/messages/en/workspace.json';
import jaMe from '@/messages/ja/me.json';
import jaWorkspace from '@/messages/ja/workspace.json';

const t = createTranslator({ locale: 'en', messages: { ...contacts, ...companies, ...deals, ...workspace } });

describe('ACT-32 and DEAL-59 record counts', () => {
    it.each([0, 1, 3])('formats note and deal counts for %i records', (count) => {
        const note = count === 1 ? 'note' : 'notes';
        const deal = count === 1 ? 'deal' : 'deals';
        expect(t('ContactsPage.notesCount', { count })).toBe(`${count} ${note}`);
        expect(t('ContactsPage.dealsCount', { count })).toBe(`${count} ${deal}`);
        expect(t('CompaniesDetail.notesSubtitle', { count })).toBe(`${count} ${note}`);
        expect(t('CompaniesDetail.dealsSubtitle', { count })).toBe(`${count} ${deal}`);
    });

    describe.each([
        { namespace: 'ContactsBrowser', singular: 'contact', plural: 'contacts' },
        { namespace: 'CompaniesBrowser', singular: 'company', plural: 'companies' },
        { namespace: 'DealsBrowser', singular: 'deal', plural: 'deals' },
    ] as const)('$namespace tag feedback', ({ namespace, singular, plural }) => {
        it.each([0, 1, 3])('formats completed operations for %i records', (count) => {
            const noun = count === 1 ? singular : plural;
            expect(t(`${namespace}.toastTagAdded`, { count })).toBe(`Tagged ${count} ${noun}`);
            expect(t(`${namespace}.toastTagRemoved`, { count })).toBe(`Removed tag from ${count} ${noun}`);
        });

        it.each([
            { succeeded: 0, total: 1 },
            { succeeded: 1, total: 2 },
            { succeeded: 2, total: 3 },
        ])('formats partial operations for $succeeded of $total records', ({ succeeded, total }) => {
            const noun = total === 1 ? singular : plural;
            expect(t(`${namespace}.toastTagAddedPartial`, { succeeded, total })).toBe(`Tagged ${succeeded} of ${total} ${noun}`);
            expect(t(`${namespace}.toastTagRemovedPartial`, { succeeded, total })).toBe(`Removed tag from ${succeeded} of ${total} ${noun}`);
        });
    });
});

describe('ACT-32 quick-edit and timeline labels', () => {
    it.each([
        contacts.ContactsQuickEditSheet.description,
        companies.CompaniesQuickEditSheet.description,
        deals.DealsQuickEditSheet.subtitle,
        pipelines.PipelinesQuickEditSheet.description,
    ])('describes saving fields: %s', (description) => {
        expect(description).toContain('Only changed fields will be saved.');
    });

    it.each([
        ['chipTask', 'Task', 'タスク'],
        ['chipActivity', 'Activity', 'アクティビティ'],
        ['chipNote', 'Note', 'メモ'],
        ['chipLifecycle', 'Lifecycle', 'ライフサイクル'],
        ['chipComment', 'Comment', 'コメント'],
        ['chipCampaign', 'Campaign', 'キャンペーン'],
    ] as const)('capitalizes %s and preserves Japanese', (key, english, japanese) => {
        expect(me.MeTimeline[key]).toBe(english);
        expect(jaMe.MeTimeline[key]).toBe(japanese);
    });
});

describe('ADM-62 member and role counts', () => {
    it.each([0, 1, 3])('formats %i members', (count) => {
        expect(t('WorkspaceMembers.count', { count })).toBe(`${count} ${count === 1 ? 'member' : 'members'}`);
    });

    it('removes the unused permission count in both locales', () => {
        expect(workspace.WorkspaceRoles).not.toHaveProperty('permissionCount');
        expect(jaWorkspace.WorkspaceRoles).not.toHaveProperty('permissionCount');
    });
});
