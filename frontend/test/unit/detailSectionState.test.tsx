/** @vitest-environment jsdom */

import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { NextIntlClientProvider } from 'next-intl';
import { afterEach, describe, expect, it, vi } from 'vitest';

import DetailSectionState from '@/app/components/records/DetailSectionState';
import type { DetailSectionFailure } from '@/app/lib/detailSectionResult';
import en from '@/messages/en/records.json';
import ja from '@/messages/ja/records.json';

const refresh = vi.hoisted(() => vi.fn());
vi.mock('next/navigation', () => ({ useRouter: () => ({ refresh }) }));

afterEach(() => { cleanup(); vi.clearAllMocks(); });

describe.each([{ locale: 'en', messages: en }, { locale: 'ja', messages: ja }])('detail recovery in $locale', ({ locale, messages }) => {
    it('replaces failed content with localized recovery, then reveals a genuine empty result', () => {
        const view = (failure: DetailSectionFailure | null) => (
            <NextIntlClientProvider locale={locale} messages={messages}>
                <DetailSectionState title="History" failure={failure}>
                    <p>Successful empty history</p>
                </DetailSectionState>
            </NextIntlClientProvider>
        );
        const { rerender } = render(view({ kind: 'failed', status: 503, reference: 'support-ref' }));
        expect(screen.queryByText('Successful empty history')).toBeNull();
        expect(screen.getByRole('alert').textContent).toContain(messages.RecordListState.sectionTitle.replace('{section}', 'History'));
        expect(screen.getByRole('alert').textContent).toContain('support-ref');
        fireEvent.click(screen.getByRole('button', { name: messages.RecordListState.retry }));
        expect(refresh).toHaveBeenCalledOnce();
        rerender(view(null));
        expect(screen.queryByRole('alert')).toBeNull();
        expect(screen.getByText('Successful empty history')).toBeDefined();
    });

    it('hides previously rendered content after permission denial', () => {
        const view = (failure: DetailSectionFailure | null) => (
            <NextIntlClientProvider locale={locale} messages={messages}>
                <DetailSectionState title="History" failure={failure}><p>Private history</p></DetailSectionState>
            </NextIntlClientProvider>
        );
        const { rerender } = render(view(null));
        expect(screen.getByText('Private history')).toBeDefined();
        rerender(view({ kind: 'failed', status: 403 }));
        expect(screen.queryByText('Private history')).toBeNull();
        expect(screen.getByRole('alert').textContent).toContain(messages.RecordListState.sectionDeniedTitle.replace('{section}', 'History'));
        expect(screen.getByRole('alert').textContent).toContain(messages.RecordListState.deniedBody);
    });
});
