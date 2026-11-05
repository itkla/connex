/** @vitest-environment jsdom */

import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { NextIntlClientProvider } from 'next-intl';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { RecordListState } from '@/app/components/records/RecordListState';
import { ApiError } from '@/app/lib/api';
import en from '@/messages/en/records.json';
import ja from '@/messages/ja/records.json';

afterEach(cleanup);

describe.each([{ locale: 'en', messages: en }, { locale: 'ja', messages: ja }])('record list recovery in $locale', ({ locale, messages }) => {
    const t = messages.RecordListState;

    it('replaces empty onboarding with a localized error, support reference and Retry', () => {
        const retry = vi.fn();
        render(
            <NextIntlClientProvider locale={locale} messages={messages}>
                <RecordListState error={new ApiError('private diagnostic', 503, undefined, undefined, 'test-reference')} loading={false} hasRecords={false} onRetry={retry}>
                    <p>No contacts yet</p>
                </RecordListState>
            </NextIntlClientProvider>,
        );

        expect(screen.queryByText('No contacts yet')).toBeNull();
        expect(screen.getByRole('alert').textContent).toContain(t.title);
        expect(screen.getByRole('alert').textContent).toContain(t.reference.replace('{id}', 'test-reference'));
        expect(screen.queryByText('private diagnostic')).toBeNull();
        fireEvent.click(screen.getByRole('button', { name: t.retry }));
        expect(retry).toHaveBeenCalledOnce();
    });

    it('keeps loaded rows and explains they are stale while a retry is busy', () => {
        render(
            <NextIntlClientProvider locale={locale} messages={messages}>
                <RecordListState error={new Error('offline')} loading hasRecords onRetry={vi.fn()}>
                    <p>Previously loaded contact</p>
                </RecordListState>
            </NextIntlClientProvider>,
        );

        expect(screen.getByText('Previously loaded contact')).toBeDefined();
        expect(screen.getByRole('alert').textContent).toContain(t.refreshBody);
        expect(screen.getByRole('button', { name: t.retrying }).hasAttribute('disabled')).toBe(true);
    });

    it('gives access guidance for permission refusals', () => {
        render(
            <NextIntlClientProvider locale={locale} messages={messages}>
                <RecordListState error={new ApiError('internal permission', 403)} loading={false} hasRecords={false} onRetry={vi.fn()}>
                    <p>No contacts yet</p>
                </RecordListState>
            </NextIntlClientProvider>,
        );

        expect(screen.getByRole('alert').textContent).toContain(t.deniedTitle);
        expect(screen.getByRole('alert').textContent).toContain(t.deniedBody);
        expect(screen.queryByText('No contacts yet')).toBeNull();
    });

    it('allows the genuinely empty state after success', () => {
        render(
            <NextIntlClientProvider locale={locale} messages={messages}>
                <RecordListState error={null} loading={false} hasRecords={false} onRetry={vi.fn()}>
                    <p>No contacts yet</p>
                </RecordListState>
            </NextIntlClientProvider>,
        );

        expect(screen.queryByRole('alert')).toBeNull();
        expect(screen.getByText('No contacts yet')).toBeDefined();
    });
});
