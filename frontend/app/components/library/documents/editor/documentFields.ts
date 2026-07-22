import type { DocumentToken } from '@/app/lib/documentTokens';

/** A merge field offered in the block builder; {@code token} is the server-resolved merge token. */
export type MergeField = {
    token: DocumentToken;
    labelKey: string;
};

/** Merge fields in insertion-menu order, each mapped to its {@code DocumentTemplateBuilder} label key. */
export const MERGE_FIELDS: MergeField[] = [
    { token: 'company.name', labelKey: 'fieldCompanyName' },
    { token: 'company.address', labelKey: 'fieldCompanyAddress' },
    { token: 'deal.name', labelKey: 'fieldDealName' },
    { token: 'deal.currency', labelKey: 'fieldDealCurrency' },
    { token: 'owner.name', labelKey: 'fieldOwnerName' },
    { token: 'workspace.name', labelKey: 'fieldWorkspaceName' },
    { token: 'date', labelKey: 'fieldDate' },
    { token: 'total', labelKey: 'fieldTotal' },
];

const LABEL_KEY_BY_TOKEN: Record<string, string> = Object.fromEntries(
    MERGE_FIELDS.map((field) => [field.token, field.labelKey]),
);

/** The {@code DocumentTemplateBuilder} label key for a merge token, or null when the token is unknown. */
export function mergeFieldLabelKey(token: string): string | null {
    return LABEL_KEY_BY_TOKEN[token] ?? null;
}
