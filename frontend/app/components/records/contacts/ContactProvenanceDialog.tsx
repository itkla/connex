'use client';

import { useState } from 'react';
import { useRouter } from 'next/navigation';
import { useTranslations } from 'next-intl';

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
import { useApiErrorToast } from '@/app/hooks/useApiErrorToast';
import { toastSuccess } from '@/app/lib/toast';
import { LEAD_SOURCES, REFERRER_SOURCES, asLeadSource } from '@/app/lib/contactProvenance';
import { useContactTargetSearch } from '@/app/hooks/useRecordTargetSearch';
import { filterByNameQuery } from '@/app/lib/filterByNameQuery';
import type { Contact, ContactLeadSource } from '@/app/lib/types';

const NO_SOURCE_VALUE = '__none__';

type Props = {
    contactId: number;
    /** The owning workspace, used to keep shared-in contacts out of the referrer choices. */
    ownerWorkspaceId: number | undefined;
    leadSource: ContactLeadSource | null;
    leadSourceDetail: string | null;
    referrerPersonId: number | null;
    referrer: Contact | null;
    open: boolean;
    onOpenChange: (open: boolean) => void;
};

/**
 * Correcting how a relationship originally entered Connex (issue #559).
 *
 * <p>A correction replaces the whole triple so a wrong referrer cannot linger behind a fixed
 * source; the previous values stay in the audit log.
 */
export default function ContactProvenanceDialog({
    contactId,
    ownerWorkspaceId,
    leadSource,
    leadSourceDetail,
    referrerPersonId,
    referrer,
    open,
    onOpenChange,
}: Props) {
    const t = useTranslations('ContactProvenance');
    const showApiError = useApiErrorToast('ContactProvenance');
    const router = useRouter();

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

    const selectedSource = asLeadSource(source);
    const supportsReferrer = selectedSource !== null
        && REFERRER_SOURCES.includes(selectedSource);
    const referrerOptions = filterByNameQuery(
        referrerSearch.contacts.filter((contact) =>
            contact.id !== contactId
            && (ownerWorkspaceId === undefined || contact.workspaceId === ownerWorkspaceId)),
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
        onOpenChange(next);
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
            onOpenChange(false);
            router.refresh();
        } catch (err) {
            showApiError(err, 'toastFailed');
        } finally {
            setSaving(false);
        }
    };

    return (
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
    );
}
