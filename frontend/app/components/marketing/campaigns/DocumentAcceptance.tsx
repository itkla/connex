"use client";

import { useEffect, useRef, useState, type FormEvent } from "react";
import {
    CheckCircleIcon,
    ClockIcon,
    XCircleIcon,
} from "@heroicons/react/24/outline";
import { useLocale, useTranslations } from "next-intl";

import DocumentView from "@/app/components/records/documents/DocumentView";
import {
    acceptDocument,
    declineDocument,
    documentAcceptanceFailureKind,
    markDocumentAcceptanceViewed,
} from "@/app/lib/api";
import {
    documentAcceptanceTokenFromLocation,
    documentAcceptanceViewFailure,
} from "@/app/components/marketing/campaigns/documentAcceptance";
import type {
    DocumentAcceptanceFailureKind,
    DocumentAcceptancePreview,
} from "@/app/lib/types";
import { formatDateTime } from "@/app/lib/utils";
import DocumentAcceptanceUnavailable from "@/app/components/marketing/campaigns/DocumentAcceptanceUnavailable";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Textarea } from "@/components/ui/textarea";

type DecisionMode = "accept" | "decline" | null;
type DecisionReceipt = "accepted" | "declined" | null;
type ViewState = "pending" | "recorded";

/** Renders and records one public recipient review without using a Connex session. */
export default function DocumentAcceptance({
    initialPreview,
}: {
    initialPreview: DocumentAcceptancePreview;
}) {
    const t = useTranslations("DocumentAcceptance");
    const locale = useLocale();
    const viewRequested = useRef(false);
    const receiptRef = useRef<DecisionReceipt>(null);
    const [preview, setPreview] = useState(initialPreview);
    const [viewState, setViewState] = useState<ViewState>("pending");
    const [failure, setFailure] = useState<DocumentAcceptanceFailureKind | null>(null);
    const [mode, setMode] = useState<DecisionMode>(null);
    const [receipt, setReceipt] = useState<DecisionReceipt>(null);
    const [typedName, setTypedName] = useState("");
    const [reason, setReason] = useState("");
    const [fieldError, setFieldError] = useState<string | null>(null);
    const [requestError, setRequestError] = useState(false);
    const [isSubmitting, setIsSubmitting] = useState(false);

    useEffect(() => {
        if (viewRequested.current) return;
        viewRequested.current = true;
        const token = documentAcceptanceTokenFromLocation();
        if (!token) {
            void Promise.resolve().then(() => setFailure("unavailable"));
            return;
        }
        void markDocumentAcceptanceViewed(token)
            .then((viewed) => {
                if (receiptRef.current) return;
                setPreview(viewed);
                setViewState("recorded");
            })
            .catch((error: unknown) => {
                const viewFailure = documentAcceptanceViewFailure(
                    receiptRef.current != null,
                    error,
                );
                if (viewFailure) setFailure(viewFailure);
            });
    }, []);

    if (failure && !receipt) {
        const copy = failure === "unavailable"
            ? { title: t("unavailableTitle"), body: t("unavailableBody"), footer: t("footer") }
            : failure === "throttled"
                ? { title: t("throttledTitle"), body: t("throttledBody"), footer: t("footer") }
                : {
                    title: t("serviceUnavailableTitle"),
                    body: t("serviceUnavailableBody"),
                    footer: t("footer"),
                };
        return <DocumentAcceptanceUnavailable copy={copy} />;
    }

    const openDecision = (nextMode: Exclude<DecisionMode, null>) => {
        setMode(nextMode);
        setFieldError(null);
        setRequestError(false);
    };

    const cancelDecision = () => {
        setMode(null);
        setFieldError(null);
        setRequestError(false);
    };

    const handleDecisionFailure = (error: unknown) => {
        const knownFailure = documentAcceptanceFailureKind(error);
        if (knownFailure) {
            setFailure(knownFailure);
        } else {
            setRequestError(true);
        }
    };

    const submitAcceptance = async (event: FormEvent<HTMLFormElement>) => {
        event.preventDefault();
        const normalizedName = typedName.trim();
        if (!normalizedName) {
            setFieldError(t("typedNameRequired"));
            return;
        }
        if (normalizedName.length > 255) {
            setFieldError(t("typedNameTooLong"));
            return;
        }
        setFieldError(null);
        setRequestError(false);
        setIsSubmitting(true);
        try {
            const token = documentAcceptanceTokenFromLocation();
            if (!token) {
                setFailure("unavailable");
                return;
            }
            await acceptDocument(token, { typedName: normalizedName });
            receiptRef.current = "accepted";
            setReceipt("accepted");
            setMode(null);
        } catch (error: unknown) {
            handleDecisionFailure(error);
        } finally {
            setIsSubmitting(false);
        }
    };

    const submitDecline = async (event: FormEvent<HTMLFormElement>) => {
        event.preventDefault();
        const normalizedReason = reason.trim();
        if (!normalizedReason) {
            setFieldError(t("reasonRequired"));
            return;
        }
        if (normalizedReason.length > 500) {
            setFieldError(t("reasonTooLong"));
            return;
        }
        setFieldError(null);
        setRequestError(false);
        setIsSubmitting(true);
        try {
            const token = documentAcceptanceTokenFromLocation();
            if (!token) {
                setFailure("unavailable");
                return;
            }
            await declineDocument(token, { reason: normalizedReason });
            receiptRef.current = "declined";
            setReceipt("declined");
            setMode(null);
        } catch (error: unknown) {
            handleDecisionFailure(error);
        } finally {
            setIsSubmitting(false);
        }
    };

    const documentTitle = preview.documentTitle
        ?? preview.content.sections.title
        ?? preview.dealName;

    return (
        <main className="min-h-dvh bg-muted/30 px-4 py-5 sm:px-6 sm:py-8 lg:px-8">
            <header className="mx-auto flex max-w-7xl items-center justify-between gap-6 px-1 pb-5 sm:pb-7">
                <span className="text-lg font-semibold tracking-tight text-foreground">Connex</span>
                <span className="min-w-0 truncate text-sm text-muted-foreground" title={documentTitle}>
                    {t("pageLabel")}
                </span>
            </header>

            <div className="mx-auto grid max-w-7xl grid-cols-1 items-start gap-5 lg:grid-cols-3 lg:gap-7">
                <article className="document-paper min-w-0 rounded-2xl border border-border bg-card p-5 shadow-sm sm:p-9 lg:col-span-2 lg:p-12">
                    <DocumentView
                        content={preview.content}
                        type={preview.documentType}
                        title={documentTitle}
                        status="final"
                        version={preview.documentVersion}
                        generatedAt={preview.content.generatedAt}
                    />
                </article>

                <aside className="rounded-2xl border border-border bg-card p-5 shadow-sm lg:sticky lg:top-6">
                    <h2 className="text-base font-semibold text-foreground">{t("detailsTitle")}</h2>
                    <dl className="mt-4 space-y-4 border-b border-border pb-5">
                        <Detail label={t("workspaceLabel")} value={preview.workspaceName} />
                        <Detail label={t("dealLabel")} value={preview.dealName} />
                        <Detail label={t("recipientLabel")} value={preview.recipientEmail} />
                        <Detail
                            label={t("expiresLabel")}
                            value={preview.expiresAt
                                ? formatDateTime(preview.expiresAt, locale)
                                : t("noExpiry")}
                        />
                    </dl>

                    <div className="pt-5">
                        <DecisionPanel
                            actionable={preview.actionable}
                            mode={mode}
                            receipt={receipt}
                            typedName={typedName}
                            reason={reason}
                            fieldError={fieldError}
                            requestError={requestError}
                            isSubmitting={isSubmitting}
                            decisionsEnabled={viewState === "recorded"}
                            onOpenDecision={openDecision}
                            onCancel={cancelDecision}
                            onTypedNameChange={setTypedName}
                            onReasonChange={setReason}
                            onAccept={submitAcceptance}
                            onDecline={submitDecline}
                        />
                    </div>
                </aside>
            </div>

            <p className="mx-auto max-w-7xl px-1 pt-6 text-center text-xs text-muted-foreground sm:text-left">
                {t("footer")}
            </p>
        </main>
    );
}

function Detail({ label, value }: { label: string; value: string }) {
    return (
        <div className="grid gap-1">
            <dt className="text-xs font-medium text-muted-foreground">{label}</dt>
            <dd className="break-words text-sm font-medium text-foreground">{value}</dd>
        </div>
    );
}

function DecisionPanel({
    actionable,
    mode,
    receipt,
    typedName,
    reason,
    fieldError,
    requestError,
    isSubmitting,
    decisionsEnabled,
    onOpenDecision,
    onCancel,
    onTypedNameChange,
    onReasonChange,
    onAccept,
    onDecline,
}: {
    actionable: boolean;
    mode: DecisionMode;
    receipt: DecisionReceipt;
    typedName: string;
    reason: string;
    fieldError: string | null;
    requestError: boolean;
    isSubmitting: boolean;
    decisionsEnabled: boolean;
    onOpenDecision: (mode: Exclude<DecisionMode, null>) => void;
    onCancel: () => void;
    onTypedNameChange: (value: string) => void;
    onReasonChange: (value: string) => void;
    onAccept: (event: FormEvent<HTMLFormElement>) => Promise<void>;
    onDecline: (event: FormEvent<HTMLFormElement>) => Promise<void>;
}) {
    const t = useTranslations("DocumentAcceptance");

    if (receipt) {
        const accepted = receipt === "accepted";
        const Icon = accepted ? CheckCircleIcon : XCircleIcon;
        return (
            <div aria-live="polite">
                <Icon
                    aria-hidden="true"
                    className={accepted ? "size-9 text-brand-dark" : "size-9 text-destructive"}
                />
                <h2 className="mt-4 text-lg font-semibold text-foreground">
                    {accepted ? t("acceptedTitle") : t("declinedTitle")}
                </h2>
                <p className="mt-2 text-sm leading-6 text-muted-foreground">
                    {accepted ? t("acceptedBody") : t("declinedBody")}
                </p>
            </div>
        );
    }

    if (!actionable) {
        return (
            <div>
                <h2 className="text-base font-semibold text-foreground">{t("viewerTitle")}</h2>
                <p className="mt-2 text-sm leading-6 text-muted-foreground">{t("viewerBody")}</p>
            </div>
        );
    }

    if (mode === "accept") {
        return (
            <form className="grid gap-4" onSubmit={onAccept} noValidate>
                <div>
                    <h2 className="text-base font-semibold text-foreground">{t("acceptTitle")}</h2>
                    <div className="mt-4 grid gap-2">
                        <Label htmlFor="document-acceptance-name">{t("typedNameLabel")}</Label>
                        <Input
                            id="document-acceptance-name"
                            autoComplete="name"
                            value={typedName}
                            maxLength={255}
                            aria-invalid={fieldError != null}
                            aria-describedby={
                                fieldError
                                    ? "document-acceptance-name-help document-acceptance-name-error"
                                    : "document-acceptance-name-help"
                            }
                            onChange={(event) => onTypedNameChange(event.target.value)}
                            disabled={isSubmitting || !decisionsEnabled}
                        />
                        <p id="document-acceptance-name-help" className="text-xs leading-5 text-muted-foreground">
                            {t("typedNameHelper")}
                        </p>
                        {fieldError ? (
                            <p id="document-acceptance-name-error" role="alert" className="text-xs text-destructive">
                                {fieldError}
                            </p>
                        ) : null}
                    </div>
                </div>
                <RequestError visible={requestError} />
                <div className="grid grid-cols-1 gap-2 sm:grid-cols-2">
                    <Button type="submit" variant="brand" disabled={isSubmitting || !decisionsEnabled}>
                        {isSubmitting ? t("accepting") : t("confirmAccept")}
                    </Button>
                    <Button type="button" variant="outline" onClick={onCancel} disabled={isSubmitting || !decisionsEnabled}>
                        {t("cancel")}
                    </Button>
                </div>
            </form>
        );
    }

    if (mode === "decline") {
        return (
            <form className="grid gap-4" onSubmit={onDecline} noValidate>
                <div>
                    <h2 className="text-base font-semibold text-foreground">{t("declineTitle")}</h2>
                    <p className="mt-2 text-xs leading-5 text-muted-foreground">{t("declineWarning")}</p>
                    <div className="mt-4 grid gap-2">
                        <Label htmlFor="document-decline-reason">{t("reasonLabel")}</Label>
                        <Textarea
                            id="document-decline-reason"
                            value={reason}
                            maxLength={500}
                            rows={4}
                            aria-invalid={fieldError != null}
                            aria-describedby={
                                fieldError
                                    ? "document-decline-help document-decline-error"
                                    : "document-decline-help"
                            }
                            onChange={(event) => onReasonChange(event.target.value)}
                            disabled={isSubmitting || !decisionsEnabled}
                        />
                        <p id="document-decline-help" className="text-xs leading-5 text-muted-foreground">
                            {t("reasonHelper")}
                        </p>
                        {fieldError ? (
                            <p id="document-decline-error" role="alert" className="text-xs text-destructive">
                                {fieldError}
                            </p>
                        ) : null}
                    </div>
                </div>
                <RequestError visible={requestError} />
                <div className="grid grid-cols-1 gap-2 sm:grid-cols-2">
                    <Button type="submit" variant="destructive" disabled={isSubmitting || !decisionsEnabled}>
                        {isSubmitting ? t("declining") : t("confirmDecline")}
                    </Button>
                    <Button type="button" variant="outline" onClick={onCancel} disabled={isSubmitting || !decisionsEnabled}>
                        {t("cancel")}
                    </Button>
                </div>
            </form>
        );
    }

    return (
        <div>
            <h2 className="text-base font-semibold text-foreground">{t("reviewTitle")}</h2>
            <p className="mt-2 text-sm leading-6 text-muted-foreground">{t("reviewBody")}</p>
            {!decisionsEnabled ? (
                <div
                    role="status"
                    className="mt-4 flex items-center gap-2 rounded-xl border border-border bg-muted/40 px-3 py-2.5 text-sm text-muted-foreground"
                >
                    <ClockIcon aria-hidden="true" className="size-4 shrink-0" />
                    <span>{t("preparingResponse")}</span>
                </div>
            ) : null}
            <div className="mt-5 grid grid-cols-1 gap-2 sm:grid-cols-2">
                <Button
                    type="button"
                    variant="brand"
                    disabled={!decisionsEnabled}
                    onClick={() => onOpenDecision("accept")}
                >
                    {t("accept")}
                </Button>
                <Button
                    type="button"
                    variant="outline"
                    disabled={!decisionsEnabled}
                    onClick={() => onOpenDecision("decline")}
                >
                    {t("decline")}
                </Button>
            </div>
        </div>
    );
}

function RequestError({ visible }: { visible: boolean }) {
    const t = useTranslations("DocumentAcceptance");
    if (!visible) return null;
    return (
        <div role="alert" className="rounded-xl border border-destructive/30 bg-destructive/5 p-3">
            <p className="text-sm font-medium text-foreground">{t("responseErrorTitle")}</p>
            <p className="mt-1 text-xs leading-5 text-muted-foreground">{t("responseErrorBody")}</p>
        </div>
    );
}
