"use client";

import { useEffect, useRef, useState } from "react";
import { useTranslations } from "next-intl";
import { UserIcon, EnvelopeIcon, InboxArrowDownIcon, MagnifyingGlassIcon, XMarkIcon } from "@heroicons/react/24/outline";

import {
    Dialog,
    DialogContent,
    DialogHeader,
    DialogTitle,
    DialogDescription,
    DialogFooter,
    DialogClose,
} from "@/components/ui/dialog";
import { Button } from "@/components/ui/button";
import { Label } from "@/components/ui/label";
import {
    Select,
    SelectContent,
    SelectItem,
    SelectTrigger,
    SelectValue,
} from "@/components/ui/select";
import { Textarea } from "@/components/ui/textarea";
import {
    DialogStatusCover,
    resolveDialogStatus,
    fieldInputClass,
    fieldErrorClass,
    fieldLeadIconClass,
} from "@/components/ui/dialog-status-cover";
import { cn } from "@/lib/utils";
import type {
    Contact,
    DataSubjectRequest,
    DataSubjectRequestBody,
    DataSubjectRequestStatus,
    DataSubjectRequestType,
} from "@/app/lib/types";
import { createDataSubjectRequest, getContactsPage, updateDataSubjectRequest } from "@/app/lib/api";
import { toastSuccess } from "@/app/lib/toast";
import { useApiErrorToast } from "@/app/hooks/useApiErrorToast";
import { usePasskeyStepUpErrorHandler } from "@/app/hooks/usePasskeyStepUpError";
import { useWorkspace } from "@/app/hooks/useWorkspace";

const REQUEST_TYPES: DataSubjectRequestType[] = ["disclosure", "correction", "cease_use", "cease_provision"];
const REQUEST_STATUSES: DataSubjectRequestStatus[] = [
    "received",
    "verifying",
    "in_progress",
    "responded",
    "refused",
    "closed",
];

type Props = {
    open: boolean;
    onOpenChange: (open: boolean) => void;
    orgId: number | null;
    editing: DataSubjectRequest | null;
    onSaved: (saved: DataSubjectRequest) => void;
};

function toInputValue(iso: string | null | undefined) {
    if (!iso) return "";
    return iso.replace(" ", "T").slice(0, 16);
}

function nowInputValue() {
    const now = new Date();
    const offset = now.getTimezoneOffset() * 60000;
    return new Date(now.getTime() - offset).toISOString().slice(0, 16);
}

function toApiValue(input: string) {
    if (!input) return undefined;
    return input.length === 16 ? `${input}:00` : input;
}

/**
 * Intake and lifecycle editor for an APPI data-subject request. Mirrors the app's
 * create-dialog kit (status band, staggered rise, success hold); the field state lives in
 * the inner form, remounted per target via `key`.
 */
export default function DataRequestDialog({ open, onOpenChange, orgId, editing, onSaved }: Props) {
    const [isSaving, setIsSaving] = useState(false);
    const [succeeded, setSucceeded] = useState(false);

    const handleOpenChange = (next: boolean) => {
        if (!next && (isSaving || succeeded)) return;
        if (!next) {
            setIsSaving(false);
            setSucceeded(false);
        }
        onOpenChange(next);
    };

    const handleSubmit = async (body: DataSubjectRequestBody) => {
        if (orgId == null) return;
        setIsSaving(true);
        try {
            const saved = editing
                ? await updateDataSubjectRequest(orgId, editing.id, body)
                : await createDataSubjectRequest(orgId, body);
            onSaved(saved);
            setIsSaving(false);
            setSucceeded(true);
            setTimeout(() => {
                setSucceeded(false);
                onOpenChange(false);
            }, 900);
        } catch (err) {
            setIsSaving(false);
            throw err;
        }
    };

    return (
        <Dialog open={open} onOpenChange={handleOpenChange}>
            <DialogContent className="max-h-[85dvh] gap-0 overflow-y-auto p-0 sm:max-w-xl">
                {open && (
                    <RequestForm
                        key={editing ? `edit-${editing.id}` : "new"}
                        editing={editing}
                        isSaving={isSaving}
                        succeeded={succeeded}
                        onSubmit={handleSubmit}
                    />
                )}
            </DialogContent>
        </Dialog>
    );
}

function RequestForm({
    editing,
    isSaving,
    succeeded,
    onSubmit,
}: {
    editing: DataSubjectRequest | null;
    isSaving: boolean;
    succeeded: boolean;
    onSubmit: (body: DataSubjectRequestBody) => Promise<void>;
}) {
    const t = useTranslations("OrgDataRequests");
    const showApiError = useApiErrorToast("OrgDataRequests");
    const handlePasskeyStepUpError = usePasskeyStepUpErrorHandler();

    const [requestType, setRequestType] = useState<DataSubjectRequestType>(editing?.requestType ?? "disclosure");
    const [status, setStatus] = useState<DataSubjectRequestStatus>(editing?.status ?? "received");
    const [requesterName, setRequesterName] = useState(editing?.requesterName ?? "");
    const [subjectName, setSubjectName] = useState(editing?.subjectName ?? "");
    const [subjectEmail, setSubjectEmail] = useState(editing?.subjectEmail ?? "");
    const [channel, setChannel] = useState(editing?.channel ?? "");
    const [receivedAt, setReceivedAt] = useState(toInputValue(editing?.receivedAt) || nowInputValue());
    const [identityVerifiedAt, setIdentityVerifiedAt] = useState(toInputValue(editing?.identityVerifiedAt));
    const [dueAt, setDueAt] = useState(toInputValue(editing?.dueAt));
    const [respondedAt, setRespondedAt] = useState(toInputValue(editing?.respondedAt));
    const [closedAt, setClosedAt] = useState(toInputValue(editing?.closedAt));
    const [summary, setSummary] = useState(editing?.summary ?? "");
    const [resolution, setResolution] = useState(editing?.resolution ?? "");
    const [requesterError, setRequesterError] = useState<string | null>(null);
    const [subjectError, setSubjectError] = useState<string | null>(null);
    const [subjectLink, setSubjectLink] = useState<{ workspaceId: number; personId: number; label: string } | null>(
        editing?.subjectWorkspaceId != null && editing?.subjectPersonId != null
            ? {
                  workspaceId: editing.subjectWorkspaceId,
                  personId: editing.subjectPersonId,
                  label: t("linkedContactExisting", { id: editing.subjectPersonId }),
              }
            : null,
    );

    const status_ = resolveDialogStatus({
        isLoading: isSaving,
        hasErrors: Boolean(requesterError || subjectError),
        isSuccess: succeeded,
    });
    const locked = isSaving || succeeded;

    const handleSubmit = async () => {
        if (locked) return;
        const requester = requesterName.trim();
        const subject = subjectName.trim();
        if (!requester) {
            setRequesterError(t("requesterRequired"));
            return;
        }
        if (!subject) {
            setSubjectError(t("subjectRequired"));
            return;
        }
        setRequesterError(null);
        setSubjectError(null);
        try {
            await onSubmit({
                requestType,
                status,
                requesterName: requester,
                subjectName: subject,
                subjectEmail: subjectEmail.trim() || undefined,
                channel: channel.trim() || undefined,
                subjectWorkspaceId: subjectLink?.workspaceId,
                subjectPersonId: subjectLink?.personId,
                receivedAt: toApiValue(receivedAt),
                identityVerifiedAt: toApiValue(identityVerifiedAt),
                dueAt: toApiValue(dueAt),
                respondedAt: toApiValue(respondedAt),
                closedAt: toApiValue(closedAt),
                summary: summary.trim() || undefined,
                resolution: resolution.trim() || undefined,
            });
            toastSuccess(editing ? t("toastUpdated") : t("toastCreated"));
        } catch (err) {
            if (!handlePasskeyStepUpError(err)) {
                showApiError(err, "toastSaveFailed");
            }
        }
    };

    return (
        <>
            <DialogStatusCover status={status_} />

            <div className="px-6 pb-6">
                <DialogHeader className="ncd-rise -mt-12 mb-5" style={{ animationDelay: "40ms" }}>
                    <DialogTitle className="text-xl font-semibold tracking-tight">
                        {editing ? t("editTitle") : t("createTitle")}
                    </DialogTitle>
                    <DialogDescription>{editing ? t("editDescription") : t("createDescription")}</DialogDescription>
                </DialogHeader>

                <form
                    onSubmit={(e) => {
                        e.preventDefault();
                        void handleSubmit();
                    }}
                    className="grid gap-5"
                >
                    <div className="ncd-rise grid grid-cols-2 gap-3" style={{ animationDelay: "90ms" }}>
                        <div className="grid gap-1.5">
                            <Label htmlFor="dsr-type">{t("fieldType")}</Label>
                            <Select
                                value={requestType}
                                onValueChange={(value) => setRequestType(value as DataSubjectRequestType)}
                                disabled={locked}
                            >
                                <SelectTrigger id="dsr-type" className="w-full">
                                    <SelectValue />
                                </SelectTrigger>
                                <SelectContent>
                                    {REQUEST_TYPES.map((type) => (
                                        <SelectItem key={type} value={type}>
                                            {t(`type_${type}`)}
                                        </SelectItem>
                                    ))}
                                </SelectContent>
                            </Select>
                        </div>
                        <div className="grid gap-1.5">
                            <Label htmlFor="dsr-status">{t("fieldStatus")}</Label>
                            <Select
                                value={status}
                                onValueChange={(value) => setStatus(value as DataSubjectRequestStatus)}
                                disabled={locked}
                            >
                                <SelectTrigger id="dsr-status" className="w-full">
                                    <SelectValue />
                                </SelectTrigger>
                                <SelectContent>
                                    {REQUEST_STATUSES.map((value) => (
                                        <SelectItem key={value} value={value}>
                                            {t(`status_${value}`)}
                                        </SelectItem>
                                    ))}
                                </SelectContent>
                            </Select>
                        </div>
                    </div>

                    <div className="ncd-rise grid grid-cols-2 gap-3" style={{ animationDelay: "120ms" }}>
                        <div className="grid gap-1.5">
                            <Label htmlFor="dsr-requester">{t("fieldRequester")}</Label>
                            <div className="group relative">
                                <UserIcon className={fieldLeadIconClass} />
                                <input
                                    id="dsr-requester"
                                    type="text"
                                    value={requesterName}
                                    onChange={(e) => {
                                        setRequesterName(e.target.value);
                                        if (requesterError) setRequesterError(null);
                                    }}
                                    className={cn(fieldInputClass, "pl-9 pr-3", requesterError && fieldErrorClass)}
                                    placeholder={t("fieldRequesterPlaceholder")}
                                    aria-invalid={Boolean(requesterError)}
                                    autoFocus={!editing}
                                    maxLength={160}
                                    disabled={locked}
                                />
                            </div>
                            {requesterError && <p className="text-sm text-destructive">{requesterError}</p>}
                        </div>
                        <div className="grid gap-1.5">
                            <Label htmlFor="dsr-subject">{t("fieldSubject")}</Label>
                            <div className="group relative">
                                <UserIcon className={fieldLeadIconClass} />
                                <input
                                    id="dsr-subject"
                                    type="text"
                                    value={subjectName}
                                    onChange={(e) => {
                                        setSubjectName(e.target.value);
                                        if (subjectError) setSubjectError(null);
                                    }}
                                    className={cn(fieldInputClass, "pl-9 pr-3", subjectError && fieldErrorClass)}
                                    placeholder={t("fieldSubjectPlaceholder")}
                                    aria-invalid={Boolean(subjectError)}
                                    maxLength={160}
                                    disabled={locked}
                                />
                            </div>
                            {subjectError && <p className="text-sm text-destructive">{subjectError}</p>}
                        </div>
                    </div>

                    <div className="ncd-rise grid grid-cols-2 gap-3" style={{ animationDelay: "150ms" }}>
                        <div className="grid gap-1.5">
                            <Label htmlFor="dsr-email">{t("fieldSubjectEmail")}</Label>
                            <div className="group relative">
                                <EnvelopeIcon className={fieldLeadIconClass} />
                                <input
                                    id="dsr-email"
                                    type="email"
                                    value={subjectEmail}
                                    onChange={(e) => setSubjectEmail(e.target.value)}
                                    className={cn(fieldInputClass, "pl-9 pr-3")}
                                    placeholder={t("fieldSubjectEmailPlaceholder")}
                                    maxLength={255}
                                    disabled={locked}
                                />
                            </div>
                        </div>
                        <div className="grid gap-1.5">
                            <Label htmlFor="dsr-channel">{t("fieldChannel")}</Label>
                            <div className="group relative">
                                <InboxArrowDownIcon className={fieldLeadIconClass} />
                                <input
                                    id="dsr-channel"
                                    type="text"
                                    value={channel}
                                    onChange={(e) => setChannel(e.target.value)}
                                    className={cn(fieldInputClass, "pl-9 pr-3")}
                                    placeholder={t("fieldChannelPlaceholder")}
                                    maxLength={64}
                                    disabled={locked}
                                />
                            </div>
                        </div>
                    </div>

                    <SubjectLinkField value={subjectLink} onChange={setSubjectLink} disabled={locked} />

                    <div className="ncd-rise grid grid-cols-2 gap-3" style={{ animationDelay: "210ms" }}>
                        <DateTimeField
                            id="dsr-received"
                            label={t("fieldReceivedAt")}
                            value={receivedAt}
                            onChange={setReceivedAt}
                            disabled={locked}
                        />
                        <DateTimeField
                            id="dsr-verified"
                            label={t("fieldIdentityVerifiedAt")}
                            hint={t("fieldIdentityVerifiedHint")}
                            value={identityVerifiedAt}
                            onChange={setIdentityVerifiedAt}
                            disabled={locked}
                        />
                        <DateTimeField id="dsr-due" label={t("fieldDueAt")} value={dueAt} onChange={setDueAt} disabled={locked} />
                        <DateTimeField
                            id="dsr-responded"
                            label={t("fieldRespondedAt")}
                            value={respondedAt}
                            onChange={setRespondedAt}
                            disabled={locked}
                        />
                        <DateTimeField
                            id="dsr-closed"
                            label={t("fieldClosedAt")}
                            value={closedAt}
                            onChange={setClosedAt}
                            disabled={locked}
                        />
                    </div>

                    <div className="ncd-rise grid gap-3" style={{ animationDelay: "240ms" }}>
                        <div className="grid gap-1.5">
                            <Label htmlFor="dsr-summary">{t("fieldSummary")}</Label>
                            <Textarea
                                id="dsr-summary"
                                value={summary}
                                onChange={(e) => setSummary(e.target.value)}
                                placeholder={t("fieldSummaryPlaceholder")}
                                rows={2}
                                maxLength={10000}
                                disabled={locked}
                            />
                        </div>
                        {editing && (
                            <div className="grid gap-1.5">
                                <Label htmlFor="dsr-resolution">{t("fieldResolution")}</Label>
                                <Textarea
                                    id="dsr-resolution"
                                    value={resolution}
                                    onChange={(e) => setResolution(e.target.value)}
                                    placeholder={t("fieldResolutionPlaceholder")}
                                    rows={2}
                                    maxLength={10000}
                                    disabled={locked}
                                />
                            </div>
                        )}
                    </div>

                    <DialogFooter className="ncd-rise" style={{ animationDelay: "270ms" }}>
                        <DialogClose asChild>
                            <Button type="button" variant="outline" disabled={locked}>
                                {t("cancel")}
                            </Button>
                        </DialogClose>
                        <Button
                            type="submit"
                            variant="brand"
                            disabled={locked}
                            className="min-w-24 shadow-sm transition hover:shadow-md"
                        >
                            {editing ? t("saveChanges") : t("logRequest")}
                        </Button>
                    </DialogFooter>
                </form>
            </div>
        </>
    );
}

function DateTimeField({
    id,
    label,
    hint,
    value,
    onChange,
    disabled,
}: {
    id: string;
    label: string;
    hint?: string;
    value: string;
    onChange: (value: string) => void;
    disabled: boolean;
}) {
    return (
        <div className="grid gap-1.5">
            <Label htmlFor={id}>{label}</Label>
            <input
                id={id}
                type="datetime-local"
                value={value}
                onChange={(e) => onChange(e.target.value)}
                className={cn(fieldInputClass, "px-3")}
                disabled={disabled}
            />
            {hint && <p className="text-xs text-muted-foreground">{hint}</p>}
        </div>
    );
}

/**
 * Optional link to the CRM contact record the request concerns: pick one of the org's
 * workspaces the admin belongs to, then search that workspace's contacts by name.
 */
function SubjectLinkField({
    value,
    onChange,
    disabled,
}: {
    value: { workspaceId: number; personId: number; label: string } | null;
    onChange: (value: { workspaceId: number; personId: number; label: string } | null) => void;
    disabled: boolean;
}) {
    const t = useTranslations("OrgDataRequests");
    const { workspaces, activeWorkspace } = useWorkspace();
    const orgId = activeWorkspace?.orgId ?? null;
    const orgWorkspaces = workspaces.filter((ws) => ws.orgId === orgId);

    const [workspaceId, setWorkspaceId] = useState<number | null>(activeWorkspace?.id ?? null);
    const [query, setQuery] = useState("");
    const [results, setResults] = useState<Contact[]>([]);
    const [searching, setSearching] = useState(false);
    const requestSeq = useRef(0);

    useEffect(() => {
        const trimmed = query.trim();
        const seq = ++requestSeq.current;
        if (!workspaceId || trimmed.length < 2) {
            const clear = setTimeout(() => {
                if (requestSeq.current === seq) {
                    setResults([]);
                    setSearching(false);
                }
            }, 0);
            return () => clearTimeout(clear);
        }
        const timer = setTimeout(async () => {
            if (requestSeq.current === seq) setSearching(true);
            try {
                const page = await getContactsPage(
                    { q: trimmed, size: 6 },
                    { headers: { "X-Workspace-Id": String(workspaceId) } },
                );
                if (requestSeq.current === seq) setResults(page.items);
            } catch {
                if (requestSeq.current === seq) setResults([]);
            } finally {
                if (requestSeq.current === seq) setSearching(false);
            }
        }, 250);
        return () => clearTimeout(timer);
    }, [query, workspaceId]);

    if (value) {
        return (
            <div className="ncd-rise grid gap-1.5" style={{ animationDelay: "180ms" }}>
                <Label>{t("fieldSubjectLink")}</Label>
                <div className="flex items-center justify-between gap-2 rounded-md border border-border bg-muted/40 px-3 py-2">
                    <span className="truncate text-sm text-foreground">{value.label}</span>
                    <button
                        type="button"
                        onClick={() => onChange(null)}
                        disabled={disabled}
                        className="flex size-6 shrink-0 items-center justify-center rounded-full text-muted-foreground transition-colors hover:bg-muted hover:text-foreground"
                        aria-label={t("unlinkContact")}
                    >
                        <XMarkIcon className="size-4" />
                    </button>
                </div>
            </div>
        );
    }

    return (
        <div className="ncd-rise grid gap-1.5" style={{ animationDelay: "180ms" }}>
            <Label htmlFor="dsr-link-search">{t("fieldSubjectLink")}</Label>
            <div className="grid grid-cols-[minmax(0,1fr)_minmax(0,2fr)] gap-2">
                <Select
                    value={workspaceId != null ? String(workspaceId) : undefined}
                    onValueChange={(next) => {
                        setWorkspaceId(Number(next));
                        setResults([]);
                    }}
                    disabled={disabled}
                >
                    <SelectTrigger className="w-full" aria-label={t("fieldWorkspace")}>
                        <SelectValue placeholder={t("fieldWorkspace")} />
                    </SelectTrigger>
                    <SelectContent>
                        {orgWorkspaces.map((ws) => (
                            <SelectItem key={ws.id} value={String(ws.id)}>
                                {ws.name}
                            </SelectItem>
                        ))}
                    </SelectContent>
                </Select>
                <div className="group relative">
                    <MagnifyingGlassIcon className={fieldLeadIconClass} />
                    <input
                        id="dsr-link-search"
                        type="text"
                        value={query}
                        onChange={(e) => setQuery(e.target.value)}
                        className={cn(fieldInputClass, "pl-9 pr-3")}
                        placeholder={t("searchContactsPlaceholder")}
                        disabled={disabled || workspaceId == null}
                        autoComplete="off"
                    />
                </div>
            </div>
            {query.trim().length >= 2 && (
                <div className="overflow-hidden rounded-md border border-border">
                    {searching && results.length === 0 ? (
                        <p className="px-3 py-2 text-sm text-muted-foreground">{t("searching")}</p>
                    ) : results.length === 0 ? (
                        <p className="px-3 py-2 text-sm text-muted-foreground">{t("noContactsFound")}</p>
                    ) : (
                        <ul className="divide-y divide-border">
                            {results.map((contact) => (
                                <li key={contact.id}>
                                    <button
                                        type="button"
                                        onClick={() => {
                                            if (workspaceId == null) return;
                                            onChange({
                                                workspaceId,
                                                personId: contact.id,
                                                label: contact.email ? `${contact.name} · ${contact.email}` : contact.name,
                                            });
                                            setQuery("");
                                        }}
                                        className="flex w-full items-center justify-between gap-2 px-3 py-2 text-left text-sm transition-colors hover:bg-muted/70"
                                    >
                                        <span className="truncate font-medium text-foreground">{contact.name}</span>
                                        {contact.email && (
                                            <span className="truncate text-xs text-muted-foreground">{contact.email}</span>
                                        )}
                                    </button>
                                </li>
                            ))}
                        </ul>
                    )}
                </div>
            )}
            <p className="text-xs text-muted-foreground">{t("subjectLinkHint")}</p>
        </div>
    );
}
