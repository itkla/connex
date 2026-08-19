"use client";

import { useState } from "react";
import { useTranslations } from "next-intl";
import {
    ArrowDownTrayIcon,
    ExclamationTriangleIcon,
    LockClosedIcon,
    TrashIcon,
} from "@heroicons/react/24/outline";
import { Loader2Icon } from "lucide-react";

import { usePasskeyStepUpErrorHandler } from "@/app/hooks/usePasskeyStepUpError";
import {
    ApiError,
    requestWorkspaceTenantExport,
    teardownOrganization,
    teardownOrganizationWorkspace,
} from "@/app/lib/api";
import { organizationLifecycleAccess } from "@/app/lib/organizationLifecycleAccess";
import { toastError, toastSuccess } from "@/app/lib/toast";
import type {
    OrganizationIdentity,
    OrganizationLayoutWorkspace,
    OrgRole,
    TenantExportGrant,
} from "@/app/lib/types";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import {
    ResponsiveDialog,
    ResponsiveDialogClose,
    ResponsiveDialogContent,
    ResponsiveDialogDescription,
    ResponsiveDialogFooter,
    ResponsiveDialogHeader,
    ResponsiveDialogTitle,
} from "@/components/ui/responsive-dialog";

const OPEN_SUBJECT_REQUEST_CODE = "TENANT_TEARDOWN_OPEN_DATA_SUBJECT_REQUEST";

type TeardownTarget =
    | { kind: "workspace"; id: number; name: string; slug: string }
    | { kind: "organization"; id: number; name: string; slug: string };

type LifecycleAccess = ReturnType<typeof organizationLifecycleAccess>;

function LifecycleAuthoritySection({ orgRole }: { orgRole: OrgRole }) {
    const t = useTranslations("OrgDataLifecycle");

    return (
        <div className="flex flex-wrap items-start justify-between gap-3">
            <div className="space-y-1">
                <p className="text-sm font-medium text-foreground">{t("authorityTitle")}</p>
                <p className="text-sm text-muted-foreground">
                    {orgRole === "owner" ? t("ownerAuthority") : t("adminAuthority")}
                </p>
            </div>
            <Badge variant="outline">
                {orgRole === "owner" ? t("roleOwner") : t("roleAdmin")}
            </Badge>
        </div>
    );
}

function WorkspaceTeardownSection({
    workspace,
    onOpenTeardown,
}: {
    workspace: OrganizationLayoutWorkspace;
    onOpenTeardown: (target: TeardownTarget) => void;
}) {
    const t = useTranslations("OrgDataLifecycle");

    return (
        <Button
            type="button"
            variant="destructive"
            onClick={() => onOpenTeardown({
                kind: "workspace",
                id: workspace.id,
                name: workspace.name,
                slug: workspace.slug,
            })}
        >
            <TrashIcon className="size-4" />
            {t("deleteWorkspace")}
        </Button>
    );
}

function ExportSection({
    workspaces,
    access,
    exportingWorkspaceId,
    exportGrants,
    hasMore,
    loadingMore,
    onExport,
    onLoadMore,
    onOpenTeardown,
}: {
    workspaces: OrganizationLayoutWorkspace[];
    access: LifecycleAccess;
    exportingWorkspaceId: number | null;
    exportGrants: ReadonlyMap<number, TenantExportGrant>;
    hasMore: boolean;
    loadingMore: boolean;
    onExport: (workspace: OrganizationLayoutWorkspace) => Promise<void>;
    onLoadMore: () => void;
    onOpenTeardown: (target: TeardownTarget) => void;
}) {
    const t = useTranslations("OrgDataLifecycle");

    return (
        <>
            <Alert>
                <ExclamationTriangleIcon aria-hidden />
                <AlertTitle>{t("plaintextTitle")}</AlertTitle>
                <AlertDescription>{t("plaintextDescription")}</AlertDescription>
            </Alert>

            <div className="divide-y divide-border rounded-xl border border-border">
                {workspaces.length === 0 ? (
                    <p className="px-4 py-8 text-center text-sm text-muted-foreground">
                        {t("noWorkspaces")}
                    </p>
                ) : workspaces.map((workspace) => (
                    <div
                        key={workspace.id}
                        className="flex flex-col gap-4 px-4 py-4 sm:flex-row sm:items-center sm:justify-between"
                    >
                        <div className="min-w-0">
                            <p className="truncate text-sm font-medium text-foreground">
                                {workspace.name}
                            </p>
                            <p className="truncate font-mono text-xs text-muted-foreground">
                                {workspace.slug}
                            </p>
                        </div>
                        <div className="flex shrink-0 flex-wrap gap-2">
                            {exportGrants.has(workspace.id) ? (
                                <>
                                    <Button asChild variant="outline">
                                        <a
                                            href={exportGrants.get(workspace.id)?.downloadPath}
                                            target="_blank"
                                            rel="noopener noreferrer"
                                        >
                                            <ArrowDownTrayIcon className="size-4" />
                                            {t("downloadExport")}
                                        </a>
                                    </Button>
                                    <Button
                                        type="button"
                                        variant="ghost"
                                        disabled={exportingWorkspaceId !== null}
                                        onClick={() => void onExport(workspace)}
                                    >
                                        {exportingWorkspaceId === workspace.id ? (
                                            <Loader2Icon className="size-4 animate-spin" />
                                        ) : null}
                                        {exportingWorkspaceId === workspace.id
                                            ? t("preparingExport")
                                            : t("renewExportGrant")}
                                    </Button>
                                </>
                            ) : (
                                <Button
                                    type="button"
                                    variant="outline"
                                    disabled={!access.canExport || exportingWorkspaceId !== null}
                                    onClick={() => void onExport(workspace)}
                                >
                                    {exportingWorkspaceId === workspace.id ? (
                                        <Loader2Icon className="size-4 animate-spin" />
                                    ) : (
                                        <ArrowDownTrayIcon className="size-4" />
                                    )}
                                    {exportingWorkspaceId === workspace.id
                                        ? t("preparingExport")
                                        : t("exportWorkspace")}
                                </Button>
                            )}
                            {access.canTeardown ? (
                                <WorkspaceTeardownSection
                                    workspace={workspace}
                                    onOpenTeardown={onOpenTeardown}
                                />
                            ) : null}
                        </div>
                    </div>
                ))}
            </div>
            {hasMore ? (
                <Button
                    type="button"
                    variant="outline"
                    className="justify-self-start"
                    disabled={loadingMore}
                    onClick={onLoadMore}
                >
                    {loadingMore ? <Loader2Icon className="size-4 animate-spin" /> : null}
                    {loadingMore ? t("loadingMore") : t("loadMore")}
                </Button>
            ) : null}
        </>
    );
}

function OrganizationTeardownSection({
    organization,
    access,
    onOpenTeardown,
}: {
    organization: OrganizationIdentity;
    access: LifecycleAccess;
    onOpenTeardown: (target: TeardownTarget) => void;
}) {
    const t = useTranslations("OrgDataLifecycle");

    return access.canTeardown ? (
        <div className="flex flex-col gap-4 rounded-xl border border-destructive/25 p-4 sm:flex-row sm:items-center sm:justify-between">
            <div className="space-y-1">
                <p className="text-sm font-medium text-foreground">
                    {t("organizationTeardownTitle")}
                </p>
                <p className="max-w-prose text-sm text-muted-foreground">
                    {t("organizationTeardownDescription")}
                </p>
            </div>
            <Button
                type="button"
                variant="destructive"
                className="shrink-0"
                onClick={() => onOpenTeardown({
                    kind: "organization",
                    id: organization.id,
                    name: organization.name,
                    slug: organization.slug,
                })}
            >
                <TrashIcon className="size-4" />
                {t("deleteOrganization")}
            </Button>
        </div>
    ) : (
        <Alert>
            <LockClosedIcon aria-hidden />
            <AlertTitle>{t("ownerOnlyTitle")}</AlertTitle>
            <AlertDescription>{t("ownerOnlyDescription")}</AlertDescription>
        </Alert>
    );
}

function TeardownDialog({
    organization,
    teardownTarget,
    confirmation,
    teardownFailure,
    tearingDown,
    exactConfirmation,
    onOpenChange,
    onConfirmationChange,
    onTeardown,
}: {
    organization: OrganizationIdentity;
    teardownTarget: TeardownTarget | null;
    confirmation: string;
    teardownFailure: string;
    tearingDown: boolean;
    exactConfirmation: boolean;
    onOpenChange: (open: boolean) => void;
    onConfirmationChange: (confirmation: string) => void;
    onTeardown: () => Promise<void>;
}) {
    const t = useTranslations("OrgDataLifecycle");

    return (
        <ResponsiveDialog open={teardownTarget !== null} onOpenChange={onOpenChange}>
            <ResponsiveDialogContent className="sm:max-w-lg" showCloseButton={!tearingDown}>
                <ResponsiveDialogHeader className="px-4 pt-4 sm:px-0 sm:pt-0">
                    <ResponsiveDialogTitle>
                        {teardownTarget?.kind === "workspace"
                            ? t("confirmWorkspaceTitle", { name: teardownTarget.name })
                            : t("confirmOrganizationTitle", {
                                name: teardownTarget?.name ?? organization.name,
                            })}
                    </ResponsiveDialogTitle>
                    <ResponsiveDialogDescription>
                        {teardownTarget?.kind === "workspace"
                            ? t("confirmWorkspaceDescription")
                            : t("confirmOrganizationDescription")}
                    </ResponsiveDialogDescription>
                </ResponsiveDialogHeader>

                <div className="grid gap-4 px-4 py-4 sm:px-0">
                    <Alert variant="destructive">
                        <ExclamationTriangleIcon aria-hidden />
                        <AlertTitle>{t("irreversibleTitle")}</AlertTitle>
                        <AlertDescription>
                            {teardownTarget?.kind === "workspace"
                                ? t("workspaceRetention")
                                : t("organizationRetention")}
                        </AlertDescription>
                    </Alert>

                    <div className="grid gap-2">
                        <Label htmlFor="tenant-teardown-confirmation">
                            {t("confirmationLabel", {
                                id: teardownTarget?.slug ?? "",
                            })}
                        </Label>
                        <Input
                            id="tenant-teardown-confirmation"
                            value={confirmation}
                            disabled={tearingDown}
                            autoComplete="off"
                            spellCheck={false}
                            className="font-mono"
                            aria-invalid={confirmation.length > 0 && !exactConfirmation}
                            onChange={(event) => onConfirmationChange(event.target.value)}
                        />
                        <p className="text-xs text-muted-foreground">
                            {t("confirmationHint")}
                        </p>
                    </div>

                    {teardownFailure ? (
                        <Alert variant="destructive" aria-live="polite">
                            <ExclamationTriangleIcon aria-hidden />
                            <AlertTitle>{t("refusedTitle")}</AlertTitle>
                            <AlertDescription>{teardownFailure}</AlertDescription>
                        </Alert>
                    ) : null}
                </div>

                <ResponsiveDialogFooter className="px-4 pb-4 sm:px-0 sm:pb-0">
                    <ResponsiveDialogClose asChild>
                        <Button type="button" variant="outline" disabled={tearingDown}>
                            {t("cancel")}
                        </Button>
                    </ResponsiveDialogClose>
                    <Button
                        type="button"
                        variant="destructive"
                        disabled={!exactConfirmation || tearingDown}
                        onClick={() => void onTeardown()}
                    >
                        {tearingDown ? <Loader2Icon className="size-4 animate-spin" /> : null}
                        {tearingDown ? t("deleting") : t("deletePermanently")}
                    </Button>
                </ResponsiveDialogFooter>
            </ResponsiveDialogContent>
        </ResponsiveDialog>
    );
}

/** Organization-admin export controls and organization-owner teardown controls. */
export default function OrganizationLifecyclePanel({
    organization,
    workspaces,
    orgRole,
    hasMore,
    loadingMore,
    onLoadMore,
}: {
    organization: OrganizationIdentity;
    workspaces: OrganizationLayoutWorkspace[];
    orgRole: OrgRole;
    hasMore: boolean;
    loadingMore: boolean;
    onLoadMore: () => void;
}) {
    const t = useTranslations("OrgDataLifecycle");
    const handlePasskeyStepUpError = usePasskeyStepUpErrorHandler();
    const access = organizationLifecycleAccess(orgRole);
    const [exportingWorkspaceId, setExportingWorkspaceId] = useState<number | null>(null);
    const [exportGrants, setExportGrants] = useState(
        () => new Map<number, TenantExportGrant>(),
    );
    const [teardownTarget, setTeardownTarget] = useState<TeardownTarget | null>(null);
    const [confirmation, setConfirmation] = useState("");
    const [teardownFailure, setTeardownFailure] = useState("");
    const [tearingDown, setTearingDown] = useState(false);

    async function exportWorkspace(workspace: OrganizationLayoutWorkspace) {
        setExportingWorkspaceId(workspace.id);
        try {
            const grant = await requestWorkspaceTenantExport(
                organization.id,
                workspace.id,
            );
            setExportGrants((current) => {
                const next = new Map(current);
                next.set(workspace.id, grant);
                return next;
            });
        } catch (error) {
            if (!handlePasskeyStepUpError(error)) {
                toastError(error instanceof Error ? error.message : t("exportFailed"));
            }
        } finally {
            setExportingWorkspaceId(null);
        }
    }

    function openTeardown(target: TeardownTarget) {
        setTeardownTarget(target);
        setConfirmation("");
        setTeardownFailure("");
    }

    function changeDialogOpen(open: boolean) {
        if (tearingDown || open) return;
        setTeardownTarget(null);
        setConfirmation("");
        setTeardownFailure("");
    }

    async function teardown() {
        if (!teardownTarget || confirmation !== teardownTarget.slug) return;
        setTearingDown(true);
        setTeardownFailure("");
        try {
            if (teardownTarget.kind === "workspace") {
                await teardownOrganizationWorkspace(
                    organization.id,
                    teardownTarget.id,
                    confirmation,
                );
                toastSuccess(t("workspaceDeleted", { name: teardownTarget.name }));
            } else {
                await teardownOrganization(organization.id, confirmation);
                toastSuccess(t("organizationDeleted", { name: teardownTarget.name }));
            }
            window.location.assign("/dashboard");
        } catch (error) {
            if (handlePasskeyStepUpError(error)) return;
            if (error instanceof ApiError && error.code === OPEN_SUBJECT_REQUEST_CODE) {
                setTeardownFailure(
                    teardownTarget.kind === "workspace"
                        ? t("workspaceOpenObligation")
                        : t("organizationOpenObligation"),
                );
            } else {
                setTeardownFailure(
                    error instanceof Error ? error.message : t("teardownFailed"),
                );
            }
        } finally {
            setTearingDown(false);
        }
    }

    const exactConfirmation = teardownTarget !== null
        && confirmation === teardownTarget.slug;

    return (
        <div className="overflow-hidden rounded-2xl border border-border bg-card">
            <div className="grid gap-5 p-6">
                <LifecycleAuthoritySection orgRole={orgRole} />
                <ExportSection
                    workspaces={workspaces}
                    access={access}
                    exportingWorkspaceId={exportingWorkspaceId}
                    exportGrants={exportGrants}
                    hasMore={hasMore}
                    loadingMore={loadingMore}
                    onExport={exportWorkspace}
                    onLoadMore={onLoadMore}
                    onOpenTeardown={openTeardown}
                />
                <OrganizationTeardownSection
                    organization={organization}
                    access={access}
                    onOpenTeardown={openTeardown}
                />
            </div>

            <TeardownDialog
                organization={organization}
                teardownTarget={teardownTarget}
                confirmation={confirmation}
                teardownFailure={teardownFailure}
                tearingDown={tearingDown}
                exactConfirmation={exactConfirmation}
                onOpenChange={changeDialogOpen}
                onConfirmationChange={(value) => {
                    setConfirmation(value);
                    setTeardownFailure("");
                }}
                onTeardown={teardown}
            />
        </div>
    );
}
