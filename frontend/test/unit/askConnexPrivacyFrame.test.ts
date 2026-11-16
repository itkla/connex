import { describe, expect, it } from 'vitest';

import { parseAiChatPrivacyFrame } from '@/app/lib/realtime';

describe('workspace privacy metadata', () => {
    it('preserves exact authority and projects only metadata', () => {
        expect(parseAiChatPrivacyFrame({ workspaceId: 7, kind: 'privacy_invalidated',
            cutoffGeneration: '9007199254740993', text: 'untrusted extra field' })).toEqual({
            workspaceId: 7, kind: 'privacy_invalidated', cutoffGeneration: '9007199254740993',
        });
    });

    it.each(['-1', '01', '1.0', '', '9223372036854775808', '1e2'])(
        'refuses invalid decimal authority %s', (cutoffGeneration) => {
        expect(parseAiChatPrivacyFrame({ workspaceId: 7, kind: 'privacy_invalidated', cutoffGeneration })).toBeNull();
    });
});
