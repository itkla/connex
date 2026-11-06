'use client';

import { useState } from 'react';
import { useTranslations } from 'next-intl';
import SectionHeader from '@/app/components/dashboard/SectionHeader';
import { usePermissionCheck } from '@/app/hooks/usePermissions';
import { useWorkspace } from '@/app/hooks/useWorkspace';
import type { Contact, ContactMarketingStatus } from '@/app/lib/types';
import { Button } from '@/components/ui/button';
import { Skeleton } from '@/components/ui/skeleton';
import { Label } from '@/components/ui/label';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import RestrictionsDialog from './RestrictionsDialog';
import {
    contactSuppressions, MARKETING_CHANNELS, marketingAddress, type MarketingChannel,
} from './contactMarketingPreferences';
import { useContactMarketingPreferences, type MarketingPreferenceAction } from './useContactMarketingPreferences';

/** Contact-owned preferences, separate from privacy holds and from any guarantee of delivery. */
export default function ContactMarketingPreferences({ contact, status }: { contact: Contact; status: ContactMarketingStatus | null }) {
    const t = useTranslations('ContactMarketingPreferences');
    const { activeWorkspaceId } = useWorkspace();
    const consentPermission = usePermissionCheck('CONSENT_MANAGE');
    const restrictionPermission = usePermissionCheck('PERSON_UPDATE');
    const [privacyOpen, setPrivacyOpen] = useState(false);
    const [channel, setChannel] = useState<MarketingChannel>('email');
    if (activeWorkspaceId == null || contact.workspaceId !== activeWorkspaceId) return null;
    const snapshot = JSON.stringify([activeWorkspaceId, contact.id, contact.updatedAt, contact.email, contact.phone, contact.archivedAt, contact.suspendedAt, contact.provisionCeasedAt, status]);

    return (
        <section aria-label={t('title')} className="grid gap-3">
            <SectionHeader title={t('title')} />
            <p className="text-sm text-muted-foreground">{t('policy')}</p>
            <p className="text-xs text-muted-foreground">{t('dispatch')}</p>
            {status?.privacyHold ? <p className="text-sm font-medium">{t('privacyBlocked')}</p> : null}
            {consentPermission === 'granted' ? (
                <PreferenceControls key={snapshot} contact={contact} workspaceId={activeWorkspaceId} channel={channel} onChannelChange={setChannel} />
            ) : (
                <p className="text-sm text-muted-foreground">{t(consentPermission === 'unavailable' ? 'permissionUnavailable' : 'permissionDenied')}</p>
            )}
            {restrictionPermission === 'granted' ? (
                <>
                    <Button variant="outline" size="toolbar" className="justify-self-start" onClick={() => setPrivacyOpen(true)}>{t('privacyAction')}</Button>
                    <RestrictionsDialog key={JSON.stringify([contact.id, contact.suspendedAt, contact.provisionCeasedAt])} contact={contact} open={privacyOpen} onOpenChange={setPrivacyOpen} />
                </>
            ) : null}
        </section>
    );
}

function PreferenceControls({ contact, workspaceId, channel, onChannelChange }: {
    contact: Contact;
    workspaceId: number;
    channel: MarketingChannel;
    onChannelChange: (channel: MarketingChannel) => void;
}) {
    const t = useTranslations('ContactMarketingPreferences');
    const { data, error, loading, busy, apply, retry } = useContactMarketingPreferences(contact.id, workspaceId);
    const [action, setAction] = useState<MarketingPreferenceAction | null>(null);
    if (loading) return <div className="grid gap-3" aria-label={t('loading')}><Skeleton className="h-9 w-full" /><Skeleton className="h-24 w-full" /></div>;
    if (error !== null || data === null) return (
        <div role="alert" className="grid gap-2">
            <p className="text-sm">{t(error === 'denied' ? 'permissionDenied' : 'loadFailed')}</p>
            <Button variant="outline" size="toolbar" onClick={() => { setAction(null); retry(); }} className="justify-self-start">{t('retry')}</Button>
        </div>
    );

    const consent = data.consent.find((entry) => entry.channel === channel && entry.purpose === 'marketing');
    const summary = data.status.channels.find((entry) => entry.channel === channel);
    const entries = contactSuppressions(contact, channel, data.suppressions);
    const address = marketingAddress(contact, channel);
    const blocked = data.status.privacyHold || summary?.optedOut || summary?.doNotContact || summary?.consentRevoked;
    const basis = contact.archivedAt ? 'archivedBlocked' : !summary ? 'statusUnavailable' : blocked ? 'blocked' : !summary.addressable ? 'noAddress' : 'basis';
    const channelId = `marketing-channel-${contact.id}`;
    const reason = (value: string) => {
        switch (value) {
            case 'do_not_contact': return t('reasons.doNotContact');
            case 'unsubscribe': return t('reasons.unsubscribe');
            case 'hard_bounce': return t('reasons.hardBounce');
            case 'complaint': return t('reasons.complaint');
            default: return t('reasons.other');
        }
    };
    const confirm = async () => {
        if (action && await apply(action)) setAction(null);
    };

    return (
        <div className="grid gap-3" aria-busy={busy}>
            <Label htmlFor={channelId}>{t('channel')}</Label>
            <Select value={channel} disabled={busy} onValueChange={(value) => {
                const selected = MARKETING_CHANNELS.find((candidate) => candidate === value);
                if (selected) onChannelChange(selected);
            }}>
                <SelectTrigger id={channelId} className="w-full"><SelectValue /></SelectTrigger>
                <SelectContent>{MARKETING_CHANNELS.map((value) => <SelectItem key={value} value={value}>{t(`channels.${value}`)}</SelectItem>)}</SelectContent>
            </Select>
            {channel === 'line' || channel === 'whatsapp' ? <p className="text-xs text-muted-foreground">{t('notDeliverable')}</p> : null}
            <p role="status" className="text-sm font-medium">{t(basis)}</p>
            <div className="grid gap-2 border-t border-border pt-3">
                <p className="text-sm">{t('recordedConsent', { status: t(`statuses.${consent?.status ?? 'unknown'}`) })}</p>
                {consent?.source ? <p className="break-words text-xs text-muted-foreground">{t('source', { source: consent.source === 'manual' ? t('manualSource') : consent.source })}</p> : null}
                {consent?.evidenceRef ? <p className="break-words text-xs text-muted-foreground">{t('evidence', { evidence: consent.evidenceRef })}</p> : null}
                <div className="flex flex-wrap gap-2">
                    {(['granted', 'revoked', 'unknown'] as const).map((value) => (
                        <Button key={value} variant="outline" size="toolbar" disabled={busy || value === (consent?.status ?? 'unknown')}
                            onClick={() => setAction({ kind: 'consent', channel, status: value })}>{t(`actions.${value}`)}</Button>
                    ))}
                </div>
            </div>
            <div className="grid gap-2 border-t border-border pt-3">
                <p className="text-sm font-medium">{t('restrictions')}</p>
                {entries.length === 0 ? <p className="text-xs text-muted-foreground">{t('noRestrictions')}</p> : (
                    <ul className="grid gap-3">
                        {entries.map((entry) => (
                            <li key={entry.id} className="grid gap-1">
                                <span className="text-sm">{reason(entry.reason)}</span>
                                <span className="break-all text-xs text-muted-foreground">{entry.address}</span>
                                <Button variant="outline" size="toolbar" className="justify-self-start" disabled={busy}
                                    onClick={() => setAction({ kind: 'remove', entry })}>{t('remove')}</Button>
                            </li>
                        ))}
                    </ul>
                )}
                <Button variant="outline" size="toolbar" className="justify-self-start" disabled={busy || !address || summary?.doNotContact === true}
                    onClick={() => { if (address) setAction({ kind: 'restrict', channel, address }); }}>{t('restrict')}</Button>
            </div>
            <Dialog open={action !== null} onOpenChange={(open) => { if (!open && !busy) setAction(null); }}>
                <DialogContent>
                    <DialogHeader>
                        <DialogTitle>{action?.kind === 'consent' ? t(`actions.${action.status}`) : t(action?.kind === 'remove' ? 'remove' : 'restrict')}</DialogTitle>
                        <DialogDescription>{t(action?.kind === 'remove' ? 'removeWarning' : action?.kind === 'consent' ? 'consentWarning' : 'restrictWarning', { name: contact.name })}</DialogDescription>
                    </DialogHeader>
                    {action?.kind === 'consent' && consent?.status === 'revoked' && action.status !== 'revoked' ? (
                        <p role="alert" className="text-sm text-destructive">{t('clearOptOutWarning')}</p>
                    ) : null}
                    <p className="break-all text-sm">{action?.kind === 'remove' ? action.entry.address : t(`channels.${channel}`)}</p>
                    <DialogFooter>
                        <Button variant="outline" disabled={busy} onClick={() => setAction(null)}>{t('cancel')}</Button>
                        <Button variant={action?.kind === 'remove' ? 'destructive' : 'brand'} disabled={busy} onClick={() => void confirm()}>{t(busy ? 'saving' : 'confirm')}</Button>
                    </DialogFooter>
                </DialogContent>
            </Dialog>
        </div>
    );
}
