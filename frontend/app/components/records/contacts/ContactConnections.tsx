'use client';

import { useMemo, useState } from 'react';
import Link from 'next/link';
import { useTranslations } from 'next-intl';
import { ArrowLongRightIcon, PlusIcon, UserPlusIcon, XMarkIcon } from '@heroicons/react/24/outline';

import {
    Combobox,
    ComboboxContent,
    ComboboxEmpty,
    ComboboxInput,
    ComboboxItem,
    ComboboxList,
} from '@/components/ui/combobox';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import { Button } from '@/components/ui/button';
import { cn } from '@/lib/utils';
import { toastError, toastSuccess } from '@/app/lib/toast';
import {
    acceptWarmPath,
    addContactConnection,
    getContactConnections,
    getContactIntroPath,
    removeContactConnection,
} from '@/app/lib/api';
import { useApiErrorToast } from '@/app/hooks/useApiErrorToast';
import { useContactTargetSearch } from '@/app/hooks/useRecordTargetSearch';
import {
    introMention,
    introPathBridge,
    useCanAskForIntro,
} from '@/app/components/records/RelationshipEvidenceActions';
import { INTRODUCTIONS_PATH } from '@/app/components/introductions/introductionLinks';
import type { Contact, IntroPath, PersonConnection } from '@/app/lib/types';

const TYPES = ['knows', 'colleague', 'former_colleague', 'friend'] as const;
const TYPE_KEYS: Record<string, string> = {
    knows: 'typeKnows',
    colleague: 'typeColleague',
    former_colleague: 'typeFormer',
    friend: 'typeFriend',
};

/**
 * Contact relationship graph: the warm-introduction path to reach this contact, the list of their
 * known connections, and a control to add or remove connections. Backed by the person-edge graph.
 */
export default function ContactConnections({
    contactId,
    contactName,
    initialConnections,
    initialIntroPath,
}: {
    contactId: number;
    contactName: string;
    initialConnections: PersonConnection[];
    initialIntroPath: IntroPath;
}) {
    const t = useTranslations('ContactConnections');
    const tIntro = useTranslations('Introductions');
    const showApiError = useApiErrorToast('Introductions');
    const [connections, setConnections] = useState(initialConnections);
    const [introPath, setIntroPath] = useState(initialIntroPath);
    const [selected, setSelected] = useState<Contact | null>(null);
    const [type, setType] = useState<string>('knows');
    const [busy, setBusy] = useState(false);
    const [asking, setAsking] = useState(false);
    const [asked, setAsked] = useState(false);
    const [pickerOpen, setPickerOpen] = useState(false);
    const contactSearch = useContactTargetSearch(pickerOpen, [selected?.id]);
    const canAskForIntro = useCanAskForIntro();
    const bridge = canAskForIntro ? introPathBridge(introPath) : null;

    const candidates = useMemo(
        () => contactSearch.contacts.filter(
            (contact) => contact.id !== contactId
                && !connections.some((connection) => connection.personId === contact.id),
        ),
        [contactSearch.contacts, contactId, connections],
    );

    const typeLabel = (key: string) => (TYPE_KEYS[key] ? t(TYPE_KEYS[key]) : key);

    const refresh = async () => {
        const [conns, path] = await Promise.all([
            getContactConnections(contactId),
            getContactIntroPath(contactId),
        ]);
        setConnections(conns);
        setIntroPath(path);
    };

    const add = async () => {
        if (!selected || busy) return;
        setBusy(true);
        try {
            await addContactConnection(contactId, { targetPersonId: selected.id, type });
            setSelected(null);
            setType('knows');
            await refresh();
        } catch (err) {
            toastError(err instanceof Error ? err.message : t('addFailed'));
        } finally {
            setBusy(false);
        }
    };

    const askForIntro = async () => {
        if (!bridge || asking || asked) return;
        setAsking(true);
        try {
            await acceptWarmPath({
                targetPersonId: contactId,
                bridgePersonId: bridge.personId,
                taskDescription: tIntro('acceptTaskDescription', {
                    bridge: introMention(bridge.personName, bridge.personId),
                    target: introMention(contactName, contactId),
                }),
            });
            setAsked(true);
            toastSuccess(tIntro('acceptToast', { name: contactName }));
        } catch (err: unknown) {
            showApiError(err, 'acceptFailed');
        } finally {
            setAsking(false);
        }
    };

    const remove = async (targetId: number) => {
        if (busy) return;
        setBusy(true);
        try {
            await removeContactConnection(contactId, targetId);
            await refresh();
        } catch (err) {
            toastError(err instanceof Error ? err.message : t('removeFailed'));
        } finally {
            setBusy(false);
        }
    };

    return (
        <div className="overflow-hidden rounded-2xl bg-card ring-1 ring-border">
            <div className="border-b border-border px-6 py-4">
                <div className="flex flex-wrap items-baseline justify-between gap-2">
                    <p className="text-[10px] font-medium uppercase tracking-[0.12em] text-muted-foreground">
                        {t('warmPath')}
                    </p>
                    <Link
                        href={INTRODUCTIONS_PATH}
                        className="text-xs text-brand transition-colors hover:text-brand-hover"
                    >
                        {t('seeAllPaths')}
                    </Link>
                </div>
                {introPath.directlyKnown ? (
                    <p className="mt-1.5 text-sm text-foreground">{t('alreadyKnown', { name: contactName })}</p>
                ) : introPath.reachable ? (
                    <div className="mt-2 flex flex-wrap items-center gap-x-1.5 gap-y-1">
                        {introPath.steps.map((step, i) => (
                            <span key={step.personId} className="flex items-center gap-1.5">
                                {i > 0 ? <ArrowLongRightIcon className="size-3.5 shrink-0 text-muted-foreground" /> : null}
                                <Link
                                    href={`/records/contacts/${step.personId}`}
                                    className={cn(
                                        'text-sm transition-colors hover:text-brand-hover',
                                        i === 0 || i === introPath.steps.length - 1
                                            ? 'font-medium text-foreground'
                                            : 'text-muted-foreground',
                                    )}
                                >
                                    {step.personName}
                                </Link>
                            </span>
                        ))}
                    </div>
                ) : (
                    <p className="mt-1.5 text-sm text-muted-foreground">{t('noPath')}</p>
                )}
                {bridge ? (
                    <Button
                        type="button"
                        size="sm"
                        variant="secondary"
                        className="mt-3"
                        disabled={asking || asked}
                        onClick={() => void askForIntro()}
                        title={t('askIntroVia', { name: bridge.personName })}
                    >
                        <UserPlusIcon className="size-4" />
                        {asked ? t('introAsked') : tIntro('askIntro')}
                    </Button>
                ) : null}
            </div>

            {connections.length > 0 ? (
                <ul className="divide-y divide-border">
                    {connections.map((connection) => (
                        <li key={connection.id} className="flex items-center gap-3 px-6 py-3">
                            <div className="min-w-0 flex-1">
                                <Link
                                    href={`/records/contacts/${connection.personId}`}
                                    className="block truncate text-sm font-medium text-foreground transition-colors hover:text-brand-hover"
                                >
                                    {connection.personName}
                                </Link>
                                <p className="truncate text-xs text-muted-foreground">
                                    {connection.companyName
                                        ? `${typeLabel(connection.type)} · ${connection.companyName}`
                                        : typeLabel(connection.type)}
                                </p>
                            </div>
                            <button
                                type="button"
                                onClick={() => remove(connection.personId)}
                                disabled={busy}
                                aria-label={t('remove')}
                                className="shrink-0 rounded-md p-1 text-muted-foreground transition-colors hover:bg-muted hover:text-foreground disabled:opacity-50"
                            >
                                <XMarkIcon className="size-4" />
                            </button>
                        </li>
                    ))}
                </ul>
            ) : (
                <p className="px-6 py-4 text-sm text-muted-foreground">{t('empty')}</p>
            )}

            <div className="flex items-center gap-2 border-t border-border px-6 py-4">
                <div className="min-w-0 flex-1">
                    <Combobox
                        items={candidates}
                        filter={null}
                        itemToStringLabel={(c: Contact) => c.name}
                        value={selected}
                        onOpenChange={setPickerOpen}
                        onInputValueChange={contactSearch.onInputValueChange}
                        onValueChange={(c) => setSelected((c as Contact | null) ?? null)}
                    >
                        <ComboboxInput
                            placeholder={t('addPlaceholder')}
                            className="rounded-lg border-0 bg-muted shadow-none ring-1 ring-border dark:bg-muted has-[[data-slot=input-group-control]:focus-visible]:ring-2 has-[[data-slot=input-group-control]:focus-visible]:ring-brand"
                        />
                        <ComboboxContent className="pointer-events-auto">
                            <ComboboxList>
                                <ComboboxEmpty>
                                    {contactSearch.loading
                                        ? t('searching')
                                        : contactSearch.error
                                          ? t('searchFailed')
                                          : t('noContacts')}
                                </ComboboxEmpty>
                                {candidates.map((c) => (
                                    <ComboboxItem key={c.id} value={c}>
                                        {c.name}
                                    </ComboboxItem>
                                ))}
                            </ComboboxList>
                        </ComboboxContent>
                    </Combobox>
                </div>
                <Select value={type} onValueChange={setType}>
                    <SelectTrigger className="w-36 shrink-0">
                        <SelectValue />
                    </SelectTrigger>
                    <SelectContent>
                        {TYPES.map((key) => (
                            <SelectItem key={key} value={key}>
                                {typeLabel(key)}
                            </SelectItem>
                        ))}
                    </SelectContent>
                </Select>
                <Button
                    type="button"
                    variant="brand"
                    size="icon"
                    onClick={add}
                    disabled={!selected || busy}
                    aria-label={t('add')}
                    className="shrink-0"
                >
                    <PlusIcon className="size-4" strokeWidth={2.5} />
                </Button>
            </div>
        </div>
    );
}
