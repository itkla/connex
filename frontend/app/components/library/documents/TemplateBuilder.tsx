'use client';

import { useState } from 'react';
import { useRouter } from 'next/navigation';
import { useTranslations } from 'next-intl';
import { ChevronLeftIcon } from '@heroicons/react/24/outline';
import { Loader2Icon } from 'lucide-react';

import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import { Checkbox } from '@/components/ui/checkbox';
import SectionHeader from '@/app/components/dashboard/SectionHeader';
import DocumentBodyEditor from '@/app/components/library/documents/editor/DocumentBodyEditor';
import { toastError, toastSuccess } from '@/app/lib/toast';
import { createDocumentTemplate, updateDocumentTemplate, ApiError } from '@/app/lib/api';
import { DOCUMENT_TYPES } from '@/app/lib/documentTokens';
import type { CreateDocumentTemplatePayload, DocumentBodyNode, DocumentTemplate, DocumentType } from '@/app/lib/types';

type Draft = {
    name: string;
    type: DocumentType;
    locale: string;
    title: string;
    body: string | null;
    active: boolean;
};

function textToParagraphs(text: string): DocumentBodyNode[] {
    return text.split(/\n/).map((line) => ({
        type: 'paragraph',
        content: line.trim() ? [{ type: 'text', text: line }] : undefined,
    }));
}

/**
 * Seeds a block body from a legacy template's flat section fields so pre-block templates keep their
 * content when first opened in the builder. Returns null when there is nothing to migrate.
 */
function legacyToBody(template: DocumentTemplate, termsLabel: string): string | null {
    const hasLegacy = [template.intro, template.terms, template.footer].some((section) => section?.trim());
    if (!hasLegacy) return null;
    const content: DocumentBodyNode[] = [];
    if (template.intro?.trim()) content.push(...textToParagraphs(template.intro));
    content.push({ type: 'lineItems' });
    if (template.terms?.trim()) {
        content.push({ type: 'heading', attrs: { level: 3 }, content: [{ type: 'text', text: termsLabel }] });
        content.push(...textToParagraphs(template.terms));
    }
    if (template.footer?.trim()) {
        content.push({ type: 'horizontalRule' });
        content.push(...textToParagraphs(template.footer).map((node) => ({ ...node, attrs: { textAlign: 'center' } })));
    }
    return JSON.stringify({ type: 'doc', content });
}

function bodyHasContent(body: string | null): boolean {
    if (!body || !body.trim()) return false;
    try {
        const doc = JSON.parse(body) as DocumentBodyNode;
        const walk = (node: DocumentBodyNode): boolean => {
            if (node.type === 'lineItems' || node.type === 'mergeToken' || node.type === 'horizontalRule') return true;
            if (node.type === 'text' && node.text?.trim()) return true;
            return (node.content ?? []).some(walk);
        };
        return (doc.content ?? []).some(walk);
    } catch {
        return false;
    }
}

function toDraft(template: DocumentTemplate | null, termsLabel: string): Draft {
    return {
        name: template?.name ?? '',
        type: template?.type ?? 'quote',
        locale: template?.locale ?? 'en',
        title: template?.title ?? '',
        body: template?.body ?? (template ? legacyToBody(template, termsLabel) : null),
        active: template?.active ?? true,
    };
}

/**
 * Full-page builder for a commercial-document template. The left column holds properties (name, type,
 * locale, availability); the right column is the editable "paper" — an inline document title over a
 * WYSIWYG block editor ({@link DocumentBodyEditor}) where authors compose freely and drop merge-field
 * chips and a line-items placeholder. The server owns generation and all money math.
 */
export default function TemplateBuilder({ template }: { template: DocumentTemplate | null }) {
    const t = useTranslations('DocumentTemplateBuilder');
    const router = useRouter();
    const [draft, setDraft] = useState<Draft>(() => toDraft(template, t('slashTermsHeading')));
    const [saving, setSaving] = useState(false);

    const patch = (next: Partial<Draft>) => setDraft((prev) => ({ ...prev, ...next }));

    const save = async () => {
        const name = draft.name.trim();
        if (!name) {
            toastError(t('nameRequired'));
            return;
        }
        setSaving(true);
        try {
            const payload: CreateDocumentTemplatePayload = {
                name,
                type: draft.type,
                locale: draft.locale,
                title: draft.title.trim() || null,
                intro: null,
                terms: null,
                footer: null,
                body: bodyHasContent(draft.body) ? draft.body : null,
                active: draft.active,
            };
            if (template) {
                await updateDocumentTemplate(template.id, payload);
            } else {
                await createDocumentTemplate(payload);
            }
            toastSuccess(t('saved'));
            router.push('/library/documents');
            router.refresh();
        } catch (err) {
            toastError(err instanceof ApiError ? err.message : t('saveFailed'));
        } finally {
            setSaving(false);
        }
    };

    return (
        <div className="min-h-full bg-background">
            <div className="sticky top-0 z-20 border-b border-border bg-background/85 backdrop-blur">
                <div className="mx-auto flex w-full max-w-[100rem] items-center gap-4 px-4 py-3 sm:px-6">
                    <Button variant="ghost" size="sm" onClick={() => router.push('/library/documents')}>
                        <ChevronLeftIcon className="size-4" />
                        <span className="hidden sm:inline">{t('back')}</span>
                    </Button>
                    <div className="min-w-0 flex-1">
                        <h1 className="truncate text-lg font-semibold tracking-tight">
                            {template ? draft.name || t('editTitle') : t('newTitle')}
                        </h1>
                    </div>
                    <Button variant="brand" onClick={save} disabled={saving} className="min-w-24">
                        {saving ? <Loader2Icon className="size-4 animate-spin" /> : t('save')}
                    </Button>
                </div>
            </div>

            <div className="mx-auto grid w-full max-w-[100rem] gap-8 px-4 py-6 sm:px-6 lg:grid-cols-[minmax(0,20rem)_minmax(0,1fr)] lg:gap-10">
                <div className="lg:sticky lg:top-20 lg:self-start">
                    <SectionHeader title={t('groupIdentity')} />
                    <div className="flex flex-col gap-4 px-1 sm:px-6">
                        <div className="grid gap-1.5">
                            <Label htmlFor="tpl-name">{t('name')}</Label>
                            <Input id="tpl-name" value={draft.name} maxLength={255}
                                placeholder={t('namePlaceholder')}
                                onChange={(e) => patch({ name: e.target.value })} autoFocus />
                        </div>
                        <div className="grid gap-1.5">
                            <Label htmlFor="tpl-type">{t('type')}</Label>
                            <Select value={draft.type} onValueChange={(v) => patch({ type: v as DocumentType })}>
                                <SelectTrigger id="tpl-type"><SelectValue /></SelectTrigger>
                                <SelectContent>
                                    {DOCUMENT_TYPES.map((type) => (
                                        <SelectItem key={type} value={type}>{t(`type_${type}`)}</SelectItem>
                                    ))}
                                </SelectContent>
                            </Select>
                        </div>
                        <div className="grid gap-1.5">
                            <Label htmlFor="tpl-locale">{t('locale')}</Label>
                            <Select value={draft.locale} onValueChange={(v) => patch({ locale: v })}>
                                <SelectTrigger id="tpl-locale"><SelectValue /></SelectTrigger>
                                <SelectContent>
                                    <SelectItem value="en">{t('localeEn')}</SelectItem>
                                    <SelectItem value="ja">{t('localeJa')}</SelectItem>
                                </SelectContent>
                            </Select>
                        </div>
                        <label htmlFor="tpl-active" className="flex items-start gap-2 text-sm text-muted-foreground">
                            <Checkbox id="tpl-active" className="mt-0.5" checked={draft.active}
                                onCheckedChange={(checked) => patch({ active: checked === true })} />
                            {t('active')}
                        </label>
                    </div>
                </div>

                <div className="min-w-0">
                    <SectionHeader title={t('documentLabel')} />
                    <div className="rounded-2xl border border-border bg-muted/40 p-3 sm:p-6">
                        <div className="mx-auto max-w-[52rem] rounded-xl border border-border bg-card px-4 py-6 shadow-sm sm:px-10 sm:py-12">
                            <div className="mb-8 flex items-start justify-between gap-4">
                                <div className="min-w-0 flex-1">
                                    <div className="text-xs font-medium uppercase tracking-[0.18em] text-muted-foreground">
                                        {t(`type_${draft.type}`)}
                                    </div>
                                    <input
                                        value={draft.title}
                                        maxLength={512}
                                        placeholder={t('titlePlaceholder')}
                                        onChange={(e) => patch({ title: e.target.value })}
                                        className="mt-2 w-full bg-transparent text-2xl font-semibold tracking-tight text-foreground outline-none placeholder:text-muted-foreground/40 sm:text-3xl"
                                    />
                                </div>
                                <div className="hidden shrink-0 space-y-1 text-right text-xs text-muted-foreground/70 sm:block">
                                    <div>{t('sampleVersion')}</div>
                                    <div>{t('sampleGenerated')}</div>
                                </div>
                            </div>
                            <DocumentBodyEditor value={draft.body} onChange={(json) => patch({ body: json })} />
                        </div>
                        <p className="mt-3 text-center text-xs text-muted-foreground">{t('builderNote')}</p>
                    </div>
                </div>
            </div>
        </div>
    );
}
