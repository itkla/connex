'use client';

import {
    createContext,
    useCallback,
    useContext,
    useEffect,
    useState,
    type ReactNode,
} from 'react';
import { useTranslations } from 'next-intl';

import { useApiErrorToast } from '@/app/hooks/useApiErrorToast';
import { usePermissionCheck } from '@/app/hooks/usePermissions';
import { acceptWarmPath, getWarmPaths } from '@/app/lib/api';
import { toastSuccess } from '@/app/lib/toast';
import type { WarmPath } from '@/app/lib/types';

/**
 * How many ranked paths to read. The board's own maximum, so a record never offers an ask for a
 * target the board has already ranked out of its feed.
 */
const WARM_PATH_LIMIT = 50;

/** The bridge a record's introduction would go through. */
export type IntroBridge = {
    personId: number;
    personName: string;
};

/** The single per-page intro-ask state every surface on a contact record shares. */
export type ContactIntroAsk = {
    /** The warmest bridge the Introductions board offers for this contact, or null when it offers none. */
    bridge: IntroBridge | null;
    asking: boolean;
    asked: boolean;
    ask: () => void;
};

const NO_ASK: ContactIntroAsk = {
    bridge: null,
    asking: false,
    asked: false,
    ask: () => undefined,
};

const ContactIntroAskContext = createContext<ContactIntroAsk>(NO_ASK);

/** Composes the mention token an intro follow-up task carries for a contact. */
export function introMention(name: string, id: number): string {
    return `[${name}](person:${id})`;
}

/**
 * Whether the viewer may ask for an introduction: the server requires both updating the contact and
 * creating the follow-up task. A refused permission hides the entry point rather than offering a
 * button that can only fail; an unresolved lookup leaves it, so a failed probe never removes an
 * ability the viewer has.
 */
export function useCanAskForIntro(): boolean {
    const personUpdate = usePermissionCheck('PERSON_UPDATE');
    const taskCreate = usePermissionCheck('TASK_CREATE');
    return personUpdate !== 'denied' && taskCreate !== 'denied';
}

/** The board's warmest bridge to one target, or null when the feed holds no path to it. */
function bridgeToTarget(paths: readonly WarmPath[], targetPersonId: number): IntroBridge | null {
    const path = paths.find((candidate) => candidate.targetId === targetPersonId);
    const bridge = path?.bridges[0];
    if (!bridge) return null;
    const personName = bridge.name.trim();
    return personName.length > 0 ? { personId: bridge.personId, personName } : null;
}

/**
 * Holds one contact record's introduction ask, shared by every surface on the page — the warmth
 * evidence footer and the connections block — so both offer the same ask and both settle together
 * once it is made.
 *
 * The bridge comes from the Introductions board's own ranked feed rather than from the record's
 * raw shortest-path search, so a record inherits the server's dismissal records and bridge-warmth
 * eligibility for free and can never offer an introduction the board has retired.
 */
export function ContactIntroAskProvider({
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

    return (
        <ContactIntroAskContext.Provider value={{ bridge, asking, asked, ask }}>
            {children}
        </ContactIntroAskContext.Provider>
    );
}

/**
 * The contact record's shared intro ask. Outside a {@link ContactIntroAskProvider} it reports no
 * bridge, so a surface reused off the record page simply renders no ask.
 */
export function useContactIntroAsk(): ContactIntroAsk {
    return useContext(ContactIntroAskContext);
}
