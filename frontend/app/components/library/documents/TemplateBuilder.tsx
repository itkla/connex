'use client';

import { useMemo, useRef, useState } from 'react';
import { useRouter } from 'next/navigation';
import { useTranslations } from 'next-intl';
import { ChevronLeftIcon, CodeBracketIcon } from '@heroicons/react/24/outline';
import { Loader2Icon } from 'lucide-react';

import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Textarea } from '@/components/ui/textarea';
import { Label } from '@/components/ui/label';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs';
import {
    DropdownMenu,
    DropdownMenuContent,
    DropdownMenuItem,
    DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu';
import SectionHeader from '@/app/components/dashboard/SectionHeader';
import DocumentView from '@/app/components/records/documents/DocumentView';
import { toastError, toastSuccess } from '@/app/lib/toast';
import { createDocumentTemplate, updateDocumentTemplate, ApiError } from '@/app/lib/api';
import { DOCUMENT_TOKENS, DOCUMENT_TYPES, sampleDocumentContent, sampleTokenValues } from '@/app/lib/documentTokens';
import type { CreateDocumentTemplatePayload, DocumentTemplate, DocumentType } from '@/app/lib/types';

type Draft = {
    name: string;
    type: DocumentType;
    locale: string;
    title: string;
    intro: string;
    terms: string;
    footer: string;
    active: boolean;
};

function toDraft(template: DocumentTemplate | null): Draft {
    return {
        name: template?.name ?? '',
        type: template?.type ?? 'quote',
        locale: template?.locale ?? 'en',
        title: template?.title ?? '',
        intro: template?.intro ?? '',
        terms: template?.terms ?? '',
        footer: template?.footer ?? '',
        active: template?.active ?? true,
    };
}

/**
 * Full-page builder for a commercial-document template: a grouped section editor with inline
 * merge-token insertion beside a live preview of the rendered document (sample data). The preview is
 * the shared {@link DocumentView}, so an author sees exactly what generation will produce. Replaces
 * the former modal form; the server owns generation and all money math.
 */
export default function TemplateBuilder({ template }: { template: DocumentTemplate | null }) {
    const t = useTranslations('DocumentTemplateBuilder');
    const router = useRouter();
    const [draft, setDraft] = useState<Draft>(() => toDraft(template));
    const [saving, setSaving] = useState(false);

    const patch = (next: Partial<Draft>) => setDraft((prev) => ({ ...prev, ...next }));

    const previewContent = useMemo(
        () => sampleDocumentContent(draft, sampleTokenValues(draft.locale)),
        [draft],
    );

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
                intro: draft.intro.trim() || null,
                terms: draft.terms.trim() || null,
                footer: draft.footer.trim() || null,
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

    const editor = (
        <div className="flex flex-col gap-8">
            <div>
                <SectionHeader title={t('groupIdentity')} />
                <div className="flex flex-col gap-4 px-6">
                    <div className="grid gap-1.5">
                        <Label htmlFor="tpl-name">{t('name')}</Label>
                        <Input id="tpl-name" value={draft.name} maxLength={255}
                            placeholder={t('namePlaceholder')}
                            onChange={(e) => patch({ name: e.target.value })} autoFocus />
                    </div>
                    <div className="grid grid-cols-2 gap-4">
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
                    </div>
                    <label className="flex items-center gap-2 text-sm text-muted-foreground">
                        <input type="checkbox" checked={draft.active}
                            onChange={(e) => patch({ active: e.target.checked })} />
                        {t('active')}
                    </label>
                </div>
            </div>

            <div>
                <SectionHeader title={t('groupSections')} />
                <div className="flex flex-col gap-4 px-6">
                    <TokenField label={t('title')} value={draft.title}
                        onChange={(v) => patch({ title: v })} insertLabel={t('insertToken')} />
                    <TokenField label={t('intro')} value={draft.intro} multiline rows={3}
                        onChange={(v) => patch({ intro: v })} insertLabel={t('insertToken')} />
                    <TokenField label={t('terms')} value={draft.terms} multiline rows={5}
                        onChange={(v) => patch({ terms: v })} insertLabel={t('insertToken')} />
                    <TokenField label={t('footer')} value={draft.footer} multiline rows={2}
                        onChange={(v) => patch({ footer: v })} insertLabel={t('insertToken')} />
                </div>
            </div>
        </div>
    );

    const preview = (
        <div className="flex h-full flex-col">
            <SectionHeader title={t('previewLabel')} />
            <div className="rounded-2xl border border-border bg-muted/40 p-4 sm:p-6">
                <div className="mx-auto max-w-[46rem] rounded-xl border border-border bg-card px-8 py-10 shadow-sm">
                    <DocumentView content={previewContent} type={draft.type} status="draft" version={1} />
                </div>
                <p className="mt-3 text-center text-xs text-muted-foreground">{t('previewNote')}</p>
            </div>
        </div>
    );

    return (
        <div className="min-h-full bg-background">
            <div className="sticky top-0 z-10 border-b border-border bg-background/85 backdrop-blur">
                <div className="mx-auto flex w-full max-w-[100rem] items-center gap-4 px-4 py-3 sm:px-6">
                    <Button variant="ghost" size="sm" onClick={() => router.push('/library/documents')}>
                        <ChevronLeftIcon className="size-4" />
                        {t('back')}
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

            <div className="mx-auto w-full max-w-[100rem] px-4 py-6 sm:px-6">
                <div className="hidden gap-10 lg:grid lg:grid-cols-[minmax(0,0.9fr)_minmax(0,1.1fr)]">
                    <div>{editor}</div>
                    <div className="lg:sticky lg:top-20 lg:self-start">{preview}</div>
                </div>

                <Tabs defaultValue="edit" className="lg:hidden">
                    <TabsList className="mb-4">
                        <TabsTrigger value="edit">{t('tabEdit')}</TabsTrigger>
                        <TabsTrigger value="preview">{t('tabPreview')}</TabsTrigger>
                    </TabsList>
                    <TabsContent value="edit">{editor}</TabsContent>
                    <TabsContent value="preview">{preview}</TabsContent>
                </Tabs>
            </div>
        </div>
    );
}

type TokenFieldProps = {
    label: string;
    value: string;
    onChange: (value: string) => void;
    insertLabel: string;
    multiline?: boolean;
    rows?: number;
};

/**
 * A labelled section field with an inline merge-token menu. Selecting a token inserts {{token}} at
 * the caret (or appends if unfocused), so authors compose against the live preview without leaving
 * the field.
 */
function TokenField({ label, value, onChange, insertLabel, multiline, rows }: TokenFieldProps) {
    const ref = useRef<HTMLInputElement | HTMLTextAreaElement | null>(null);

    const insert = (token: string) => {
        const el = ref.current;
        const marker = `{{${token}}}`;
        const start = el?.selectionStart ?? value.length;
        const end = el?.selectionEnd ?? value.length;
        const next = value.slice(0, start) + marker + value.slice(end);
        onChange(next);
        requestAnimationFrame(() => {
            if (!el) return;
            el.focus();
            const caret = start + marker.length;
            el.setSelectionRange(caret, caret);
        });
    };

    return (
        <div className="grid gap-1.5">
            <div className="flex items-center justify-between">
                <Label>{label}</Label>
                <DropdownMenu>
                    <DropdownMenuTrigger asChild>
                        <Button variant="ghost" size="sm" className="h-7 gap-1.5 px-2 text-xs text-muted-foreground">
                            <CodeBracketIcon className="size-3.5" />
                            {insertLabel}
                        </Button>
                    </DropdownMenuTrigger>
                    <DropdownMenuContent align="end">
                        {DOCUMENT_TOKENS.map((token) => (
                            <DropdownMenuItem key={token} onSelect={() => insert(token)}>
                                <code className="text-xs">{`{{${token}}}`}</code>
                            </DropdownMenuItem>
                        ))}
                    </DropdownMenuContent>
                </DropdownMenu>
            </div>
            {multiline ? (
                <Textarea ref={ref as React.Ref<HTMLTextAreaElement>} value={value} rows={rows ?? 3}
                    onChange={(e) => onChange(e.target.value)} />
            ) : (
                <Input ref={ref as React.Ref<HTMLInputElement>} value={value} maxLength={512}
                    onChange={(e) => onChange(e.target.value)} />
            )}
        </div>
    );
}
