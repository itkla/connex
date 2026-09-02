'use client';

import { forwardRef, useCallback, useEffect, useImperativeHandle, useRef, useState } from 'react';
import dynamic from 'next/dynamic';
import { useTranslations } from 'next-intl';
import { useReducedMotion } from 'motion/react';
import { ArrowLeftIcon, XMarkIcon } from '@heroicons/react/24/outline';
import { Loader2Icon } from 'lucide-react';

import MobileCreateMorphingBody from '@/app/components/actions/MobileCreateMorphingBody';
import MobileDealDiscardGuard, {
    type MobileDealDiscardGuardHandle,
} from '@/app/components/actions/MobileDealDiscardGuard';
import QuickCreateTypeSelector from '@/app/components/actions/QuickCreateTypeSelector';
import { DrawerDescription, DrawerHeader, DrawerTitle } from '@/components/ui/drawer';
import { deriveCreateDefaults } from '@/app/lib/actions/createDefaults';
import { getContacts, getDeals, getUsers } from '@/app/lib/api';
import type { DealCreateContainerHandle } from '@/app/components/actions/create/DealCreateContainer';
import type { AppAction, ActionContext } from '@/app/lib/actions/types';
import type { Contact, Deal, User } from '@/app/lib/types';

const TaskDialogForm = dynamic(() =>
    import('@/app/components/activity/tasks/TaskDialog').then((module) => ({ default: module.TaskDialogForm })),
);
const NoteDialogForm = dynamic(() =>
    import('@/app/components/activity/notes/NoteDialog').then((module) => ({ default: module.NoteDialogForm })),
);
const ActivityDialogForm = dynamic(() =>
    import('@/app/components/activity/activities/ActivityDialog').then((module) => ({ default: module.ActivityDialogForm })),
);
const DealCreateContainer = dynamic(() => import('@/app/components/actions/create/DealCreateContainer'));
const ContactCreateContainer = dynamic(() => import('@/app/components/actions/create/ContactCreateContainer'));
const CompanyCreateContainer = dynamic(() => import('@/app/components/actions/create/CompanyCreateContainer'));

type EmbeddedFormKind = 'task' | 'note' | 'activity';
type EmbeddedContainerKind = 'deal' | 'person' | 'company';
type EmbeddedKind = EmbeddedFormKind | EmbeddedContainerKind;
type FlowView = 'selector' | EmbeddedKind;
type FlowRefs = { persons: Contact[]; deals: Deal[]; users: User[] };

const EMBEDDED_BY_ACTION: Record<string, EmbeddedKind> = {
    'create.task': 'task',
    'create.note': 'note',
    'create.activity': 'activity',
    'create.deal': 'deal',
    'create.person': 'person',
    'create.company': 'company',
};

const CONTAINER_KINDS = new Set<EmbeddedKind>(['deal', 'person', 'company']);

type MobileCreateFlowProps = {
    actions: readonly AppAction[];
    context: ActionContext;
    currentUserId: number | null;
    onFallback: (action: AppAction) => void;
    onClose: () => void;
    onPendingChange: (pending: boolean) => void;
};

export type MobileCreateFlowHandle = {
    requestClose: () => void;
};

/** Mobile Quick Create surface that morphs between the selector and embedded create forms. */
const MobileCreateFlow = forwardRef<MobileCreateFlowHandle, MobileCreateFlowProps>(function MobileCreateFlow({
    actions,
    context,
    currentUserId,
    onFallback,
    onClose,
    onPendingChange,
}, ref) {
    const t = useTranslations('Actions');
    const reduceMotion = useReducedMotion() ?? false;
    const [view, setView] = useState<FlowView>('selector');
    const [direction, setDirection] = useState(1);
    const [refs, setRefs] = useState<FlowRefs | null>(null);
    const [submitting, setSubmitting] = useState(false);
    const [dismissLocked, setDismissLocked] = useState(false);
    const submittingRef = useRef(false);
    const dismissLockedRef = useRef(false);
    const dealContainerRef = useRef<DealCreateContainerHandle>(null);
    const discardGuardRef = useRef<MobileDealDiscardGuardHandle>(null);
    const interactionLocked = submitting || dismissLocked;

    const handleSubmittingChange = useCallback((next: boolean) => {
        submittingRef.current = next;
        setSubmitting(next);
        onPendingChange(next || dismissLockedRef.current);
    }, [onPendingChange]);

    const handleDismissLockChange = useCallback((next: boolean) => {
        dismissLockedRef.current = next;
        setDismissLocked(next);
        onPendingChange(submittingRef.current || next);
    }, [onPendingChange]);

    useEffect(() => {
        if (view === 'selector' || CONTAINER_KINDS.has(view) || refs) return;
        let cancelled = false;
        Promise.all([getContacts({}), getDeals(), getUsers()])
            .then(([persons, deals, users]) => {
                if (!cancelled) setRefs({ persons, deals, users });
            })
            .catch(() => {
                if (!cancelled) setRefs({ persons: [], deals: [], users: [] });
            });
        return () => {
            cancelled = true;
        };
    }, [view, refs]);

    const select = useCallback((action: AppAction) => {
        const kind = EMBEDDED_BY_ACTION[action.id];
        if (kind && (CONTAINER_KINDS.has(kind) || currentUserId != null)) {
            setDirection(1);
            setView(kind);
            return;
        }
        onFallback(action);
    }, [currentUserId, onFallback]);

    const showSelector = useCallback(() => {
        setDirection(-1);
        setView('selector');
    }, []);
    const hasUnsavedDealChanges = useCallback(
        () => dealContainerRef.current?.hasUnsavedChanges() ?? false,
        [],
    );
    useImperativeHandle(ref, () => ({
        requestClose: () => {
            if (discardGuardRef.current) discardGuardRef.current.requestClose();
            else onClose();
        },
    }), [onClose]);

    const defaults = view === 'selector' ? undefined : deriveCreateDefaults(context, view);
    const defaultPerson = refs?.persons.find((person) => person.id === defaults?.personId) ?? null;
    const defaultDeal = refs?.deals.find((deal) => deal.id === defaults?.dealId) ?? null;

    return (
        <MobileDealDiscardGuard
            ref={discardGuardRef}
            active={view === 'deal'}
            hasUnsavedChanges={hasUnsavedDealChanges}
            disabled={interactionLocked}
            onBack={showSelector}
            onClose={onClose}
        >
            {({ handleOpenChange, requestBack }) => (
                <div className="flex min-h-0 flex-1 flex-col">
                    <DrawerHeader className="flex-row items-center gap-2 border-b border-border px-4 py-3.5">
                        {view !== 'selector' ? (
                            <button
                                type="button"
                                onClick={requestBack}
                                disabled={interactionLocked}
                                aria-label={t('quickCreate.back')}
                                className="grid size-7 shrink-0 place-items-center rounded-md text-muted-foreground transition-colors hover:bg-muted hover:text-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-brand disabled:opacity-50"
                            >
                                <ArrowLeftIcon className="size-4" />
                            </button>
                        ) : null}
                        {view === 'selector' ? (
                            <DrawerTitle className="flex-1 text-sm font-semibold tracking-tight">
                                {t('quickCreate.title')}
                            </DrawerTitle>
                        ) : (
                            <>
                                <DrawerTitle className="sr-only">{t(`create.${view}`)}</DrawerTitle>
                                <span aria-hidden className="flex-1" />
                            </>
                        )}
                        <DrawerDescription className="sr-only">{t('quickCreate.description')}</DrawerDescription>
                        <button
                            type="button"
                            onClick={() => handleOpenChange(false)}
                            disabled={interactionLocked}
                            aria-label={t('quickCreate.close')}
                            className="grid size-7 shrink-0 place-items-center rounded-md text-muted-foreground transition-colors hover:bg-muted hover:text-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-brand disabled:opacity-50"
                        >
                            <XMarkIcon className="size-4" />
                        </button>
                    </DrawerHeader>

                    <div className="min-h-0 overflow-y-auto">
                        <MobileCreateMorphingBody
                            viewKey={view}
                            direction={direction}
                            reduceMotion={reduceMotion}
                        >
                            {view === 'selector' ? (
                                <div className="px-4 pb-6 pt-4">
                                    <QuickCreateTypeSelector actions={actions} onSelect={select} />
                                </div>
                            ) : view === 'company' ? (
                                <CompanyCreateContainer
                                    embedded
                                    open
                                    onOpenChange={handleOpenChange}
                                    onCancel={requestBack}
                                />
                            ) : view === 'deal' ? (
                                <DealCreateContainer
                                    ref={dealContainerRef}
                                    embedded
                                    open
                                    onOpenChange={handleOpenChange}
                                    onCancel={requestBack}
                                    defaults={defaults}
                                    draftPersistence={false}
                                />
                            ) : view === 'person' ? (
                                <ContactCreateContainer
                                    embedded
                                    open
                                    onOpenChange={handleOpenChange}
                                    onCancel={requestBack}
                                    onDismissLockChange={handleDismissLockChange}
                                    defaults={defaults}
                                />
                            ) : refs === null || currentUserId == null ? (
                                <div className="grid min-h-[28rem] place-items-center">
                                    <Loader2Icon className="size-5 animate-spin text-muted-foreground" />
                                </div>
                            ) : view === 'task' ? (
                                <TaskDialogForm
                                    persons={refs.persons}
                                    deals={refs.deals}
                                    users={refs.users}
                                    currentUserId={currentUserId}
                                    defaultPerson={defaultPerson}
                                    defaultDeal={defaultDeal}
                                    defaultDueDate=""
                                    defaultDescription=""
                                    onSubmittingChange={handleSubmittingChange}
                                    onCancel={requestBack}
                                    onClose={() => handleOpenChange(false)}
                                />
                            ) : view === 'note' ? (
                                <NoteDialogForm
                                    note={null}
                                    persons={refs.persons}
                                    deals={refs.deals}
                                    currentUserId={currentUserId}
                                    defaultPerson={defaultPerson}
                                    defaultDeal={defaultDeal}
                                    defaultContent=""
                                    compact
                                    onSubmittingChange={handleSubmittingChange}
                                    onCancel={requestBack}
                                    onClose={() => handleOpenChange(false)}
                                />
                            ) : (
                                <ActivityDialogForm
                                    persons={refs.persons}
                                    deals={refs.deals}
                                    currentUserId={currentUserId}
                                    defaultPerson={defaultPerson}
                                    defaultDeal={defaultDeal}
                                    onSubmittingChange={handleSubmittingChange}
                                    onCancel={requestBack}
                                    onClose={() => handleOpenChange(false)}
                                />
                            )}
                        </MobileCreateMorphingBody>
                    </div>
                </div>
            )}
        </MobileDealDiscardGuard>
    );
});

export default MobileCreateFlow;
