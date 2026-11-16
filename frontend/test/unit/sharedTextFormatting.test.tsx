import { createTranslator, NextIntlClientProvider } from 'next-intl';
import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it, vi } from 'vitest';
import MeHero from '@/app/components/me/MeHero';
import NeedsYou from '@/app/components/me/NeedsYou';
import { NewCompanyForm } from '@/app/components/records/companies/NewCompanyDialog';
import enMe from '@/messages/en/me.json';
import jaMe from '@/messages/ja/me.json';
import enRecords from '@/messages/en/records.json';
import jaRecords from '@/messages/ja/records.json';
import enActivity from '@/messages/en/activity.json';
import jaActivity from '@/messages/ja/activity.json';
import enCompanies from '@/messages/en/companies.json';
import jaCompanies from '@/messages/ja/companies.json';
import type { User } from '@/app/lib/types';

vi.mock('@/app/hooks/useWorkspace', () => ({ useWorkspace: () => ({ activeWorkspaceId: 1 }) }));
vi.mock('@/components/ui/dialog-status-cover', () => ({ DialogStatusCover: () => null }));

describe.each(['en', 'ja'] as const)('shared text formatting in %s', (locale) => {
    it('groups every record-footer number and both Notes counts', () => {
        const records = createTranslator({ locale, messages: locale === 'en' ? enRecords : jaRecords });
        const activity = createTranslator({ locale, messages: locale === 'en' ? enActivity : jaActivity });
        const footer = records('RecordsRenderView.showing', { from: 1234, to: 1258, total: 23456 });
        expect(footer).toBe(locale === 'en' ? 'Showing 1,234–1,258 of 23,456' : '23,456 件中 1,234–1,258 件');
        expect(activity('ActivityNotes.showingCount', { shown: 1234, total: 23456 }))
            .toBe(locale === 'en' ? 'Showing 1,234 of 23,456' : '23,456 件中 1,234 件を表示');
    });

    it('uses the company brand in the inline preview and preserves the authored name', () => {
        const html = renderToStaticMarkup(
            <NextIntlClientProvider locale={locale} timeZone="UTC" messages={locale === 'en' ? enCompanies : jaCompanies}>
                <NewCompanyForm active payload={{ name: '株式会社サンプル' }} setPayload={() => {}}
                    logoFile={null} setLogoFile={() => {}} isCreating={false} createNewCompany={() => {}} onCancel={() => {}} />
            </NextIntlClientProvider>,
        );
        expect(html).toContain('>サ</div>');
        expect(html).toContain('value="株式会社サンプル"');
    });

    it.each([
        ['🚀 宇宙 太郎', '宇'],
        ['𠮷野 家', '𠮷'],
        ['山田 太郎', '山'],
        ['Jane Q Doe', 'JD'],
    ])('uses the same initials for the My Work owner, orbit and cooling row: %s', (name, expected) => {
        const user: User = {
            id: 1, username: 'formatting', displayName: name, email: '',
            createdAt: '', updatedAt: '', timezone: 'UTC', locale,
        };
        const html = renderToStaticMarkup(
            <NextIntlClientProvider locale={locale} timeZone="UTC" messages={locale === 'en' ? enMe : jaMe}>
                <MeHero user={user} greeting="" nodes={[{
                    id: 1, name, band: 'warm', score: 60, trend: 'steady',
                }]} distribution={{ hot: 0, warm: 1, cool: 0, cold: 0 }} coolingCount={0} />
                <NeedsYou cooling={[{
                    id: 1, name, temp: { id: 1, score: 60, band: 'warm', trend: 'cooling', touchCount: 1, daysUntilCold: 7 },
                }]} risks={[]} />
            </NextIntlClientProvider>,
        );
        expect(html.match(new RegExp(`>${expected}<`, 'gu'))).toHaveLength(3);
        expect(html.isWellFormed()).toBe(true);
        expect(html).not.toContain('\uFFFD');
    });
});
