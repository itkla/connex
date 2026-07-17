import type { DocumentContent, DocumentType } from '@/app/lib/types';

/** The merge tokens a template author can insert; resolved server-side at generation. */
export const DOCUMENT_TOKENS = [
    'workspace.name',
    'company.name',
    'company.address',
    'deal.name',
    'deal.currency',
    'owner.name',
    'date',
    'total',
] as const;

export type DocumentToken = (typeof DOCUMENT_TOKENS)[number];

/**
 * Substitutes {{token}} occurrences in a template string. Mirrors the server-side resolver so the
 * builder preview shows what generation will produce; the persisted document is always resolved by
 * the backend, never by this function.
 */
export function resolveTokens(template: string | null | undefined, values: Record<string, string>): string {
    if (!template) return '';
    let result = template;
    for (const [key, value] of Object.entries(values)) {
        result = result.split(`{{${key}}}`).join(value);
    }
    return result;
}

type TemplateDraft = {
    title?: string | null;
    intro?: string | null;
    terms?: string | null;
    footer?: string | null;
};

/**
 * Builds a fully-resolved {@link DocumentContent} from a template draft and representative sample
 * data, for the builder's live preview. Sample line items and totals are illustrative constants, not
 * real deal figures.
 */
export function sampleDocumentContent(draft: TemplateDraft, values: Record<string, string>): DocumentContent {
    const currency = values['deal.currency'] || 'USD';
    return {
        generatedAt: '2026-01-15T10:00:00',
        workspace: { name: values['workspace.name'] || '' },
        company: { name: values['company.name'] || '', address: values['company.address'] || null },
        owner: { name: values['owner.name'] || '' },
        deal: { name: values['deal.name'] || '', currency },
        sections: {
            title: resolveTokens(draft.title, values),
            intro: resolveTokens(draft.intro, values),
            terms: resolveTokens(draft.terms, values),
            footer: resolveTokens(draft.footer, values),
        },
        lineItems: [
            {
                id: -1, dealId: -1, productId: null, name: values['sample.item1'] ?? 'Implementation',
                sku: null, unit: null, unitPrice: 4000, quantity: 1, discountType: null, discountValue: null,
                taxRate: 10, billingFrequency: 'one_time', description: null,
                servicePeriodStart: null, servicePeriodEnd: null, position: 0, currency,
                lineSubtotal: 4000, lineTax: 400, lineTotal: 4400, createdAt: '', updatedAt: '',
            },
            {
                id: -2, dealId: -1, productId: null, name: values['sample.item2'] ?? 'Platform subscription',
                sku: null, unit: null, unitPrice: 1200, quantity: 12, discountType: null, discountValue: null,
                taxRate: 10, billingFrequency: 'recurring', description: null,
                servicePeriodStart: null, servicePeriodEnd: null, position: 1, currency,
                lineSubtotal: 14400, lineTax: 1440, lineTotal: 15840, createdAt: '', updatedAt: '',
            },
        ],
        totals: {
            currency, subtotal: 18400, tax: 1840, oneTimeTotal: 4400, recurringTotal: 15840, grandTotal: 20240,
        },
    };
}

/** Sample token values for the preview, localized so ja templates preview in Japanese. */
export function sampleTokenValues(locale: string): Record<string, string> {
    const ja = locale.startsWith('ja');
    return {
        'workspace.name': ja ? '株式会社コネックス' : 'Connex Inc.',
        'company.name': ja ? '山田商事株式会社' : 'Northwind Trading',
        'company.address': ja ? '東京都千代田区丸の内1-1-1' : '1 Market St, San Francisco, CA',
        'deal.name': ja ? '2026年 年間契約' : '2026 Annual Agreement',
        'deal.currency': ja ? 'JPY' : 'USD',
        'owner.name': ja ? '佐藤 花子' : 'Alex Rivera',
        date: '2026-01-15',
        total: ja ? 'JPY 20,240.00' : 'USD 20,240.00',
        'sample.item1': ja ? '導入支援' : 'Implementation',
        'sample.item2': ja ? 'プラットフォーム利用料' : 'Platform subscription',
    };
}

/** Ordered document types for pickers. */
export const DOCUMENT_TYPES: DocumentType[] = ['quote', 'proposal', 'order_form', 'contract'];
