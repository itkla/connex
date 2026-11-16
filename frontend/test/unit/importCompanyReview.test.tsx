/** @vitest-environment jsdom */
import { useState } from 'react';
import { cleanup, fireEvent, render, screen, within } from '@testing-library/react';
import { NextIntlClientProvider, createTranslator } from 'next-intl';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { CompanyChoices } from '@/app/components/import/ImportDialog';
import { needsImportDecision } from '@/app/lib/importReview';
import type { DuplicateCandidate, ImportRowAnalysis } from '@/app/lib/types';
import en from '@/messages/en/importExport.json';
import ja from '@/messages/ja/importExport.json';

afterEach(cleanup);

const candidate: DuplicateCandidate = {
    recordId: 10, recordType: 'company', name: '㈱テスト', ownedByActiveWorkspace: true,
    strength: 'WEAK', matches: [{ kind: 'NAME', normalizedValue: '㈱テスト', strength: 'WEAK' }],
};

describe.each(['en', 'ja'] as const)('company import review in %s', (locale) => {
    const messages = locale === 'en' ? en : ja;
    const t = createTranslator({ locale, messages, namespace: 'importExport' });
    function mount(dependency: NonNullable<ImportRowAnalysis['companyDependency']>, selectedId?: number) {
        const row: ImportRowAnalysis = { rowIndex: 0, status: 'create', companyDependency: dependency };
        render(<NextIntlClientProvider locale={locale} messages={messages}>
            <CompanyChoices row={row} allRows={[row]} selectedId={selectedId} onChoice={vi.fn()} />
        </NextIntlClientProvider>);
        return row;
    }

    it('announces creation of the decoded company without asking for a choice', () => {
        const row = mount({ name: '㈱テスト', key: 'test', truncated: false, candidates: [] });
        expect(screen.getByText(t('companyWillCreate', { name: '㈱テスト' }))).toBeTruthy();
        expect(screen.queryByText(t('companyChoice', { name: '㈱テスト' }))).toBeNull();
        expect(screen.queryByRole('button')).toBeNull();
        expect(needsImportDecision(row)).toBe(false);
    });

    it('requests a choice when a visible company needs explicit selection', () => {
        const row = mount({ name: '㈱テスト', key: 'test', truncated: false, candidates: [candidate] });
        expect(screen.getByText(t('companyChoice', { name: '㈱テスト' }))).toBeTruthy();
        expect(screen.getByRole('button', { name: t('candidateUse') })).toBeTruthy();
        expect(screen.queryByText(t('companyWillCreate', { name: '㈱テスト' }))).toBeNull();
        expect(needsImportDecision(row)).toBe(true);
    });

    it('shows a selection as linked while keeping it reviewable', () => {
        mount({ name: '㈱テスト', key: 'test', truncated: false, candidates: [candidate], selectedId: 10 }, 10);
        expect(screen.getByText(t('companySelected', { name: '㈱テスト' }))).toBeTruthy();
        expect(screen.queryByText(t('companyChoice', { name: '㈱テスト' }))).toBeNull();
        expect(screen.queryByText(t('companyWillCreate', { name: '㈱テスト' }))).toBeNull();
        expect(screen.queryByText(t('companyChoiceHint'))).toBeNull();
    });

    it('reopens a selected company disclosure and applies the choice to every matching row', () => {
        const rows: ImportRowAnalysis[] = [0, 1].map((rowIndex) => ({
            rowIndex, status: 'invalid', companyDependency: {
                name: candidate.name, key: 'test', truncated: false, candidates: [candidate],
            },
        }));
        const onChoice = vi.fn<(indices: number[], id: number | null) => void>();
        function ReviewState() {
            const [companyLinks, setCompanyLinks] = useState<Record<number, number>>({});
            return <>{rows.map((row) => <section key={row.rowIndex} data-testid={`company-row-${row.rowIndex}`}>
                <CompanyChoices row={row} allRows={rows} selectedId={companyLinks[row.rowIndex]}
                    onChoice={(indices, id) => {
                        onChoice(indices, id);
                        setCompanyLinks((previous) => {
                            const next = { ...previous };
                            for (const index of indices) {
                                if (id == null) delete next[index];
                                else next[index] = id;
                            }
                            return next;
                        });
                    }} />
            </section>)}</>;
        }
        render(<NextIntlClientProvider locale={locale} messages={messages}>
            <ReviewState />
        </NextIntlClientProvider>);
        const firstRow = screen.getByTestId('company-row-0');
        fireEvent.click(within(firstRow).getByRole('button', { name: t('candidateUse') }));
        expect(onChoice).toHaveBeenLastCalledWith([0], candidate.recordId);
        expect(within(firstRow).queryByText(t('companyChoice', { name: candidate.name }))).toBeNull();
        expect(within(firstRow).queryByText(t('companyChoiceHint'))).toBeNull();
        expect(firstRow.querySelector('details')?.open).toBe(false);
        fireEvent.click(within(firstRow).getByText(t('companySelected', { name: candidate.name })));
        expect(firstRow.querySelector('details')?.open).toBe(true);
        fireEvent.click(within(firstRow).getByRole('button', { name: t('applyCompanyChoice', { count: 2 }) }));
        expect(onChoice).toHaveBeenLastCalledWith([0, 1], candidate.recordId);
        for (const row of rows) {
            const reviewedRow = screen.getByTestId(`company-row-${row.rowIndex}`);
            expect(within(reviewedRow).getByText(t('companySelected', { name: candidate.name }))).toBeTruthy();
            expect(within(reviewedRow).queryByText(t('companyChoice', { name: candidate.name }))).toBeNull();
        }
    });

    it('never promises creation when the company candidate set was truncated', () => {
        mount({ name: '㈱テスト', key: 'test', truncated: true, candidates: [] });
        expect(screen.getByText(t('problems.COMPANY_TRUNCATED'))).toBeTruthy();
        expect(screen.queryByText(t('companyWillCreate', { name: '㈱テスト' }))).toBeNull();
        expect(screen.queryByRole('button')).toBeNull();
    });
});
