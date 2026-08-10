import { readFileSync } from 'node:fs';
import path from 'node:path';

import { describe, expect, it } from 'vitest';

function source(relativePath: string): string {
    return readFileSync(path.resolve(process.cwd(), relativePath), 'utf8');
}

describe('manual deal duplicate preflight', () => {
    it('uses the canonical deal endpoint and binds checks to company identity', () => {
        const api = source('app/lib/api.ts');
        const hook = source('app/hooks/useDuplicatePreflight.ts');

        expect(api).toContain('/api/duplicate-preflight/deals');
        expect(hook).toContain("preflightDealDuplicates({");
        expect(hook).toContain('companyId: values.companyId');
        expect(hook).toContain('reviewToken: dealReviewToken');
        expect(hook).toContain("kind === 'deal' ? checked.reviewToken : null");
    });

    it('preserves the acknowledged proof through the submit-time recheck', () => {
        const composer = source('app/components/records/deals/NewDealDialog.tsx');
        const hook = source('app/hooks/useDuplicatePreflight.ts');

        expect(composer).toContain("useDuplicatePreflight('deal'");
        expect(composer).toContain('name: payload.name.trim()');
        expect(composer).toContain('await duplicatePreflight.reviewNow()');
        expect(composer).toContain('!duplicateDecision.duplicateReviewToken');
        expect(composer).toContain('kind="deal"');
        expect(composer).toContain('onAcknowledgedChange={duplicatePreflight.setAcknowledged}');
        expect(hook).toContain("kind === 'deal' ? response?.reviewToken : undefined");
    });

    it.each([
        'app/components/actions/create/DealCreateContainer.tsx',
        'app/components/calendar/CalendarNewDealContainer.tsx',
        'app/components/records/companies/CompanyActionsMenu.tsx',
        'app/components/records/deals/DealsBrowser.tsx',
    ])('%s submits the reviewed token', (relativePath) => {
        const caller = source(relativePath);

        expect(caller).toContain('duplicateReviewToken: string');
        expect(caller).toContain('duplicateReviewToken,');
    });

    it('forces a company-page deal back to its contextual company', () => {
        const companyAction = source(
            'app/components/records/companies/CompanyActionsMenu.tsx',
        );

        expect(companyAction).toContain('company: company.id,');
        expect(companyAction).toContain(
            'company: company.id,\n                duplicateReviewToken,',
        );
        expect(companyAction).toContain('setPayload={setContextualDealPayload}');
        expect(companyAction).toContain(
            'setNewDealPayload((currentPayload) => ({',
        );
    });

    it('ships distinct English and Japanese deal-match evidence', () => {
        const english = source('messages/en/common.json');
        const japanese = source('messages/ja/common.json');

        expect(english).toContain('"DEAL_KEY": "Same deal name and company"');
        expect(japanese).toContain('"DEAL_KEY": "案件名と紐づく会社が一致"');
    });
});
