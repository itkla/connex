'use client';

import { useState } from 'react';
import { useRouter } from 'next/navigation';
import { useTranslations } from 'next-intl';

import { SettingsSection } from '@/app/components/settings/SettingsSection';
import { useApiErrorToast } from '@/app/hooks/useApiErrorToast';
import { usePasskeyStepUpErrorHandler } from '@/app/hooks/usePasskeyStepUpError';
import { useWorkspace } from '@/app/hooks/useWorkspace';
import { ApiError, updateWorkspaceDefaultCurrency } from '@/app/lib/api';
import { CURRENCY_CODES, isKnownCurrency, workspaceDefaultCurrency } from '@/app/lib/currencies';
import { toastError, toastSuccess } from '@/app/lib/toast';
import type { Workspace } from '@/app/lib/types';
import { Button } from '@/components/ui/button';
import { Label } from '@/components/ui/label';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';

/** Single-field setting, rendered under the workspace identity panel's settings permission gate. */
export default function WorkspaceCurrencyPanel({ workspace }: { workspace: Workspace }) {
    const t = useTranslations('WorkspaceIdentity');
    const router = useRouter();
    const showApiError = useApiErrorToast('WorkspaceIdentity');
    const handleStepUpError = usePasskeyStepUpErrorHandler();
    const { publishWorkspaceIdentity } = useWorkspace();
    const [currency, setCurrency] = useState(workspaceDefaultCurrency(workspace));
    const [saving, setSaving] = useState(false);
    const [invalid, setInvalid] = useState(false);

    async function save() {
        if (!isKnownCurrency(currency)) {
            setInvalid(true);
            return;
        }
        setSaving(true);
        setInvalid(false);
        try {
            const updated = await updateWorkspaceDefaultCurrency(workspace.id, currency, workspace.identityVersion);
            publishWorkspaceIdentity(updated);
            toastSuccess(t('currencySaved'));
            router.refresh();
        } catch (error) {
            if (error instanceof ApiError && error.fieldErrors?.defaultCurrency) {
                setInvalid(true);
            } else if (error instanceof ApiError && error.status === 409) {
                toastError(t('stale'));
                router.refresh();
            } else if (!handleStepUpError(error)) {
                showApiError(error, 'saveFailed');
            }
        } finally {
            setSaving(false);
        }
    }

    return (
        <SettingsSection title={t('currencyTitle')} description={t('currencyDescription')}>
            <form className="rounded-2xl border border-border bg-card" onSubmit={(event) => {
                event.preventDefault();
                void save();
            }}>
                <div className="grid gap-3 p-6">
                    <Label htmlFor="workspace-default-currency">{t('currencyLabel')}</Label>
                    <Select value={currency} onValueChange={(value) => { setCurrency(value); setInvalid(false); }} disabled={saving}>
                        <SelectTrigger id="workspace-default-currency" className="w-full sm:w-56" aria-invalid={invalid}
                            aria-describedby={invalid ? 'workspace-currency-error' : 'workspace-currency-hint'}>
                            <SelectValue />
                        </SelectTrigger>
                        <SelectContent>
                            {CURRENCY_CODES.map((code) => <SelectItem key={code} value={code}>{code}</SelectItem>)}
                        </SelectContent>
                    </Select>
                    <p id="workspace-currency-hint" className="text-sm text-muted-foreground">
                        {t(workspace.defaultCurrency ? 'currencyEffective' : 'currencyFallback', {
                            currency: workspaceDefaultCurrency(workspace),
                        })}
                    </p>
                    {invalid ? <p id="workspace-currency-error" role="alert" className="text-sm text-destructive">{t('currencyInvalid')}</p> : null}
                </div>
                <div className="flex justify-end border-t border-border bg-muted/30 px-6 py-4">
                    <Button type="submit" variant="brand" disabled={saving || currency === workspace.defaultCurrency}>
                        {saving ? t('saving') : t('currencySave')}
                    </Button>
                </div>
            </form>
        </SettingsSection>
    );
}
