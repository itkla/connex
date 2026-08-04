import { describe, expect, it } from 'vitest';

import { presentAuditEntry } from '@/app/lib/auditPresentation';
import type { AuditLogEntry } from '@/app/lib/types';

function entry(overrides: Partial<AuditLogEntry>): AuditLogEntry {
    return {
        id: 1,
        action: 'company.update',
        entityType: 'company',
        entityId: 4,
        actorId: 2,
        actorLabel: 'Operator',
        targetLabel: 'Example',
        outcome: 'success',
        summary: 'Updated company',
        createdAt: '2026-08-04 08:00:00',
        ...overrides,
    };
}

describe('presentAuditEntry', () => {
    it('renders allowlisted secret metadata instead of treating values as empty diffs', () => {
        const presentation = presentAuditEntry(entry({
            action: 'secret_store.secret.use',
            entityType: 'organization',
            changes: {
                secretId: 17,
                purpose: 'ORG_AI_PROVIDER_CREDENTIAL',
                keyId: 'primary-v2',
                rewrapped: false,
            },
        }));

        expect(presentation.diffs).toEqual([]);
        expect(presentation.metadata).toEqual(expect.arrayContaining([
            expect.objectContaining({ key: 'secretId', value: 17 }),
            expect.objectContaining({ key: 'purpose', value: 'ORG_AI_PROVIDER_CREDENTIAL' }),
            expect.objectContaining({ key: 'keyId', value: 'primary-v2' }),
            expect.objectContaining({ key: 'rewrapped', value: false }),
        ]));
    });

    it('renders safe AI-call evidence while excluding prompts, responses, and credentials', () => {
        const presentation = presentAuditEntry(entry({
            action: 'ai.llm.call',
            entityType: 'ai_call',
            entityId: null,
            targetLabel: 'bedrock/us-east-1',
            changes: {
                provider: 'bedrock',
                model: 'anthropic.claude',
                feature: 'deal_brief',
                correlationId: 'corr-123',
                outcome: 'blocked',
                inputTokens: 80,
                outputTokens: 25,
                prompt: 'private CRM content',
                response: 'private model output',
                credential: 'secret-token',
            },
        }));

        expect(presentation.metadata).toEqual(expect.arrayContaining([
            expect.objectContaining({ key: 'provider', value: 'bedrock' }),
            expect.objectContaining({ key: 'model', value: 'anthropic.claude' }),
            expect.objectContaining({ key: 'correlationId', value: 'corr-123' }),
            expect.objectContaining({ key: 'result', value: 'blocked' }),
        ]));
        expect(presentation.metadata.map((row) => row.key)).not.toEqual(expect.arrayContaining([
            'prompt',
            'response',
            'credential',
        ]));
    });

    it('keeps null and partial legacy domain entries useful without rendering blank fields', () => {
        const missing = presentAuditEntry(entry({
            action: 'secret_store.secret.use_failed',
            entityType: 'workspace',
            targetLabel: null,
            outcome: null,
            changes: null,
        }));
        const legacy = presentAuditEntry(entry({
            action: 'ai.llm.call',
            entityType: 'ai_call',
            changes: { provider: { old: null, new: 'vertex' }, model: {} },
        }));

        expect(missing.metadata).toEqual(expect.arrayContaining([
            expect.objectContaining({ key: 'operation', value: 'secret_store.secret.use_failed' }),
            expect.objectContaining({ key: 'result', value: null }),
            expect.objectContaining({ key: 'target', value: 'workspace #4' }),
        ]));
        expect(legacy.metadata).toEqual(expect.arrayContaining([
            expect.objectContaining({ key: 'provider', value: 'vertex' }),
        ]));
        expect(legacy.metadata.some((row) => row.key === 'model')).toBe(false);
    });

    it('preserves ordinary field diffs and ignores malformed legacy payloads', () => {
        const presentation = presentAuditEntry(entry({
            changes: {
                name: { old: 'Before', new: 'After' },
                partial: { old: 'Known' },
                malformed: 'not-a-diff',
            },
        }));

        expect(presentation.diffs).toEqual([
            { field: 'name', old: 'Before', new: 'After' },
            { field: 'partial', old: 'Known', new: undefined },
        ]);
        expect(presentation.metadata).toEqual([]);
    });
});
