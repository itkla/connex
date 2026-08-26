'use client';

import { useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react';
import { useTranslations } from 'next-intl';
import { PlusIcon } from '@heroicons/react/24/solid';

import { Button } from '@/components/ui/button';
import ActivityDialog from '@/app/components/activity/activities/ActivityDialog';
import TaskDialog from '@/app/components/activity/tasks/TaskDialog';
import { useApiErrorToast } from '@/app/hooks/useApiErrorToast';
import {
    addDealPerson,
    createActivity,
    createTask,
    getCompanyDeals,
    getCompanyPeople,
    getUsers,
} from '@/app/lib/api';
import { toastWarn } from '@/app/lib/toast';
import type {
    Contact,
    CreateActivityPayload,
    CreateTaskPayload,
    Deal,
    User,
} from '@/app/lib/types';

/**
 * The record a composer was opened from. Its link is pre-filled in the canonical composer and the
 * other side of the link is offered from the same company, exactly as the record surfaces expect.
 */
export type RecordComposerAnchor =
    | { kind: 'person'; person: Contact; companyId: number | null }
    | { kind: 'deal'; deal: Deal };

type RecordComposerProps = {
    anchor: RecordComposerAnchor;
    currentUserId: number;
    /** Omit to render the record surface's own "+" trigger and own the open state internally. */
    open?: boolean;
    onOpenChange?: (open: boolean) => void;
};

type RecordTaskComposerProps = RecordComposerProps & {
    /**
     * Prefills the due date (`YYYY-MM-DD`) when the surface opening the composer already knows when
     * the work has to happen — a follow-up scheduled ahead of a predicted cold date, for instance.
     */
    defaultDueDate?: string;
    /** Prefills the description when the surface opening the composer knows what the task is for. */
    defaultDescription?: string;
};

/** Role recorded on the deal when a contact-anchored composer files its contact onto a deal. */
const PERSON_ANCHOR_LINK_ROLE = 'Contact';

/** Role recorded on the deal when a deal-anchored composer adds a contact it does not yet know. */
const DEAL_ANCHOR_LINK_ROLE = '';

function useOpenState(controlledOpen: boolean | undefined, onOpenChange?: (open: boolean) => void) {
    const controlled = controlledOpen !== undefined;
    const [internalOpen, setInternalOpen] = useState(false);
    const open = controlled ? controlledOpen : internalOpen;
    const setOpen = useCallback(
        (next: boolean) => {
            if (!controlled) setInternalOpen(next);
            onOpenChange?.(next);
        },
        [controlled, onOpenChange],
    );
    return { controlled, open, setOpen };
}

/**
 * Loads the records the composer may link to, scoped to the anchor's company: a contact-anchored
 * composer offers that company's deals, a deal-anchored composer offers that company's people.
 * Loads once per company on first open, and reports a failure so the surface can say so.
 */
function useRecordLinkOptions(
    anchor: RecordComposerAnchor,
    open: boolean,
    onLoadError: (error: unknown) => void,
): { persons: Contact[]; deals: Deal[]; companyId: number | null } {
    const anchorPerson = anchor.kind === 'person' ? anchor.person : null;
    const anchorDeal = anchor.kind === 'deal' ? anchor.deal : null;
    const companyId = anchor.kind === 'person' ? anchor.companyId : anchor.deal.company ?? null;
    const [companyDeals, setCompanyDeals] = useState<Deal[]>([]);
    const [companyPeople, setCompanyPeople] = useState<Contact[]>([]);
    const [loadedCompanyId, setLoadedCompanyId] = useState<number | null>(null);
    const onLoadErrorRef = useRef(onLoadError);

    useLayoutEffect(() => {
        onLoadErrorRef.current = onLoadError;
    });

    const wantsCompanyDeals = anchorPerson !== null;

    useEffect(() => {
        if (!open || companyId == null || loadedCompanyId === companyId) return;
        let active = true;
        const request = wantsCompanyDeals
            ? getCompanyDeals(companyId).then((items) => {
                if (active) setCompanyDeals(items);
            })
            : getCompanyPeople(companyId).then((items) => {
                if (active) setCompanyPeople(items);
            });
        request
            .catch((error: unknown) => {
                if (active) onLoadErrorRef.current(error);
            })
            .finally(() => {
                if (active) setLoadedCompanyId(companyId);
            });
        return () => {
            active = false;
        };
    }, [companyId, loadedCompanyId, open, wantsCompanyDeals]);

    const persons = useMemo(
        () => (anchorPerson ? [anchorPerson] : companyPeople),
        [anchorPerson, companyPeople],
    );
    const deals = useMemo(
        () => (anchorDeal ? [anchorDeal] : companyDeals),
        [anchorDeal, companyDeals],
    );

    return { persons, deals, companyId };
}

/** Loads the assignable teammates the task composer needs, on its first open. */
function useAssignableUsers(open: boolean): User[] {
    const [users, setUsers] = useState<User[]>([]);
    useEffect(() => {
        if (!open || users.length > 0) return;
        let active = true;
        getUsers()
            .then((next) => {
                if (active) setUsers(next);
            })
            .catch(() => undefined);
        return () => {
            active = false;
        };
    }, [open, users.length]);
    return users;
}

function ComposerTrigger({ title, onClick }: { title: string; onClick: () => void }) {
    return (
        <Button
            type="button"
            variant="ghost"
            size="icon-sm"
            title={title}
            onClick={onClick}
            className="cursor-pointer text-muted-foreground hover:text-foreground"
        >
            <PlusIcon className="size-4" />
            <span className="sr-only">{title}</span>
        </Button>
    );
}

/**
 * The canonical task composer opened from a contact or deal record, with that record pre-linked.
 * Choosing the other side of the link also files the contact on the deal, so the task and the
 * relationship stay consistent; a link that fails is reported without losing the created task.
 */
export function RecordTaskComposer({
    anchor,
    currentUserId,
    open: openProp,
    onOpenChange,
    defaultDueDate,
    defaultDescription,
}: RecordTaskComposerProps) {
    const t = useTranslations('ActivityTasksDialog');
    const showApiError = useApiErrorToast('ActivityTasksDialog');
    const { controlled, open, setOpen } = useOpenState(openProp, onOpenChange);
    const onLoadError = useCallback(
        (error: unknown) => showApiError(error, 'toastFailedLoadLinks'),
        [showApiError],
    );
    const { persons, deals, companyId } = useRecordLinkOptions(anchor, open, onLoadError);
    const users = useAssignableUsers(open);
    const linkRole = anchor.kind === 'person' ? PERSON_ANCHOR_LINK_ROLE : DEAL_ANCHOR_LINK_ROLE;

    const linkEmptyMessages = {
        personEmptyMessage: anchor.kind === 'deal' && companyId == null ? t('noCompanyForPeople') : undefined,
        dealEmptyMessage: anchor.kind === 'person' && companyId == null ? t('noCompanyForDeals') : undefined,
    };

    const createRequest = useCallback(
        async (payload: CreateTaskPayload, init?: RequestInit) => {
            const created = await createTask(payload, init);
            if (payload.dealId != null && payload.personId != null) {
                await addDealPerson(payload.dealId, payload.personId, linkRole, init).catch(() => {
                    toastWarn(t('toastLinkFailed'));
                });
            }
            return created;
        },
        [linkRole, t],
    );

    return (
        <>
            {controlled ? null : <ComposerTrigger title={t('triggerAdd')} onClick={() => setOpen(true)} />}
            <TaskDialog
                open={open}
                onOpenChange={setOpen}
                persons={persons}
                deals={deals}
                users={users}
                currentUserId={currentUserId}
                defaultPerson={anchor.kind === 'person' ? anchor.person : null}
                defaultDeal={anchor.kind === 'deal' ? anchor.deal : null}
                defaultDueDate={defaultDueDate}
                defaultDescription={defaultDescription}
                {...linkEmptyMessages}
                createRequest={createRequest}
            />
        </>
    );
}

/**
 * The canonical activity composer opened from a contact or deal record, with that record pre-linked.
 * Choosing the other side of the link also files the contact on the deal, so the activity and the
 * relationship stay consistent; a link that fails is reported without losing the logged activity.
 */
export function RecordActivityComposer({ anchor, currentUserId, open: openProp, onOpenChange }: RecordComposerProps) {
    const t = useTranslations('ActivityCreateDialog');
    const showApiError = useApiErrorToast('ActivityCreateDialog');
    const { controlled, open, setOpen } = useOpenState(openProp, onOpenChange);
    const onLoadError = useCallback(
        (error: unknown) => showApiError(error, 'toastFailedLoadLinks'),
        [showApiError],
    );
    const { persons, deals, companyId } = useRecordLinkOptions(anchor, open, onLoadError);
    const linkRole = anchor.kind === 'person' ? PERSON_ANCHOR_LINK_ROLE : DEAL_ANCHOR_LINK_ROLE;

    const linkEmptyMessages = {
        personEmptyMessage: anchor.kind === 'deal' && companyId == null ? t('noCompanyForPeople') : undefined,
        dealEmptyMessage: anchor.kind === 'person' && companyId == null ? t('noCompanyForDeals') : undefined,
    };

    const createRequest = useCallback(
        async (payload: CreateActivityPayload, init?: RequestInit) => {
            const created = await createActivity(payload, init);
            if (payload.dealId != null && payload.personId != null) {
                await addDealPerson(payload.dealId, payload.personId, linkRole, init).catch(() => {
                    toastWarn(t('toastLinkFailed'));
                });
            }
            return created;
        },
        [linkRole, t],
    );

    return (
        <>
            {controlled ? null : <ComposerTrigger title={t('triggerAdd')} onClick={() => setOpen(true)} />}
            <ActivityDialog
                open={open}
                onOpenChange={setOpen}
                persons={persons}
                deals={deals}
                currentUserId={currentUserId}
                defaultPerson={anchor.kind === 'person' ? anchor.person : null}
                defaultDeal={anchor.kind === 'deal' ? anchor.deal : null}
                {...linkEmptyMessages}
                createRequest={createRequest}
            />
        </>
    );
}
