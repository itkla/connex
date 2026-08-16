'use client';

import { useState } from 'react';
import Link from 'next/link';
import { useRouter } from 'next/navigation';
import { useTranslations } from 'next-intl';

import SectionHeader from '@/app/components/dashboard/SectionHeader';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import {
    Combobox,
    ComboboxContent,
    ComboboxEmpty,
    ComboboxInput,
    ComboboxItem,
    ComboboxList,
} from '@/components/ui/combobox';
import {
    ResponsiveDialog,
    ResponsiveDialogClose,
    ResponsiveDialogContent,
    ResponsiveDialogDescription,
    ResponsiveDialogFooter,
    ResponsiveDialogHeader,
    ResponsiveDialogTitle,
} from '@/components/ui/responsive-dialog';
import {
    Select,
    SelectContent,
    SelectItem,
    SelectTrigger,
    SelectValue,
} from '@/components/ui/select';
import { updateContactProvenance } from '@/app/lib/api';
import { toastError, toastSuccess } from '@/app/lib/toast';
import { LEAD_SOURCES, REFERRER_SOURCES } from '@/app/lib/contactProvenance';
import { useContactTargetSearch } from '@/app/hooks/useRecordTargetSearch';
import { filterByNameQuery } from '@/app/lib/filterByNameQuery';
import type { Contact, ContactLeadSource } from '@/app/lib/types';
import { cn } from '@/lib/utils';

type Props = {
    contactId: number;
    leadSource: ContactLeadSource | null;
    leadSourceDetail: string | null;
    /** The stored referrer id, authoritative even when the referrer record cannot be resolved. */
    referrerPersonId: number | null;
    /** The resolved referrer record, when it is still visible; used only for its label and link. */
    referrer: Contact | null;
    /** Whether the viewer may correct the provenance; without it the panel is read-only. */
    canEdit: boolean;
    className?: string;
};

const NO_SOURCE_VALUE = '__none__';

/**
 * The contact's source provenance — how the relationship originally entered Connex (#559).
 *
 * <p>Provenance, like the lead lifecycle, is the owning workspace's own record, so this panel only
 * appears on owned contacts. An uncaptured source is stated plainly rather than guessed, and a
 * correction replaces the whole triple so a wrong referrer cannot linger behind a fixed source; the
 * previous values stay in the audit log.
 */
export default function ContactProvenancePanel({
    contactId,
    leadSource,
    leadSourceDetail,
    referrerPersonId,
    referrer,
    canEdit,
    className,
}: Props) {
    const t = useTranslations('ContactProvenance');
    const router = useRouter();

    const [open, setOpen] = useState(false);
    const [saving, setSaving] = useState(false);
    const [source, setSource] = useState<string>(NO_SOURCE_VALUE);
    const [detail, setDetail] = useState('');
    const [referrerId, setReferrerId] = useState<number | null>(null);
    const referrerSearch = useContactTargetSearch(
        open,
        [referrerId ?? referrerPersonId],
        referrer ? [referrer] : [],
    );
    const [referrerQuery, setReferrerQuery] = useState('');

    const selectedSource = source === NO_SOURCE_VALUE ? null : (source as ContactLeadSource);
    const supportsReferrer = selectedSource !== null
        && REFERRER_SOURCES.includes(selectedSource);
    const referrerOptions = filterByNameQuery(
        referrerSearch.contacts.filter((contact) => contact.id !== contactId),
        referrerQuery,
    );
    const selectedReferrer = referrerId === null
        ? null
        : referrerSearch.contacts.find((contact) => contact.id === referrerId) ?? null;

    const openDialog = (next: boolean) => {
        if (!next && saving) return;
        if (next) {
            setSource(leadSource ?? NO_SOURCE_VALUE);
            setDetail(leadSourceDetail ?? '');
            setReferrerId(referrerPersonId);
            setReferrerQuery('');
        }
        setOpen(next);
    };

    const submit = async () => {
        if (saving) return;
        setSaving(true);
        try {
            await updateContactProvenance(contactId, {
                leadSource: selectedSource,
                leadSourceDetail: selectedSource === null ? null : detail.trim() || null,
                referrerPersonId: supportsReferrer ? referrerId : null,
            });
            toastSuccess(t('toastSaved'));
            setOpen(false);
            router.refresh();
        } catch (err) {
            toastError(err instanceof Error ? err.message : t('toastFailed'));
        } finally {
            setSaving(false);
        }
    };

    return (
        <div className={cn('mt-6', className)}>
            <SectionHeader title={t('title')} />
            <div className="overflow-hidden rounded-2xl border border-border bg-card">
                <div className="flex items-start justify-between gap-4 px-6 py-4 xl:px-4">
                    <div className="min-w-0">
                        {leadSource === null ? (
                            <p className="text-sm text-muted-foreground">{t('notCaptured')}</p>
                        ) : (
                            <>
                                <p className="text-sm font-medium text-foreground">
                                    {t(`source.${leadSource}`)}
                                </p>
                                {leadSourceDetail ? (
                                    <p className="mt-0.5 text-xs text-muted-foreground">{leadSourceDetail}</p>
                                ) : null}
                                {referrerPersonId !== null ? (
                                    <p className="mt-1 text-xs text-muted-foreground">
                                        {t('referredBy')}{' '}
                                        <Link
                                            href={`/records/contacts/${referrerPersonId}`}
                                            className="font-medium text-foreground underline-offset-2 hover:underline"
                                        >
                                            {referrer?.name ?? t('referrerUnavailable')}
                                        </Link>
                                    </p>
                                ) : null}
                            </>
                        )}
                    </div>
                    {canEdit ? (
                        <Button
                            variant="outline"
                            size="sm"
                            className="shrink-0"
                            onClick={() => openDialog(true)}
                        >
                            {leadSource === null ? t('captureAction') : t('correctAction')}
                        </Button>
                    ) : null}
                </div>
            </div>

            <ResponsiveDialog open={open} onOpenChange={openDialog}>
                <ResponsiveDialogContent className="sm:max-w-md">
                    <ResponsiveDialogHeader>
                        <ResponsiveDialogTitle>{t('dialogTitle')}</ResponsiveDialogTitle>
                        <ResponsiveDialogDescription>{t('dialogDescription')}</ResponsiveDialogDescription>
                    </ResponsiveDialogHeader>

                    <div className="flex flex-col gap-4 px-6 pb-2 sm:px-0">
                        <div className="flex flex-col gap-2">
                            <Label htmlFor="contact-provenance-source">{t('sourceLabel')}</Label>
                            <Select value={source} onValueChange={setSource} disabled={saving}>
                                <SelectTrigger id="contact-provenance-source">
                                    <SelectValue />
                                </SelectTrigger>
                                <SelectContent>
                                    <SelectItem value={NO_SOURCE_VALUE}>{t('source.none')}</SelectItem>
                                    {LEAD_SOURCES.map((value) => (
                                        <SelectItem key={value} value={value}>
                                            {t(`source.${value}`)}
                                        </SelectItem>
                                    ))}
                                </SelectContent>
                            </Select>
                        </div>

                        {selectedSource !== null ? (
                            <div className="flex flex-col gap-2">
                                <Label htmlFor="contact-provenance-detail">{t('detailLabel')}</Label>
                                <Input
                                    id="contact-provenance-detail"
                                    value={detail}
                                    maxLength={255}
                                    disabled={saving}
                                    onChange={(event) => setDetail(event.target.value)}
                                    placeholder={t('detailPlaceholder')}
                                />
                            </div>
                        ) : null}

                        {supportsReferrer ? (
                            <div className="flex flex-col gap-2">
                                <Label htmlFor="contact-provenance-referrer">{t('referrerLabel')}</Label>
                                <Combobox
                                    items={referrerOptions}
                                    filter={null}
                                    itemToStringLabel={(contact: Contact) => contact.name}
                                    value={selectedReferrer}
                                    onInputValueChange={(value) => {
                                        setReferrerQuery(value);
                                        referrerSearch.onInputValueChange(value);
                                    }}
                                    onValueChange={(contact) => setReferrerId(contact?.id ?? null)}
                                >
                                    <ComboboxInput
                                        id="contact-provenance-referrer"
                                        placeholder={t('referrerPlaceholder')}
                                        disabled={saving}
                                    />
                                    <ComboboxContent>
                                        <ComboboxEmpty>{t('referrerEmpty')}</ComboboxEmpty>
                                        <ComboboxList>
                                            {(contact: Contact) => (
                                                <ComboboxItem key={contact.id} value={contact}>
                                                    {contact.name}
                                                </ComboboxItem>
                                            )}
                                        </ComboboxList>
                                    </ComboboxContent>
                                </Combobox>
                            </div>
                        ) : null}
                    </div>

                    <ResponsiveDialogFooter>
                        <ResponsiveDialogClose asChild>
                            <Button type="button" variant="outline" disabled={saving}>
                                {t('cancel')}
                            </Button>
                        </ResponsiveDialogClose>
                        <Button onClick={() => void submit()} disabled={saving}>
                            {t('save')}
                        </Button>
                    </ResponsiveDialogFooter>
                </ResponsiveDialogContent>
            </ResponsiveDialog>
        </div>
    );
}
