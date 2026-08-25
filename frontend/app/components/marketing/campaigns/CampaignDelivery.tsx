"use client";

import { useMemo, useState } from "react";
import { useLocale, useTranslations } from "next-intl";
import { Loader2Icon } from "lucide-react";
import { InformationCircleIcon, PlusIcon } from "@heroicons/react/24/outline";
import { Button } from "@/components/ui/button";
import { Label } from "@/components/ui/label";
import { Input } from "@/components/ui/input";
import { Textarea } from "@/components/ui/textarea";
import {
    Select,
    SelectTrigger,
    SelectValue,
    SelectContent,
    SelectItem,
} from "@/components/ui/select";
import Panel from "@/app/components/overview/analytics/Panel";
import PermissionsUnavailable from "@/app/components/PermissionsUnavailable";
import WorkspaceUnavailableRetry from "@/app/components/WorkspaceUnavailableRetry";
import CampaignCounter from "@/app/components/marketing/campaigns/CampaignCounter";
import SendStatusBadge from "@/app/components/marketing/campaigns/SendStatusBadge";
import NewMessageDialog from "@/app/components/marketing/campaigns/NewMessageDialog";
import { cn } from "@/lib/utils";
import {
    ApiError,
    addCampaignMessageRevision,
    cancelCampaignSend,
    createCampaignMessage,
    createCampaignSend,
    isFieldError,
    pauseCampaignSend,
    queueCampaignSend,
} from "@/app/lib/api";
import {
    type CampaignAudienceSnapshotSummary,
    type CampaignMessage,
    type CampaignMessageLocale,
    type CampaignMessagePayload,
    type CampaignSend,
} from "@/app/lib/types";
import { canCreateSend, type CampaignAccess } from "@/app/lib/campaignAccess";
import { useApiErrorToast } from "@/app/hooks/useApiErrorToast";
import { toastError, toastSuccess } from "@/app/lib/toast";
import { formatDate } from "@/app/lib/utils";
import type { CapabilityAvailability } from "@/app/lib/capabilityAvailability";

const LOCALES: CampaignMessageLocale[] = ["en", "ja"];
const EMPTY_MESSAGE_PAYLOAD: CampaignMessagePayload = { name: "", channel: "email" };

type RevisionDraft = {
    locale: CampaignMessageLocale;
    subject: string;
    bodyHtml: string;
    bodyText: string;
};

const EMPTY_REVISION_DRAFT: RevisionDraft = { locale: "en", subject: "", bodyHtml: "", bodyText: "" };

/**
 * The message-authoring and send lifecycle surface for a campaign. Holds the messages and sends as
 * a single source of truth so a freshly authored message is immediately sendable, and gates the
 * queue/pause/cancel controls on the caller's resolved permissions.
 *
 * Delivery is an instance capability that is off by default, so the queue control states that up
 * front rather than letting the reader discover it from a 403 they already committed to.
 */
export default function CampaignDelivery({
    campaignId,
    initialMessages,
    initialSends,
    snapshots,
    access,
    deliveryAvailability,
}: {
    campaignId: number;
    initialMessages: CampaignMessage[];
    initialSends: CampaignSend[];
    snapshots: CampaignAudienceSnapshotSummary[];
    access: CampaignAccess;
    deliveryAvailability: CapabilityAvailability;
}) {
    const t = useTranslations("CampaignMessages");
    const st = useTranslations("CampaignSends");
    const tCapability = useTranslations("CapabilityUnavailable");
    const showMessageApiError = useApiErrorToast("CampaignMessages");
    const showSendApiError = useApiErrorToast("CampaignSends");
    const locale = useLocale();

    const [messages, setMessages] = useState<CampaignMessage[]>(initialMessages);
    const [sends, setSends] = useState<CampaignSend[]>(initialSends);

    const [dialogOpen, setDialogOpen] = useState(false);
    const [messagePayload, setMessagePayload] = useState<CampaignMessagePayload>(EMPTY_MESSAGE_PAYLOAD);
    const [isCreatingMessage, setIsCreatingMessage] = useState(false);
    const [messageCreated, setMessageCreated] = useState(false);

    const [selectedMessageId, setSelectedMessageId] = useState<number | null>(
        initialMessages[0]?.id ?? null,
    );
    const [revision, setRevision] = useState<RevisionDraft>(EMPTY_REVISION_DRAFT);
    const [isSavingRevision, setIsSavingRevision] = useState(false);

    const [sendSnapshot, setSendSnapshot] = useState<string>("");
    const [sendMessageId, setSendMessageId] = useState<string>("");
    const [sendVersion, setSendVersion] = useState<string>("");
    const [sendPurpose, setSendPurpose] = useState<string>("");
    const [isCreatingSend, setIsCreatingSend] = useState(false);
    const [actionSendId, setActionSendId] = useState<number | null>(null);
    const [deliveryRefused, setDeliveryRefused] = useState(false);

    const deliveryDisabled = deliveryAvailability === "disabled" || deliveryRefused;
    const deliveryUnavailable = deliveryAvailability !== "enabled" || deliveryRefused;
    const canManage = access.manage;
    const canSend = access.send;
    const canMaterializeSend = canCreateSend(access);

    const selectedMessage = useMemo(
        () => messages.find((message) => message.id === selectedMessageId) ?? null,
        [messages, selectedMessageId],
    );
    const sendableMessages = useMemo(
        () => messages.filter((message) => message.revisions.length > 0),
        [messages],
    );
    const sendMessage = useMemo(
        () => messages.find((message) => String(message.id) === sendMessageId) ?? null,
        [messages, sendMessageId],
    );
    const chosenSnapshot = useMemo(
        () => snapshots.find((snapshot) => String(snapshot.version) === sendSnapshot) ?? null,
        [snapshots, sendSnapshot],
    );

    const channelLabel = (channel: string) => {
        if (channel === "email") return t("channels.email");
        if (channel === "sms") return t("channels.sms");
        return channel;
    };

    const isSmsMessage = selectedMessage?.channel === "sms";

    const openMessageDialog = () => {
        setMessagePayload(EMPTY_MESSAGE_PAYLOAD);
        setMessageCreated(false);
        setDialogOpen(true);
    };

    const createMessage = async () => {
        setIsCreatingMessage(true);
        try {
            const created = await createCampaignMessage(campaignId, {
                ...messagePayload,
                name: messagePayload.name.trim(),
            });
            setMessages((prev) => [created, ...prev]);
            setSelectedMessageId(created.id);
            setRevision(EMPTY_REVISION_DRAFT);
            setMessageCreated(true);
            setDialogOpen(false);
            toastSuccess(t("created"));
        } catch (err) {
            setIsCreatingMessage(false);
            if (isFieldError(err)) throw err;
            showMessageApiError(err, "createFailed");
            return;
        }
        setIsCreatingMessage(false);
    };

    const saveRevision = async () => {
        if (!selectedMessage) return;
        setIsSavingRevision(true);
        try {
            const updated = await addCampaignMessageRevision(
                campaignId,
                selectedMessage.id,
                selectedMessage.channel === "sms"
                    ? {
                          locale: revision.locale,
                          subject: null,
                          bodyHtml: null,
                          bodyText: revision.bodyText.trim(),
                      }
                    : {
                          locale: revision.locale,
                          subject: revision.subject.trim(),
                          bodyHtml: revision.bodyHtml,
                          bodyText: revision.bodyText.trim() ? revision.bodyText : null,
                      },
            );
            setMessages((prev) =>
                prev.map((message) => (message.id === updated.id ? updated : message)),
            );
            setRevision((prev) => ({ ...EMPTY_REVISION_DRAFT, locale: prev.locale }));
            toastSuccess(t("revisionSaved"));
        } catch (err) {
            showMessageApiError(err, "revisionSaveFailed");
        } finally {
            setIsSavingRevision(false);
        }
    };

    const changeSendMessage = (value: string) => {
        setSendMessageId(value);
        setSendVersion("");
    };

    const createSend = async () => {
        const snapshotVersion = Number(sendSnapshot);
        const messageId = Number(sendMessageId);
        const messageVersion = Number(sendVersion);
        if (!snapshotVersion || !messageId || !messageVersion) return;
        setIsCreatingSend(true);
        try {
            const created = await createCampaignSend(campaignId, {
                snapshotVersion,
                messageId,
                messageVersion,
                purpose: sendPurpose.trim() || null,
            });
            setSends((prev) => [created, ...prev]);
            setSendSnapshot("");
            setSendMessageId("");
            setSendVersion("");
            setSendPurpose("");
            toastSuccess(st("created"));
        } catch (err) {
            showSendApiError(err, "createFailed");
        } finally {
            setIsCreatingSend(false);
        }
    };

    const applySend = (updated: CampaignSend) =>
        setSends((prev) => prev.map((send) => (send.id === updated.id ? updated : send)));

    const queue = async (send: CampaignSend) => {
        setActionSendId(send.id);
        try {
            applySend(await queueCampaignSend(campaignId, send.id));
            toastSuccess(st("queued"));
        } catch (err) {
            if (err instanceof ApiError && err.status === 403) {
                setDeliveryRefused(true);
                toastError(st("deliveryUnavailable"));
            } else {
                showSendApiError(err, "queueFailed");
            }
        } finally {
            setActionSendId(null);
        }
    };

    const pause = async (send: CampaignSend) => {
        setActionSendId(send.id);
        try {
            applySend(await pauseCampaignSend(campaignId, send.id));
            toastSuccess(st("paused"));
        } catch (err) {
            showSendApiError(err, "pauseFailed");
        } finally {
            setActionSendId(null);
        }
    };

    const cancel = async (send: CampaignSend) => {
        setActionSendId(send.id);
        try {
            applySend(await cancelCampaignSend(campaignId, send.id));
            toastSuccess(st("cancelled"));
        } catch (err) {
            showSendApiError(err, "cancelFailed");
        } finally {
            setActionSendId(null);
        }
    };

    const messageName = (messageId: number) =>
        messages.find((message) => message.id === messageId)?.name ?? st("unknownMessage");

    return (
        <div className="flex flex-col gap-6">
            <Panel
                title={t("title")}
                subtitle={t("subtitle")}
                action={
                    canManage ? (
                        <Button variant="outline" size="sm" onClick={openMessageDialog}>
                            <PlusIcon className="size-4" />
                            {t("new")}
                        </Button>
                    ) : undefined
                }
            >
                {messages.length === 0 ? (
                    <div className="rounded-xl border border-dashed border-border px-4 py-8 text-center">
                        <p className="text-sm font-medium text-foreground">{t("empty")}</p>
                        <p className="mt-1 text-xs text-muted-foreground">{t("emptyHint")}</p>
                    </div>
                ) : (
                    <div className="grid grid-cols-1 gap-6 lg:grid-cols-[18rem_1fr]">
                        <ul className="flex flex-col gap-1.5">
                            {messages.map((message) => {
                                const locales = Array.from(
                                    new Set(message.revisions.map((rev) => rev.locale)),
                                );
                                const isSelected = message.id === selectedMessageId;
                                return (
                                    <li key={message.id}>
                                        <button
                                            type="button"
                                            onClick={() => {
                                                setSelectedMessageId(message.id);
                                                setRevision(EMPTY_REVISION_DRAFT);
                                            }}
                                            className={cn(
                                                "w-full rounded-xl border px-3.5 py-3 text-left transition-colors",
                                                isSelected
                                                    ? "border-brand/40 bg-brand-light/40"
                                                    : "border-border bg-card hover:bg-muted/60",
                                            )}
                                        >
                                            <div className="flex items-center justify-between gap-2">
                                                <span className="truncate text-sm font-medium text-foreground">
                                                    {message.name}
                                                </span>
                                                <span className="shrink-0 text-xs text-muted-foreground">
                                                    {channelLabel(message.channel)}
                                                </span>
                                            </div>
                                            <div className="mt-2 flex flex-wrap items-center gap-1.5">
                                                <span className="inline-flex items-center rounded-full bg-muted px-2 py-0.5 text-xs text-muted-foreground ring-1 ring-inset ring-border">
                                                    {t(`messageStatus.${message.status}`)}
                                                </span>
                                                {locales.length === 0 ? (
                                                    <span className="text-xs text-muted-foreground">
                                                        {t("noContent")}
                                                    </span>
                                                ) : (
                                                    locales.map((code) => (
                                                        <span
                                                            key={code}
                                                            className="inline-flex items-center rounded-full bg-muted px-2 py-0.5 font-mono text-xs uppercase text-foreground ring-1 ring-inset ring-border"
                                                        >
                                                            {code}
                                                        </span>
                                                    ))
                                                )}
                                            </div>
                                        </button>
                                    </li>
                                );
                            })}
                        </ul>

                        {selectedMessage ? (
                            <div className="flex flex-col gap-5">
                                {canManage && (
                                    <div className="flex flex-col gap-4 rounded-xl border border-border bg-card p-4">
                                        <div className="grid grid-cols-1 gap-4 sm:grid-cols-[8rem_1fr]">
                                            <div className="grid gap-1.5">
                                                <Label htmlFor="revision-locale">{t("locale")}</Label>
                                                <Select
                                                    value={revision.locale}
                                                    onValueChange={(value) =>
                                                        setRevision((prev) => ({
                                                            ...prev,
                                                            locale: value as CampaignMessageLocale,
                                                        }))
                                                    }
                                                >
                                                    <SelectTrigger id="revision-locale" className="w-full">
                                                        <SelectValue />
                                                    </SelectTrigger>
                                                    <SelectContent>
                                                        {LOCALES.map((code) => (
                                                            <SelectItem key={code} value={code}>
                                                                {t(`locales.${code}`)}
                                                            </SelectItem>
                                                        ))}
                                                    </SelectContent>
                                                </Select>
                                            </div>
                                            {!isSmsMessage && (
                                                <div className="grid gap-1.5">
                                                    <Label htmlFor="revision-subject">{t("subject")}</Label>
                                                    <Input
                                                        id="revision-subject"
                                                        type="text"
                                                        value={revision.subject}
                                                        onChange={(e) =>
                                                            setRevision((prev) => ({
                                                                ...prev,
                                                                subject: e.target.value,
                                                            }))
                                                        }
                                                        placeholder={t("subjectPlaceholder")}
                                                        maxLength={255}
                                                    />
                                                </div>
                                            )}
                                        </div>
                                        {!isSmsMessage && (
                                            <div className="grid gap-1.5">
                                                <Label htmlFor="revision-body-html">{t("bodyHtml")}</Label>
                                                <Textarea
                                                    id="revision-body-html"
                                                    value={revision.bodyHtml}
                                                    onChange={(e) =>
                                                        setRevision((prev) => ({
                                                            ...prev,
                                                            bodyHtml: e.target.value,
                                                        }))
                                                    }
                                                    className="min-h-40 resize-y font-mono"
                                                    placeholder={t("bodyHtmlPlaceholder")}
                                                />
                                                <p className="text-xs text-muted-foreground">
                                                    {t("unsubscribeHint")}
                                                </p>
                                            </div>
                                        )}
                                        <div className="grid gap-1.5">
                                            <Label htmlFor="revision-body-text">
                                                {isSmsMessage ? t("smsBody") : t("bodyText")}
                                            </Label>
                                            <Textarea
                                                id="revision-body-text"
                                                value={revision.bodyText}
                                                onChange={(e) =>
                                                    setRevision((prev) => ({
                                                        ...prev,
                                                        bodyText: e.target.value,
                                                    }))
                                                }
                                                className="min-h-24 resize-y"
                                                placeholder={
                                                    isSmsMessage
                                                        ? t("smsBodyPlaceholder")
                                                        : t("bodyTextPlaceholder")
                                                }
                                            />
                                            {isSmsMessage && (
                                                <p className="text-xs text-muted-foreground">{t("smsHint")}</p>
                                            )}
                                        </div>
                                        <div className="flex justify-end">
                                            <Button
                                                variant="brand"
                                                size="sm"
                                                onClick={saveRevision}
                                                disabled={
                                                    isSavingRevision ||
                                                    (isSmsMessage
                                                        ? !revision.bodyText.trim()
                                                        : !revision.subject.trim() ||
                                                          !revision.bodyHtml.trim())
                                                }
                                            >
                                                {isSavingRevision ? (
                                                    <>
                                                        <Loader2Icon className="size-4 animate-spin" />
                                                        {t("saving")}
                                                    </>
                                                ) : (
                                                    t("saveRevision")
                                                )}
                                            </Button>
                                        </div>
                                    </div>
                                )}

                                <div>
                                    <h3 className="text-xs font-medium uppercase tracking-[0.12em] text-muted-foreground">
                                        {t("revisions")}
                                    </h3>
                                    {selectedMessage.revisions.length === 0 ? (
                                        <p className="mt-3 text-sm text-muted-foreground">{t("noRevisions")}</p>
                                    ) : (
                                        <ul className="mt-3 divide-y divide-border">
                                            {selectedMessage.revisions.map((rev) => (
                                                <li
                                                    key={rev.version}
                                                    className="flex items-center justify-between gap-3 py-2.5"
                                                >
                                                    <div className="flex min-w-0 items-center gap-3">
                                                        <span className="inline-flex items-center rounded-full bg-muted px-2 py-0.5 font-mono text-xs text-foreground ring-1 ring-inset ring-border">
                                                            {t("version", { version: rev.version })}
                                                        </span>
                                                        <span className="inline-flex items-center rounded-full bg-muted px-2 py-0.5 font-mono text-xs uppercase text-foreground ring-1 ring-inset ring-border">
                                                            {rev.locale}
                                                        </span>
                                                        <span className="truncate text-sm text-foreground">
                                                            {isSmsMessage ? (rev.bodyText ?? "") : rev.subject}
                                                        </span>
                                                    </div>
                                                    <span className="shrink-0 text-xs text-muted-foreground">
                                                        {formatDate(rev.createdAt, locale)}
                                                    </span>
                                                </li>
                                            ))}
                                        </ul>
                                    )}
                                </div>
                            </div>
                        ) : (
                            <div className="rounded-xl border border-dashed border-border px-4 py-8 text-center">
                                <p className="text-sm text-muted-foreground">{t("selectHint")}</p>
                            </div>
                        )}
                    </div>
                )}
            </Panel>

            <Panel title={st("title")} subtitle={st("subtitle")}>
                <div className="flex flex-col gap-6">
                    {deliveryAvailability === "unavailable" ? (
                        <PermissionsUnavailable
                            variant="inline"
                            title={tCapability("title")}
                            body={tCapability("body")}
                            action={(
                                <WorkspaceUnavailableRetry
                                    label={tCapability("retry")}
                                    pendingLabel={tCapability("retrying")}
                                />
                            )}
                        />
                    ) : (
                        <div className="flex items-start gap-3 rounded-xl border border-dashed border-border bg-muted/40 px-4 py-3">
                            <InformationCircleIcon
                                aria-hidden
                                className="mt-0.5 size-4 shrink-0 text-muted-foreground"
                            />
                            <p className="text-sm text-muted-foreground">
                                {deliveryDisabled ? st("deliveryUnavailable") : st("queueHint")}
                            </p>
                        </div>
                    )}
                    {canMaterializeSend && (
                        <div className="flex flex-col gap-4 rounded-xl border border-border bg-card p-4">
                            <div className="grid grid-cols-1 gap-4 md:grid-cols-3">
                                <div className="grid gap-1.5">
                                    <Label htmlFor="send-snapshot">{st("snapshot")}</Label>
                                    <Select value={sendSnapshot} onValueChange={setSendSnapshot}>
                                        <SelectTrigger id="send-snapshot" className="w-full">
                                            <SelectValue placeholder={st("snapshotPlaceholder")} />
                                        </SelectTrigger>
                                        <SelectContent>
                                            {snapshots.map((snapshot) => (
                                                <SelectItem
                                                    key={snapshot.version}
                                                    value={String(snapshot.version)}
                                                >
                                                    {st("snapshotOption", {
                                                        version: snapshot.version,
                                                        count: snapshot.estimatedIncluded.toLocaleString(locale),
                                                    })}
                                                </SelectItem>
                                            ))}
                                        </SelectContent>
                                    </Select>
                                </div>
                                <div className="grid gap-1.5">
                                    <Label htmlFor="send-message">{st("message")}</Label>
                                    <Select value={sendMessageId} onValueChange={changeSendMessage}>
                                        <SelectTrigger id="send-message" className="w-full">
                                            <SelectValue placeholder={st("messagePlaceholder")} />
                                        </SelectTrigger>
                                        <SelectContent>
                                            {sendableMessages.map((message) => (
                                                <SelectItem key={message.id} value={String(message.id)}>
                                                    {message.name}
                                                </SelectItem>
                                            ))}
                                        </SelectContent>
                                    </Select>
                                </div>
                                <div className="grid gap-1.5">
                                    <Label htmlFor="send-version">{st("version")}</Label>
                                    <Select
                                        value={sendVersion}
                                        onValueChange={setSendVersion}
                                        disabled={!sendMessage}
                                    >
                                        <SelectTrigger id="send-version" className="w-full">
                                            <SelectValue placeholder={st("versionPlaceholder")} />
                                        </SelectTrigger>
                                        <SelectContent>
                                            {(sendMessage?.revisions ?? []).map((rev) => (
                                                <SelectItem key={rev.version} value={String(rev.version)}>
                                                    {st("versionOption", {
                                                        version: rev.version,
                                                        locale: rev.locale.toUpperCase(),
                                                    })}
                                                </SelectItem>
                                            ))}
                                        </SelectContent>
                                    </Select>
                                </div>
                            </div>

                            <div className="grid grid-cols-1 gap-4 md:grid-cols-2">
                                <div className="grid gap-1.5">
                                    <Label htmlFor="send-purpose">{st("purpose")}</Label>
                                    <Input
                                        id="send-purpose"
                                        type="text"
                                        value={sendPurpose}
                                        onChange={(e) => setSendPurpose(e.target.value)}
                                        placeholder={st("purposePlaceholder")}
                                        maxLength={32}
                                    />
                                </div>
                            </div>

                            {chosenSnapshot && (
                                <div className="flex flex-wrap items-center gap-4 rounded-lg bg-muted/60 px-4 py-3">
                                    <span className="text-xs font-medium uppercase tracking-[0.1em] text-muted-foreground">
                                        {st("eligible")}
                                    </span>
                                    <span className="tabular-nums text-sm font-semibold text-brand-hover">
                                        {st("eligibleIncluded", {
                                            count: chosenSnapshot.estimatedIncluded.toLocaleString(locale),
                                        })}
                                    </span>
                                    <span className="tabular-nums text-sm text-muted-foreground">
                                        {st("eligibleExcluded", {
                                            count: chosenSnapshot.excludedTotal.toLocaleString(locale),
                                        })}
                                    </span>
                                </div>
                            )}

                            <div className="flex items-center justify-between gap-3">
                                <p className="text-xs text-muted-foreground">{st("createHint")}</p>
                                <Button
                                    variant="outline"
                                    size="sm"
                                    onClick={createSend}
                                    disabled={
                                        isCreatingSend || !sendSnapshot || !sendMessageId || !sendVersion
                                    }
                                >
                                    {isCreatingSend ? (
                                        <>
                                            <Loader2Icon className="size-4 animate-spin" />
                                            {st("creating")}
                                        </>
                                    ) : (
                                        st("create")
                                    )}
                                </Button>
                            </div>
                        </div>
                    )}

                    {sends.length === 0 ? (
                        <p className="py-4 text-sm text-muted-foreground">{st("empty")}</p>
                    ) : (
                        <ul className="divide-y divide-border">
                            {sends.map((send) => {
                                const busy = actionSendId === send.id;
                                const canQueue = send.status === "draft";
                                const canPause = send.status === "queued" || send.status === "running";
                                const canCancelSend =
                                    send.status === "draft" ||
                                    send.status === "queued" ||
                                    send.status === "running" ||
                                    send.status === "paused";
                                return (
                                    <li key={send.id} className="flex flex-col gap-3 py-4">
                                        <div className="flex flex-wrap items-center justify-between gap-3">
                                            <div className="flex min-w-0 flex-wrap items-center gap-3">
                                                <SendStatusBadge status={send.status} />
                                                <span className="truncate text-sm font-medium text-foreground">
                                                    {messageName(send.messageId)}
                                                </span>
                                                <span className="text-xs text-muted-foreground">
                                                    {st("messageVersionLabel", { version: send.messageVersion })}
                                                </span>
                                            </div>
                                            <span className="shrink-0 text-xs text-muted-foreground">
                                                {formatDate(send.createdAt, locale)}
                                            </span>
                                        </div>

                                        <div className="grid grid-cols-2 gap-3 sm:grid-cols-4 sm:gap-4">
                                            <CampaignCounter
                                                label={st("total")}
                                                value={send.totalRecipients.toLocaleString(locale)}
                                            />
                                            <CampaignCounter
                                                label={st("dispatched")}
                                                value={send.dispatchedCount.toLocaleString(locale)}
                                            />
                                            <CampaignCounter
                                                label={st("skipped")}
                                                value={send.skippedCount.toLocaleString(locale)}
                                            />
                                            <CampaignCounter
                                                label={st("failed")}
                                                value={send.failedCount.toLocaleString(locale)}
                                            />
                                        </div>

                                        {canSend && (canQueue || canPause || canCancelSend) && (
                                            <div className="flex flex-wrap items-center gap-2">
                                                {canQueue && (
                                                    <Button
                                                        variant="brand"
                                                        size="sm"
                                                        onClick={() => queue(send)}
                                                        disabled={busy || deliveryUnavailable}
                                                    >
                                                        {busy ? (
                                                            <Loader2Icon className="size-4 animate-spin" />
                                                        ) : null}
                                                        {st("queue")}
                                                    </Button>
                                                )}
                                                {canPause && (
                                                    <Button
                                                        variant="outline"
                                                        size="sm"
                                                        onClick={() => pause(send)}
                                                        disabled={busy}
                                                    >
                                                        {st("pause")}
                                                    </Button>
                                                )}
                                                {canCancelSend && (
                                                    <Button
                                                        variant="ghost"
                                                        size="sm"
                                                        onClick={() => cancel(send)}
                                                        disabled={busy}
                                                        className="text-muted-foreground hover:text-destructive"
                                                    >
                                                        {st("cancel")}
                                                    </Button>
                                                )}
                                            </div>
                                        )}

                                    </li>
                                );
                            })}
                        </ul>
                    )}
                </div>
            </Panel>

            <NewMessageDialog
                open={dialogOpen}
                onOpenChange={setDialogOpen}
                payload={messagePayload}
                setPayload={setMessagePayload}
                isCreating={isCreatingMessage}
                isSuccess={messageCreated}
                createMessage={createMessage}
            />
        </div>
    );
}
