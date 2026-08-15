import { describe, expect, it } from 'vitest';

import {
    REPORT_DATA_SOURCES,
    REPORT_GROUPS,
    REPORT_MEASURES,
    reportGroupsForMeasure,
} from '@/app/components/reports/reportConfig';

const DOCUMENT_GROUPS = ['none', 'date', 'owner', 'company'];
const DISCOUNT_GROUPS = ['none', 'date', 'pipeline', 'stage', 'owner', 'company'];

/**
 * The builder's measure and grouping vocabulary is a client mirror of the server's fail-closed
 * catalog: offering a pair the backend rejects turns a save into a 400 the author cannot explain.
 */
describe('commercial quote, approval, and discount measures are reachable in the builder', () => {
    it('exposes the documents data source', () => {
        expect(REPORT_DATA_SOURCES).toContain('documents');
    });

    it('lists every documents measure and its groupings', () => {
        expect(REPORT_MEASURES.documents).toEqual([
            'quote_count',
            'quote_issue_rate',
            'document_to_win_rate',
            'approval_decision_count',
            'approval_cycle_days',
        ]);
        expect(REPORT_GROUPS.documents).toEqual(DOCUMENT_GROUPS);
        for (const measure of REPORT_MEASURES.documents) {
            expect(reportGroupsForMeasure('documents', measure)).toEqual(DOCUMENT_GROUPS);
        }
    });

    it('offers both discount measures on deals with the deal grouping set minus status and deal', () => {
        expect(REPORT_MEASURES.deals).toContain('effective_discount_percent');
        expect(REPORT_MEASURES.deals).toContain('open_discount_percent');
        expect(reportGroupsForMeasure('deals', 'effective_discount_percent')).toEqual(DISCOUNT_GROUPS);
        expect(reportGroupsForMeasure('deals', 'open_discount_percent')).toEqual(DISCOUNT_GROUPS);
    });
});
