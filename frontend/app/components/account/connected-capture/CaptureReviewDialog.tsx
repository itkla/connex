'use client';

import { useState } from 'react';
import {
    ArrowLeftIcon,
    CheckCircleIcon,
    LinkIcon,
    NoSymbolIcon,
    UserPlusIcon,
} from '@heroicons/react/24/outline';
import { useLocale, useTranslations } from 'next-intl';

import DuplicatePreflightWarning from '@/app/components/records/DuplicatePreflightWarning';
import type {
    CaptureReviewDecision,
    CaptureReviewItem,
    CreateContactPayload,
    DuplicatePreflightResponse,
    PersonDuplicatePreflightRequest,
} from '@/app/lib/types';
import { formatDateTime } from '@/app/lib/utils';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Checkbox } from '@/components/ui/checkbox';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';

type PreflightState = {
    status: 'idle' | 'checking' | 'ready' | 'error';
    response: DuplicatePreflightResponse | null;
    acknowledged: boolean;
};

const INITIAL_PREFLIGHT: PreflightState = {
    status: 'idle',
    response: null,
    acknowledged: false,
};

/**
 * Resolves one held capture item through approval, canonical attachment, guarded creation, or ignore.
 */
export default function CaptureReviewDialog({
    review,
    busy,
    canCreatePeople,
    onBack,
    onDecide,
    onApprove,
    onPreflight,
}: {
    review: CaptureReviewItem;
    busy: boolean;
    canCreatePeople: boolean;
    onBack: () => void;
    onDecide: (decision: CaptureReviewDecision) => Promise<boolean>;
    onApprove: () => Promise<boolean>;
    onPreflight: (request: PersonDuplicatePreflightRequest) => Promise<DuplicatePreflightResponse>;
}) {
    const t = useTranslations('AccountCaptureReviews');
    const tConnections = useTranslations('AccountConnections');
    const locale = useLocale();
    const [rememberExact, setRememberExact] = useState(false);
    const [contact, setContact] = useState<CreateContactPayload>({
        name: review.displayName ?? '',
        email: review.email ?? '',
        phone: '',
        title: '',
    });
    const [preflight, setPreflight] = useState<PreflightState>(INITIAL_PREFLIGHT);

    const resolve = async (decision: CaptureReviewDecision) => {
        if (await onDecide(decision)) onBack();
    };

    const create = async () => {
        if (preflight.status === 'ready' && preflight.response) {
            const hasCandidates = preflight.response.candidates.length > 0 || preflight.response.truncated;
            if (hasCandidates && !preflight.acknowledged) return;
            await resolve({
                action: 'create',
                contact,
                duplicateReviewToken: preflight.response.reviewToken,
                rememberExact,
                version: review.version,
            });
            return;
        }

        setPreflight({ status: 'checking', response: null, acknowledged: false });
        try {
            const response = await onPreflight({
                name: contact.name || null,
                emails: contact.email ? [contact.email] : [],
                phones: contact.phone ? [contact.phone] : [],
            });
            const hasCandidates = response.candidates.length > 0 || response.truncated;
            setPreflight({ status: 'ready', response, acknowledged: !hasCandidates });
            if (!hasCandidates) {
                await resolve({
                    action: 'create',
                    contact,
                    duplicateReviewToken: response.reviewToken,
                    rememberExact,
                    version: review.version,
                });
            }
        } catch {
            setPreflight({ status: 'error', response: null, acknowledged: false });
        }
    };

    return (
        <div className="grid gap-5">
            <div>
                <Button type="button" variant="ghost" size="sm" onClick={onBack}>
                    <ArrowLeftIcon className="size-4" />
                    {t('back')}
                </Button>
            </div>

            <div className="grid gap-2">
                <div className="flex flex-wrap items-center gap-2">
                    <Badge variant="secondary">
                        {tConnections(`provider_${review.provider}`)}
                    </Badge>
                    <Badge variant="outline">{t(`stream.${review.stream}`)}</Badge>
                </div>
                <h3 className="text-base font-semibold text-foreground">
                    {review.subject || review.displayName || review.email || t('untitled')}
                </h3>
                <dl className="grid gap-1 text-xs text-muted-foreground">
                    <div className="flex flex-wrap gap-1">
                        <dt>{t('occurredAt')}</dt>
                        <dd>{formatDateTime(review.occurredAt, locale)}</dd>
                    </div>
                    <div className="flex min-w-0 flex-wrap gap-1">
                        <dt>{t('sourceId')}</dt>
                        <dd className="break-all font-mono">{review.interactionId}</dd>
                    </div>
                    <div className="flex flex-wrap gap-1">
                        <dt>{t('heldReasonLabel')}</dt>
                        <dd>{t(`heldReason.${review.heldReason}`)}</dd>
                    </div>
                </dl>
            </div>

            {review.heldReason === 'approval_required' ? (
                <section className="grid gap-3 rounded-lg border border-border p-3">
                    <div>
                        <h4 className="text-sm font-medium text-foreground">{t('approvalTitle')}</h4>
                        <p className="mt-1 text-xs text-muted-foreground">{t('approvalDescription')}</p>
                    </div>
                    <Button
                        type="button"
                        disabled={busy}
                        onClick={async () => {
                            if (await onApprove()) onBack();
                        }}
                    >
                        <CheckCircleIcon className="size-4" />
                        {t('approve')}
                    </Button>
                </section>
            ) : null}

            {review.allowedActions.includes('attach') && review.candidates.length > 0 ? (
                <section className="grid gap-2">
                    <div>
                        <h4 className="text-sm font-medium text-foreground">{t('attachTitle')}</h4>
                        <p className="mt-1 text-xs text-muted-foreground">{t('attachDescription')}</p>
                    </div>
                    <ul className="grid gap-2" aria-label={t('candidateLabel')}>
                        {review.candidates.map((candidate) => (
                            <li
                                key={candidate.personId}
                                className="flex flex-col gap-2 rounded-lg border border-border p-3 sm:flex-row sm:items-center"
                            >
                                <div className="min-w-0 flex-1">
                                    <p className="truncate text-sm font-medium text-foreground">
                                        {candidate.name}
                                    </p>
                                </div>
                                <Button
                                    type="button"
                                    size="sm"
                                    variant="outline"
                                    disabled={busy}
                                    onClick={() => resolve({
                                        action: 'attach',
                                        personId: candidate.personId,
                                        rememberExact,
                                        version: review.version,
                                    })}
                                >
                                    <LinkIcon className="size-4" />
                                    {t('attach')}
                                </Button>
                            </li>
                        ))}
                    </ul>
                </section>
            ) : null}

            {review.allowedActions.includes('create') && canCreatePeople ? (
                <section className="grid gap-3 rounded-lg border border-border p-3">
                    <div>
                        <h4 className="text-sm font-medium text-foreground">{t('createTitle')}</h4>
                        <p className="mt-1 text-xs text-muted-foreground">{t('createDescription')}</p>
                    </div>
                    <div className="grid gap-3 sm:grid-cols-2">
                        <div className="grid gap-1.5">
                            <Label htmlFor={`capture-review-${review.id}-name`}>{t('field.name')}</Label>
                            <Input
                                id={`capture-review-${review.id}-name`}
                                value={contact.name}
                                disabled={busy}
                                onChange={(event) => {
                                    setContact((current) => ({ ...current, name: event.target.value }));
                                    setPreflight(INITIAL_PREFLIGHT);
                                }}
                            />
                        </div>
                        <div className="grid gap-1.5">
                            <Label htmlFor={`capture-review-${review.id}-title`}>{t('field.title')}</Label>
                            <Input
                                id={`capture-review-${review.id}-title`}
                                value={contact.title}
                                disabled={busy}
                                onChange={(event) => {
                                    setContact((current) => ({ ...current, title: event.target.value }));
                                    setPreflight(INITIAL_PREFLIGHT);
                                }}
                            />
                        </div>
                        <div className="grid gap-1.5">
                            <Label htmlFor={`capture-review-${review.id}-email`}>{t('field.email')}</Label>
                            <Input
                                id={`capture-review-${review.id}-email`}
                                type="email"
                                value={contact.email}
                                disabled={busy}
                                onChange={(event) => {
                                    setContact((current) => ({ ...current, email: event.target.value }));
                                    setPreflight(INITIAL_PREFLIGHT);
                                }}
                            />
                        </div>
                        <div className="grid gap-1.5">
                            <Label htmlFor={`capture-review-${review.id}-phone`}>{t('field.phone')}</Label>
                            <Input
                                id={`capture-review-${review.id}-phone`}
                                type="tel"
                                value={contact.phone}
                                disabled={busy}
                                onChange={(event) => {
                                    setContact((current) => ({ ...current, phone: event.target.value }));
                                    setPreflight(INITIAL_PREFLIGHT);
                                }}
                            />
                        </div>
                    </div>
                    <DuplicatePreflightWarning
                        id={`capture-review-${review.id}-preflight`}
                        kind="person"
                        status={preflight.status}
                        response={preflight.response}
                        acknowledged={preflight.acknowledged}
                        onAcknowledgedChange={(acknowledged) => setPreflight((current) => ({
                            ...current,
                            acknowledged,
                        }))}
                        onRetry={create}
                    />
                    <Button
                        type="button"
                        variant="outline"
                        disabled={busy || !contact.name.trim() || preflight.response?.truncated === true}
                        onClick={create}
                    >
                        <UserPlusIcon className="size-4" />
                        {preflight.status === 'checking' ? t('checking') : t('create')}
                    </Button>
                </section>
            ) : null}

            {review.email ? (
                <Label className="items-start leading-relaxed">
                    <Checkbox
                        checked={rememberExact}
                        disabled={busy}
                        onCheckedChange={(checked) => setRememberExact(checked === true)}
                    />
                    <span>{t('rememberExact')}</span>
                </Label>
            ) : null}

            {review.allowedActions.includes('ignore') ? (
                <Button
                    type="button"
                    variant="outline"
                    disabled={busy}
                    onClick={() => resolve({
                        action: 'ignore',
                        rememberExact,
                        version: review.version,
                    })}
                >
                    <NoSymbolIcon className="size-4" />
                    {t('ignore')}
                </Button>
            ) : null}
        </div>
    );
}
