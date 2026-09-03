'use client';

import { useTranslations } from 'next-intl';

import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Switch } from '@/components/ui/switch';

/** Editable values for one workspace disqualification reason. */
export type DisqualificationReasonDraft = {
    workspaceId: number;
    id: number | null;
    code: string;
    label: string;
    requiresNote: boolean;
    position: number;
    builtIn: boolean;
};

type DisqualificationReasonEditorProps = {
    draft: DisqualificationReasonDraft;
    saving: boolean;
    codeValid: boolean;
    labelValid: boolean;
    onChange: (draft: DisqualificationReasonDraft) => void;
    onCancel: () => void;
    onSave: () => void;
};

/** Form for creating or editing one disqualification reason. */
export function DisqualificationReasonEditor({
    draft,
    saving,
    codeValid,
    labelValid,
    onChange,
    onCancel,
    onSave,
}: DisqualificationReasonEditorProps) {
    const t = useTranslations('WorkspaceDisqualification');

    return (
        <div className="rounded-2xl border border-brand/30 bg-brand-light/30 p-5">
            <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
                <div className="space-y-2">
                    <Label htmlFor="reason-code">{t('codeField')}</Label>
                    <Input
                        id="reason-code"
                        value={draft.code}
                        maxLength={32}
                        disabled={draft.id !== null || saving}
                        aria-invalid={!codeValid}
                        aria-describedby={!codeValid ? 'reason-code-error' : undefined}
                        onChange={(event) => onChange({ ...draft, code: event.target.value })}
                        placeholder={t('codePlaceholder')}
                    />
                    {!codeValid ? (
                        <p id="reason-code-error" className="text-xs text-destructive">
                            {t('codeInvalid')}
                        </p>
                    ) : null}
                </div>
                <div className="space-y-2">
                    <Label htmlFor="reason-label">{t('labelField')}</Label>
                    <Input
                        id="reason-label"
                        value={draft.label}
                        maxLength={200}
                        disabled={saving}
                        onChange={(event) => onChange({ ...draft, label: event.target.value })}
                        placeholder={t('labelPlaceholder')}
                    />
                    {draft.builtIn ? (
                        <p className="text-xs text-muted-foreground">{t('builtInLabelHint')}</p>
                    ) : null}
                </div>
                <div className="space-y-2">
                    <Label htmlFor="reason-position">{t('positionField')}</Label>
                    <Input
                        id="reason-position"
                        type="number"
                        min={0}
                        value={draft.position}
                        disabled={saving}
                        onChange={(event) => onChange({
                            ...draft,
                            position: Math.max(0, event.currentTarget.valueAsNumber || 0),
                        })}
                    />
                </div>
            </div>
            <div className="mt-4 flex items-center justify-between gap-4">
                <div className="flex items-center gap-3">
                    <Switch
                        id="reason-requires-note"
                        checked={draft.requiresNote}
                        disabled={saving}
                        onCheckedChange={(checked) => onChange({ ...draft, requiresNote: checked })}
                    />
                    <Label htmlFor="reason-requires-note">{t('requiresNoteField')}</Label>
                </div>
                <div className="flex gap-2">
                    <Button variant="ghost" size="inline" disabled={saving} onClick={onCancel}>
                        {t('cancel')}
                    </Button>
                    <Button
                        size="inline"
                        disabled={saving || !codeValid || !labelValid}
                        onClick={onSave}
                    >
                        {t('save')}
                    </Button>
                </div>
            </div>
        </div>
    );
}
