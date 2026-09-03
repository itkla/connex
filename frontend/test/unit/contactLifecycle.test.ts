import { describe, expect, it } from 'vitest';

import {
    disqualificationReasonLabel,
    isCanonicalDisqualificationReasonCode,
} from '@/app/lib/contactLifecycle';

describe('isCanonicalDisqualificationReasonCode', () => {
    it('accepts only the exact API code format without normalizing input', () => {
        expect(isCanonicalDisqualificationReasonCode('NO_FIT')).toBe(true);
        expect(isCanonicalDisqualificationReasonCode('A1')).toBe(true);
        expect(isCanonicalDisqualificationReasonCode('A')).toBe(false);
        expect(isCanonicalDisqualificationReasonCode('partner_only')).toBe(false);
        expect(isCanonicalDisqualificationReasonCode(' BAD_CODE')).toBe(false);
        expect(isCanonicalDisqualificationReasonCode('BAD-CODE')).toBe(false);
        expect(isCanonicalDisqualificationReasonCode('ÖTHER')).toBe(false);
        expect(isCanonicalDisqualificationReasonCode(`A${'0'.repeat(32)}`)).toBe(false);
    });
});

describe('disqualificationReasonLabel', () => {
    const translate = (key: string) => `translated:${key}`;

    it('uses the localized built-in label when no stored label exists', () => {
        expect(disqualificationReasonLabel('NO_FIT', null, translate))
            .toBe('translated:reason.NO_FIT');
    });

    it('prefers a workspace-authored label', () => {
        expect(disqualificationReasonLabel('NO_FIT', 'Not our market', translate))
            .toBe('Not our market');
    });

    it('shows an unknown historical code verbatim instead of blank', () => {
        expect(disqualificationReasonLabel('RETIRED_IMPORT_CODE', null, translate))
            .toBe('RETIRED_IMPORT_CODE');
    });
});
