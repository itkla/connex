'use client';

import { useState } from 'react';
import { useTranslations } from 'next-intl';
import { Loader2Icon } from 'lucide-react';

import {
    Dialog,
    DialogClose,
    DialogContent,
    DialogDescription,
    DialogFooter,
    DialogHeader,
    DialogTitle,
} from '@/components/ui/dialog';
import {
    Select,
    SelectContent,
    SelectItem,
    SelectTrigger,
    SelectValue,
} from '@/components/ui/select';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Textarea } from '@/components/ui/textarea';
import { Label } from '@/components/ui/label';
import { DialogStatusCover, resolveDialogStatus } from '@/components/ui/dialog-status-cover';
import { toastError } from '@/app/lib/toast';
import { createDocumentTemplate, updateDocumentTemplate, ApiError } from '@/app/lib/api';
import type { CreateDocumentTemplatePayload, DocumentTemplate, DocumentType } from '@/app/lib/types';

type Props = {
    open: boolean;
    onOpenChange: (open: boolean) => void;
    mode: 'create' | 'edit';
    template?: DocumentTemplate | null;
    onSaved: (template: DocumentTemplate) => void;
};

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

const TOKENS = [
    '{{workspace.name}}', '{{company.name}}', '{{company.address}}', '{{deal.name}}',
    '{{deal.currency}}', '{{owner.name}}', '{{date}}', '{{total}}',
];

function toDraft(template?: DocumentTemplate | null): Draft {
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
 * Create/edit form for a commercial-document template. Section fields may carry {{merge tokens}},
 * which the server resolves at document-generation time — this dialog only stores the raw template.
 */
export default function DocumentTemplateDialog({ open, onOpenChange, mode, template, onSaved }: Props) {
    const t = useTranslations('DocumentTemplateDialog');
    const [draft, setDraft] = useState<Draft>(() => toDraft(template));
    const [saving, setSaving] = useState(false);
    const [succeeded, setSucceeded] = useState(false);

    const patch = (next: Partial<Draft>) => setDraft((prev) => ({ ...prev, ...next }));

    const submit = async () => {
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
            const saved = mode === 'create'
                ? await createDocumentTemplate(payload)
                : await updateDocumentTemplate(template!.id, payload);
            setSucceeded(true);
            setTimeout(() => {
                setSucceeded(false);
                onSaved(saved);
                onOpenChange(false);
            }, 700);
        } catch (err) {
            toastError(err instanceof ApiError ? err.message : t('saveFailed'));
        } finally {
            setSaving(false);
        }
    };

    const status = resolveDialogStatus({ isLoading: saving, isSuccess: succeeded });

    return (
        <Dialog open={open} onOpenChange={(next) => { if (!saving) onOpenChange(next); }}>
            <DialogContent className="gap-0 overflow-hidden p-0 sm:max-w-xl">
                <DialogStatusCover status={status} />
                <div className="max-h-[85vh] overflow-y-auto px-6 pb-6">
                    <DialogHeader className="-mt-12 mb-5">
                        <DialogTitle className="text-xl font-semibold tracking-tight">
                            {mode === 'create' ? t('createTitle') : t('editTitle')}
                        </DialogTitle>
                        <DialogDescription>{t('description')}</DialogDescription>
                    </DialogHeader>

                    <form
                        onSubmit={(e) => { e.preventDefault(); if (!saving) submit(); }}
                        className="grid gap-4"
                    >
                        <div className="grid gap-1.5">
                            <Label htmlFor="template-name">{t('name')}</Label>
                            <Input id="template-name" value={draft.name} maxLength={255}
                                onChange={(e) => patch({ name: e.target.value })} autoFocus />
                        </div>

                        <div className="grid grid-cols-2 gap-4">
                            <div className="grid gap-1.5">
                                <Label htmlFor="template-type">{t('type')}</Label>
                                <Select value={draft.type} onValueChange={(v) => patch({ type: v as DocumentType })}>
                                    <SelectTrigger id="template-type"><SelectValue /></SelectTrigger>
                                    <SelectContent>
                                        <SelectItem value="quote">{t('typeQuote')}</SelectItem>
                                        <SelectItem value="proposal">{t('typeProposal')}</SelectItem>
                                        <SelectItem value="order_form">{t('typeOrderForm')}</SelectItem>
                                        <SelectItem value="contract">{t('typeContract')}</SelectItem>
                                    </SelectContent>
                                </Select>
                            </div>
                            <div className="grid gap-1.5">
                                <Label htmlFor="template-locale">{t('locale')}</Label>
                                <Select value={draft.locale} onValueChange={(v) => patch({ locale: v })}>
                                    <SelectTrigger id="template-locale"><SelectValue /></SelectTrigger>
                                    <SelectContent>
                                        <SelectItem value="en">{t('localeEn')}</SelectItem>
                                        <SelectItem value="ja">{t('localeJa')}</SelectItem>
                                    </SelectContent>
                                </Select>
                            </div>
                        </div>

                        <div className="grid gap-1.5">
                            <Label htmlFor="template-title">{t('title')}</Label>
                            <Input id="template-title" value={draft.title} maxLength={512}
                                onChange={(e) => patch({ title: e.target.value })} />
                        </div>

                        <div className="grid gap-1.5">
                            <Label htmlFor="template-intro">{t('intro')}</Label>
                            <Textarea id="template-intro" value={draft.intro} rows={2}
                                onChange={(e) => patch({ intro: e.target.value })} />
                        </div>

                        <div className="grid gap-1.5">
                            <Label htmlFor="template-terms">{t('terms')}</Label>
                            <Textarea id="template-terms" value={draft.terms} rows={4}
                                onChange={(e) => patch({ terms: e.target.value })} />
                        </div>

                        <div className="grid gap-1.5">
                            <Label htmlFor="template-footer">{t('footer')}</Label>
                            <Textarea id="template-footer" value={draft.footer} rows={2}
                                onChange={(e) => patch({ footer: e.target.value })} />
                        </div>

                        <div className="rounded-lg border border-border bg-muted/40 px-3 py-2.5">
                            <div className="mb-1.5 text-xs font-medium text-muted-foreground">{t('tokensHint')}</div>
                            <div className="flex flex-wrap gap-1.5">
                                {TOKENS.map((token) => (
                                    <code key={token} className="rounded bg-background px-1.5 py-0.5 text-xs text-muted-foreground">
                                        {token}
                                    </code>
                                ))}
                            </div>
                        </div>

                        <label className="flex items-center gap-2 text-sm text-muted-foreground">
                            <input type="checkbox" checked={draft.active}
                                onChange={(e) => patch({ active: e.target.checked })} />
                            {t('active')}
                        </label>

                        <DialogFooter className="mt-2">
                            <DialogClose asChild>
                                <Button type="button" variant="outline" disabled={saving}>{t('cancel')}</Button>
                            </DialogClose>
                            <Button type="submit" variant="brand" disabled={saving || succeeded} className="min-w-24">
                                {saving ? <Loader2Icon className="size-4 animate-spin" /> : t('save')}
                            </Button>
                        </DialogFooter>
                    </form>
                </div>
            </DialogContent>
        </Dialog>
    );
}
