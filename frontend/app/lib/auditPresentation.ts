import type { AuditChange, AuditLogEntry } from '@/app/lib/types';

export type AuditMetadataLabelKey =
    | 'metaOperation'
    | 'metaResult'
    | 'metaTarget'
    | 'metaSecretReference'
    | 'metaPurpose'
    | 'metaKeyId'
    | 'metaPreviousKeyId'
    | 'metaNewKeyId'
    | 'metaRewrapped'
    | 'metaHealthy'
    | 'metaAvailable'
    | 'metaTotalSecrets'
    | 'metaStaleSecrets'
    | 'metaMissingKeySecrets'
    | 'metaDisabledKeySecrets'
    | 'metaMismatchedSecrets'
    | 'metaUnsupportedAlgorithmSecrets'
    | 'metaProvider'
    | 'metaRegion'
    | 'metaModel'
    | 'metaFeature'
    | 'metaCorrelationId'
    | 'metaMessageCount'
    | 'metaMediaCount'
    | 'metaMediaBytes'
    | 'metaMediaTypes'
    | 'metaStructured'
    | 'metaInputTokens'
    | 'metaOutputTokens'
    | 'metaStopReason'
    | 'metaDemaskWarnings'
    | 'metaParseOutcome'
    | 'metaReason';

export type AuditMetadataRow = {
    key: string;
    labelKey: AuditMetadataLabelKey;
    value: string | number | boolean | null;
    mono: boolean;
};

export type AuditDiffRow = {
    field: string;
    old: unknown;
    new: unknown;
};

export type AuditPresentation = {
    diffs: AuditDiffRow[];
    metadata: AuditMetadataRow[];
};

type MetadataField = {
    key: string;
    labelKey: AuditMetadataLabelKey;
    mono?: boolean;
};

const SECRET_FIELDS: readonly MetadataField[] = [
    { key: 'secretId', labelKey: 'metaSecretReference', mono: true },
    { key: 'purpose', labelKey: 'metaPurpose' },
    { key: 'keyId', labelKey: 'metaKeyId', mono: true },
    { key: 'previousKeyId', labelKey: 'metaPreviousKeyId', mono: true },
    { key: 'newKeyId', labelKey: 'metaNewKeyId', mono: true },
    { key: 'rewrapped', labelKey: 'metaRewrapped' },
    { key: 'healthy', labelKey: 'metaHealthy' },
    { key: 'available', labelKey: 'metaAvailable' },
    { key: 'totalSecrets', labelKey: 'metaTotalSecrets' },
    { key: 'staleSecrets', labelKey: 'metaStaleSecrets' },
    { key: 'missingKeySecrets', labelKey: 'metaMissingKeySecrets' },
    { key: 'disabledKeySecrets', labelKey: 'metaDisabledKeySecrets' },
    { key: 'mismatchedSecrets', labelKey: 'metaMismatchedSecrets' },
    { key: 'unsupportedAlgorithmSecrets', labelKey: 'metaUnsupportedAlgorithmSecrets' },
];

const AI_FIELDS: readonly MetadataField[] = [
    { key: 'provider', labelKey: 'metaProvider' },
    { key: 'region', labelKey: 'metaRegion' },
    { key: 'model', labelKey: 'metaModel', mono: true },
    { key: 'feature', labelKey: 'metaFeature' },
    { key: 'correlationId', labelKey: 'metaCorrelationId', mono: true },
    { key: 'messageCount', labelKey: 'metaMessageCount' },
    { key: 'mediaCount', labelKey: 'metaMediaCount' },
    { key: 'mediaBytes', labelKey: 'metaMediaBytes' },
    { key: 'mediaTypes', labelKey: 'metaMediaTypes' },
    { key: 'structured', labelKey: 'metaStructured' },
    { key: 'inputTokens', labelKey: 'metaInputTokens' },
    { key: 'outputTokens', labelKey: 'metaOutputTokens' },
    { key: 'stopReason', labelKey: 'metaStopReason' },
    { key: 'demaskWarnings', labelKey: 'metaDemaskWarnings' },
    { key: 'parseOutcome', labelKey: 'metaParseOutcome' },
    { key: 'reason', labelKey: 'metaReason' },
];

function isRecord(value: unknown): value is Record<string, unknown> {
    return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function isAuditChange(value: unknown): value is AuditChange {
    return isRecord(value) && ('old' in value || 'new' in value);
}

function safeMetadataValue(value: unknown): string | number | boolean | null {
    const resolved = isAuditChange(value) ? ('new' in value ? value.new : value.old) : value;
    if (typeof resolved === 'string' || typeof resolved === 'number' || typeof resolved === 'boolean') {
        return resolved;
    }
    if (Array.isArray(resolved) && resolved.every((item) => (
        typeof item === 'string' || typeof item === 'number' || typeof item === 'boolean'
    ))) {
        return resolved.map(String).join(', ');
    }
    return null;
}

function metadataRows(
    changes: Record<string, unknown> | null | undefined,
    fields: readonly MetadataField[],
): AuditMetadataRow[] {
    if (!changes) return [];
    return fields.flatMap((field) => {
        const value = safeMetadataValue(changes[field.key]);
        return value == null || value === ''
            ? []
            : [{ key: field.key, labelKey: field.labelKey, value, mono: field.mono ?? false }];
    });
}

function fixedMetadata(entry: Pick<AuditLogEntry, 'action' | 'entityType' | 'entityId' | 'targetLabel' | 'outcome'>) {
    const target = entry.targetLabel
        ?? (entry.entityType && entry.entityId != null ? `${entry.entityType} #${entry.entityId}` : null);
    return [
        { key: 'operation', labelKey: 'metaOperation' as const, value: entry.action, mono: true },
        { key: 'result', labelKey: 'metaResult' as const, value: entry.outcome, mono: false },
        { key: 'target', labelKey: 'metaTarget' as const, value: target, mono: false },
    ];
}

/** Resolves the domain outcome used by audit filtering, counts, badges, and details. */
export function auditOutcome(
    entry: Pick<AuditLogEntry, 'action' | 'entityType' | 'outcome' | 'changes'>,
): string | null {
    const aiEntry = entry.entityType === 'ai_call' || entry.action === 'ai.llm.call';
    if (!aiEntry) return entry.outcome;
    const metadataOutcome = safeMetadataValue(entry.changes?.outcome);
    return typeof metadataOutcome === 'string' ? metadataOutcome : entry.outcome;
}

/** Builds a fail-closed audit presentation without exposing arbitrary metadata fields. */
export function presentAuditEntry(
    entry: Pick<AuditLogEntry, 'action' | 'entityType' | 'entityId' | 'targetLabel' | 'outcome' | 'changes'>,
): AuditPresentation {
    const changes = entry.changes;
    const secretEntry = entry.action.startsWith('secret_store.');
    const aiEntry = entry.entityType === 'ai_call' || entry.action === 'ai.llm.call';
    if (secretEntry || aiEntry) {
        const result = auditOutcome(entry);
        return {
            diffs: [],
            metadata: [
                ...fixedMetadata({ ...entry, outcome: result }),
                ...metadataRows(changes, secretEntry ? SECRET_FIELDS : AI_FIELDS),
            ],
        };
    }
    return {
        diffs: changes
            ? Object.entries(changes).flatMap(([field, value]) => (
                isAuditChange(value) ? [{ field, old: value.old, new: value.new }] : []
            ))
            : [],
        metadata: [],
    };
}
