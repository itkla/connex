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

type AuditMetadataValue = AuditMetadataRow['value'];

type MetadataField = {
    key: string;
    labelKey: AuditMetadataLabelKey;
    validate: (value: unknown) => AuditMetadataValue;
    mono?: boolean;
};

const SAFE_TOKEN = /^[A-Za-z0-9][A-Za-z0-9._:/@-]{0,127}$/;
const SAFE_ERROR = /^[A-Za-z][A-Za-z0-9.$_-]{0,127}$/;
const SECRET_PURPOSES = new Set([
    'workspace.smtp.password',
    'workspace.delivery.provider_credential',
    'workspace.delivery.webhook_secret',
    'workspace.connector.credential',
    'org.sso.oidc_client_secret',
    'org.sso.saml_sp_private_key',
    'org.ai.provider_credential',
    'user.provider.google_token',
    'user.provider.microsoft_token',
]);
const AI_PROVIDERS = new Set(['azure_openai', 'bedrock', 'openai_compatible', 'vertex', 'unresolved']);
const AI_FEATURES = new Set([
    'deal.brief',
    'deal.risk_rationale',
    'intro.rationale',
    'report.narrative',
    'business_card.scan',
]);
const AI_OUTCOMES = new Set(['attempt', 'success', 'failure', 'blocked']);
const AI_REASONS = new Set([
    'gate',
    'media_admission',
    'provider',
    'provider_capability',
    'serialization',
    'leak',
    'provider_exception',
]);
const AI_PARSE_OUTCOMES = new Set(['parsed', 'truncated', 'malformed_output']);

function isRecord(value: unknown): value is Record<string, unknown> {
    return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function isAuditChange(value: unknown): value is AuditChange {
    return isRecord(value) && ('old' in value || 'new' in value);
}

function resolvedMetadataValue(value: unknown): unknown {
    return isAuditChange(value) ? ('new' in value ? value.new : value.old) : value;
}

function knownString(allowed: ReadonlySet<string>): (value: unknown) => AuditMetadataValue {
    return (value) => {
        const resolved = resolvedMetadataValue(value);
        return typeof resolved === 'string' && allowed.has(resolved) ? resolved : null;
    };
}

function token(value: unknown): AuditMetadataValue {
    const resolved = resolvedMetadataValue(value);
    return typeof resolved === 'string' && SAFE_TOKEN.test(resolved) ? resolved : null;
}

function nonNegativeInteger(value: unknown): AuditMetadataValue {
    const resolved = resolvedMetadataValue(value);
    return typeof resolved === 'number' && Number.isSafeInteger(resolved) && resolved >= 0 ? resolved : null;
}

function booleanValue(value: unknown): AuditMetadataValue {
    const resolved = resolvedMetadataValue(value);
    return typeof resolved === 'boolean' ? resolved : null;
}

function tokenList(value: unknown): AuditMetadataValue {
    const resolved = resolvedMetadataValue(value);
    if (!Array.isArray(resolved) || resolved.length === 0 || resolved.length > 16) return null;
    return resolved.every((item) => typeof item === 'string' && SAFE_TOKEN.test(item))
        ? resolved.join(', ')
        : null;
}

const SECRET_FIELDS: readonly MetadataField[] = [
    { key: 'secretId', labelKey: 'metaSecretReference', validate: nonNegativeInteger, mono: true },
    { key: 'purpose', labelKey: 'metaPurpose', validate: knownString(SECRET_PURPOSES) },
    { key: 'keyId', labelKey: 'metaKeyId', validate: token, mono: true },
    { key: 'previousKeyId', labelKey: 'metaPreviousKeyId', validate: token, mono: true },
    { key: 'newKeyId', labelKey: 'metaNewKeyId', validate: token, mono: true },
    { key: 'rewrapped', labelKey: 'metaRewrapped', validate: booleanValue },
    { key: 'healthy', labelKey: 'metaHealthy', validate: booleanValue },
    { key: 'available', labelKey: 'metaAvailable', validate: booleanValue },
    { key: 'totalSecrets', labelKey: 'metaTotalSecrets', validate: nonNegativeInteger },
    { key: 'staleSecrets', labelKey: 'metaStaleSecrets', validate: nonNegativeInteger },
    { key: 'missingKeySecrets', labelKey: 'metaMissingKeySecrets', validate: nonNegativeInteger },
    { key: 'disabledKeySecrets', labelKey: 'metaDisabledKeySecrets', validate: nonNegativeInteger },
    { key: 'mismatchedSecrets', labelKey: 'metaMismatchedSecrets', validate: nonNegativeInteger },
    { key: 'unsupportedAlgorithmSecrets', labelKey: 'metaUnsupportedAlgorithmSecrets', validate: nonNegativeInteger },
];

const AI_FIELDS: readonly MetadataField[] = [
    { key: 'provider', labelKey: 'metaProvider', validate: knownString(AI_PROVIDERS) },
    { key: 'region', labelKey: 'metaRegion', validate: token },
    { key: 'model', labelKey: 'metaModel', validate: token, mono: true },
    { key: 'feature', labelKey: 'metaFeature', validate: knownString(AI_FEATURES) },
    { key: 'correlationId', labelKey: 'metaCorrelationId', validate: token, mono: true },
    { key: 'messageCount', labelKey: 'metaMessageCount', validate: nonNegativeInteger },
    { key: 'mediaCount', labelKey: 'metaMediaCount', validate: nonNegativeInteger },
    { key: 'mediaBytes', labelKey: 'metaMediaBytes', validate: nonNegativeInteger },
    { key: 'mediaTypes', labelKey: 'metaMediaTypes', validate: tokenList },
    { key: 'structured', labelKey: 'metaStructured', validate: booleanValue },
    { key: 'inputTokens', labelKey: 'metaInputTokens', validate: nonNegativeInteger },
    { key: 'outputTokens', labelKey: 'metaOutputTokens', validate: nonNegativeInteger },
    { key: 'stopReason', labelKey: 'metaStopReason', validate: token },
    { key: 'demaskWarnings', labelKey: 'metaDemaskWarnings', validate: nonNegativeInteger },
    { key: 'parseOutcome', labelKey: 'metaParseOutcome', validate: knownString(AI_PARSE_OUTCOMES) },
    { key: 'reason', labelKey: 'metaReason', validate: knownString(AI_REASONS) },
];

function metadataRows(
    changes: Record<string, unknown> | null | undefined,
    fields: readonly MetadataField[],
): AuditMetadataRow[] {
    if (!changes) return [];
    return fields.flatMap((field) => {
        const value = field.validate(changes[field.key]);
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
    if (entry.action.startsWith('secret_store.')) {
        return entry.outcome === 'success' || entry.outcome === 'failure' ? entry.outcome : null;
    }
    const aiEntry = entry.entityType === 'ai_call' || entry.action === 'ai.llm.call';
    if (!aiEntry) return entry.outcome;
    const metadataOutcome = knownString(AI_OUTCOMES)(entry.changes?.outcome);
    if (typeof metadataOutcome === 'string') return metadataOutcome;
    return entry.outcome != null && AI_OUTCOMES.has(entry.outcome) ? entry.outcome : null;
}

/** Returns the target label that may be rendered for an audit row. */
export function auditTargetLabel(
    entry: Pick<AuditLogEntry, 'action' | 'entityType' | 'targetLabel' | 'changes'>,
): string | null {
    if (entry.action.startsWith('secret_store.')) {
        const purpose = knownString(SECRET_PURPOSES)(entry.changes?.purpose);
        return typeof purpose === 'string' ? purpose : 'secret_store';
    }
    if (entry.entityType === 'ai_call' || entry.action === 'ai.llm.call') {
        const provider = knownString(AI_PROVIDERS)(entry.changes?.provider);
        const region = token(entry.changes?.region);
        const safeProvider = typeof provider === 'string' ? provider : 'ai_call';
        return typeof region === 'string' ? `${safeProvider}/${region}` : safeProvider;
    }
    return entry.targetLabel;
}

/** Returns the summary that may be rendered for an audit row. */
export function auditSummary(
    entry: Pick<AuditLogEntry, 'action' | 'entityType' | 'summary' | 'outcome' | 'changes'>,
): string | null {
    if (entry.entityType === 'ai_call' || entry.action === 'ai.llm.call') {
        const outcome = auditOutcome(entry);
        return outcome == null ? 'AI call' : `AI call ${outcome}`;
    }
    if (!entry.action.startsWith('secret_store.')) return entry.summary;
    const summaries: Readonly<Record<string, string>> = {
        'secret_store.secret.use': 'Secret used',
        'secret_store.secret.use_failed': 'Secret use failed',
        'secret_store.secret.rewrap': 'Secret rewrapped',
        'secret_store.secret.rewrap_failed': 'Secret rewrap failed',
        'secret_store.diagnostics.read': 'Secret store diagnostics read',
    };
    return summaries[entry.action] ?? 'Secret store operation';
}

/** Returns a controlled error category for sensitive audit rows. */
export function auditError(
    entry: Pick<AuditLogEntry, 'action' | 'entityType' | 'context'>,
): string | null {
    const error = entry.context?.error;
    if (typeof error !== 'string') return null;
    const sensitive = entry.action.startsWith('secret_store.')
        || entry.entityType === 'ai_call'
        || entry.action === 'ai.llm.call';
    return sensitive && !SAFE_ERROR.test(error) ? null : error;
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
        const targetLabel = auditTargetLabel(entry);
        return {
            diffs: [],
            metadata: [
                ...fixedMetadata({ ...entry, targetLabel, outcome: result }),
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
