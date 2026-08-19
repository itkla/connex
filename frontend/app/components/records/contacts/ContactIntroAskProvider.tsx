'use client';

import { useCallback, useEffect, useMemo, useState, type ReactNode } from 'react';
import { useTranslations } from 'next-intl';

import {
    bridgeToTarget,
    ContactIntroAskContext,
    introMention,
    useCanAskForIntro,
    WARM_PATH_LIMIT,
    type IntroBridge,
} from '@/app/components/records/contacts/introAsk';
import { useApiErrorToast } from '@/app/hooks/useApiErrorToast';
import { acceptWarmPath, getWarmPaths } from '@/app/lib/api';
import { toastSuccess } from '@/app/lib/toast';

/**
 * Holds one contact record's introduction ask, shared by every surface on the page — the warmth
 * evidence footer and the connections block — so both offer the same ask and both settle together
 * once it is made.
 *
 * The bridge comes from the Introductions board's own ranked feed rather than from the record's
 * raw shortest-path search, so a record inherits the server's dismissal records and bridge-warmth
 * eligibility for free and can never offer an introduction the board has retired.
 *
 * The whole ranked feed is read and narrowed here, which is the board's own cost paid once per
 * record view; a viewer who may not make an introduction never pays it. When the endpoint accepts a
 * target, pass it and drop the narrowing. A refused or failed read leaves no bridge, so the record
 * offers no ask rather than one that could only fail.
 */
export default function ContactIntroAskProvider({
    contactId,
    contactName,
    children,
}: {
    contactId: number;
    contactName: string;
    children: ReactNode;
}) {
    const t = useTranslations('Introductions');
    const showApiError = useApiErrorToast('Introductions');
    const canAsk = useCanAskForIntro();
    const [bridge, setBridge] = useState<IntroBridge | null>(null);
    const [asking, setAsking] = useState(false);
    const [asked, setAsked] = useState(false);

    useEffect(() => {
        if (!canAsk) return;
        let active = true;
        getWarmPaths(WARM_PATH_LIMIT)
            .then((paths) => {
                if (active) setBridge(bridgeToTarget(paths, contactId));
            })
            .catch(() => undefined);
        return () => {
            active = false;
        };
    }, [canAsk, contactId]);

    const ask = useCallback(() => {
        if (bridge === null || asking || asked) return;
        setAsking(true);
        acceptWarmPath({
            targetPersonId: contactId,
            bridgePersonId: bridge.personId,
            taskDescription: t('acceptTaskDescription', {
                bridge: introMention(bridge.personName, bridge.personId),
                target: introMention(contactName, contactId),
            }),
        })
            .then(() => {
                setAsked(true);
                toastSuccess(t('acceptToast', { name: contactName }));
            })
            .catch((error: unknown) => showApiError(error, 'acceptFailed'))
            .finally(() => setAsking(false));
    }, [asked, asking, bridge, contactId, contactName, showApiError, t]);

    const value = useMemo(
        () => ({ bridge, asking, asked, ask }),
        [ask, asked, asking, bridge],
    );

    return (
        <ContactIntroAskContext.Provider value={value}>
            {children}
        </ContactIntroAskContext.Provider>
    );
}
