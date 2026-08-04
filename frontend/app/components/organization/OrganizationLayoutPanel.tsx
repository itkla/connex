"use client";

import { useState } from "react";
import { useTranslations } from "next-intl";
import {
    ArrowTopRightOnSquareIcon,
    BuildingOffice2Icon,
    ChevronDownIcon,
    LockClosedIcon,
    Squares2X2Icon,
    TableCellsIcon,
    UserGroupIcon,
} from "@heroicons/react/24/outline";
import { Loader2Icon } from "lucide-react";

import type {
    OrganizationIdentity,
    OrganizationLayoutAuthorityMember,
    OrganizationLayoutWorkspace,
    OrganizationLayoutWorkspaceMember,
} from "@/app/lib/types";
import { Avatar, AvatarFallback, AvatarImage } from "@/components/ui/avatar";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Collapsible, CollapsibleContent, CollapsibleTrigger } from "@/components/ui/collapsible";

type OrganizationLayoutPanelProps = {
    organization: OrganizationIdentity;
    authorityMemberships: OrganizationLayoutAuthorityMember[];
    workspaces: OrganizationLayoutWorkspace[];
    activeWorkspaceId: number | null;
    hasMore: boolean;
    loadingMore: boolean;
    switching: boolean;
    onLoadMore: () => void;
    onNavigate: (workspaceId: number, href: string) => void;
};

function initial(name: string): string {
    return name.trim().charAt(0).toUpperCase() || "?";
}

function MemberAvatar({ member }: { member: { displayName: string; profilePictureUrl?: string | null } }) {
    return (
        <Avatar size="sm" aria-hidden="true">
            {member.profilePictureUrl ? <AvatarImage src={member.profilePictureUrl} alt="" /> : null}
            <AvatarFallback>{initial(member.displayName)}</AvatarFallback>
        </Avatar>
    );
}

function WorkspaceMemberList({
    workspace,
    switching,
    onNavigate,
}: {
    workspace: OrganizationLayoutWorkspace;
    switching: boolean;
    onNavigate: (workspaceId: number, href: string) => void;
}) {
    const t = useTranslations("OrgOverview");
    const roleLabels: Record<string, string> = {
        owner: t("roleOwner"),
        admin: t("roleAdmin"),
        member: t("roleMember"),
    };

    if (!workspace.rosterVisible) {
        return (
            <div className="flex items-start gap-3 rounded-xl bg-muted/50 px-4 py-3 text-sm text-muted-foreground">
                <LockClosedIcon className="mt-0.5 size-4 shrink-0" />
                <p>{t("rosterRestricted")}</p>
            </div>
        );
    }
    if (workspace.memberships.length === 0) {
        return <p className="px-1 py-2 text-sm text-muted-foreground">{t("rosterEmpty")}</p>;
    }
    return (
        <div className="space-y-2">
            <ul className="divide-y divide-border/70" aria-label={t("workspaceMembers", { name: workspace.name })}>
                {workspace.memberships.map((member) => (
                    <li key={member.userId}>
                        {member.status === "active" ? (
                            <button
                                type="button"
                                disabled={switching}
                                className="group flex w-full items-center gap-3 rounded-lg px-1 py-2 text-left outline-none transition-colors motion-reduce:transition-none hover:bg-muted/60 focus-visible:ring-2 focus-visible:ring-brand/40 disabled:opacity-50"
                                onClick={() => onNavigate(workspace.id, `/users/${member.userId}`)}
                            >
                                <MemberAvatar member={member} />
                                <span className="min-w-0 flex-1 truncate text-sm font-medium text-foreground">
                                    {member.displayName}
                                </span>
                                <span className="text-xs text-muted-foreground">
                                    {roleLabels[member.role] ?? member.role}
                                </span>
                                <ArrowTopRightOnSquareIcon className="size-4 text-muted-foreground opacity-0 transition-opacity motion-reduce:transition-none group-hover:opacity-100 group-focus-visible:opacity-100" />
                            </button>
                        ) : (
                            <div className="flex w-full items-center gap-3 px-1 py-2 text-muted-foreground">
                                <MemberAvatar member={member} />
                                <span className="min-w-0 flex-1 truncate text-sm font-medium">
                                    {member.displayName}
                                </span>
                                <span className="text-xs">{roleLabels[member.role] ?? member.role}</span>
                                <Badge variant="outline">{t("pendingMembership")}</Badge>
                            </div>
                        )}
                    </li>
                ))}
            </ul>
            {workspace.membershipsTruncated ? (
                <p className="text-xs text-muted-foreground">{t("rosterTruncated")}</p>
            ) : null}
        </div>
    );
}

function StructureView({
    workspaces,
    activeWorkspaceId,
    switching,
    onNavigate,
}: Pick<
    OrganizationLayoutPanelProps,
    "workspaces" | "activeWorkspaceId" | "switching" | "onNavigate"
>) {
    const t = useTranslations("OrgOverview");
    if (workspaces.length === 0) {
        return <p className="px-6 py-12 text-center text-sm text-muted-foreground">{t("workspacesEmpty")}</p>;
    }
    return (
        <ol className="grid gap-4 p-4 lg:grid-cols-2" aria-label={t("structureLabel")}>
            {workspaces.map((workspace, index) => (
                <li key={workspace.id} className="relative">
                    <Collapsible defaultOpen={index === 0 || workspace.id === activeWorkspaceId}>
                        <div className="overflow-hidden rounded-xl border border-border bg-background">
                            <div className="flex items-start gap-3 p-4">
                                <span aria-hidden className="grid size-10 shrink-0 place-items-center rounded-xl bg-brand-light text-brand-dark">
                                    <BuildingOffice2Icon className="size-5" />
                                </span>
                                <div className="min-w-0 flex-1">
                                    <div className="flex flex-wrap items-center gap-2">
                                        <p className="truncate font-semibold text-foreground">{workspace.name}</p>
                                        {workspace.id === activeWorkspaceId ? (
                                            <Badge variant="secondary">{t("currentWorkspace")}</Badge>
                                        ) : null}
                                    </div>
                                    <p className="truncate font-mono text-xs text-muted-foreground">{workspace.slug}</p>
                                    <p className="mt-1 text-xs text-muted-foreground">
                                        {workspace.timezone ?? t("accountTimezone")}
                                    </p>
                                </div>
                                <CollapsibleTrigger asChild>
                                    <Button className="group" variant="ghost" size="icon-sm" aria-label={t("toggleRoster", { name: workspace.name })}>
                                        <ChevronDownIcon className="size-4 transition-transform motion-reduce:transition-none group-data-[state=open]:rotate-180" />
                                    </Button>
                                </CollapsibleTrigger>
                            </div>
                            <div className="flex items-center justify-between gap-3 border-t border-border bg-muted/20 px-4 py-3">
                                <div className="flex items-center gap-2 text-xs text-muted-foreground">
                                    {workspace.rosterVisible ? (
                                        <>
                                            <UserGroupIcon className="size-4" />
                                            <span>{t("memberCount", {
                                                count: workspace.memberships.filter(({ status }) => status === "active").length,
                                            })}</span>
                                        </>
                                    ) : (
                                        <>
                                            <LockClosedIcon className="size-4" />
                                            <span>{t("restricted")}</span>
                                        </>
                                    )}
                                </div>
                                {workspace.rosterVisible ? (
                                    <Button
                                        variant="ghost"
                                        size="sm"
                                        disabled={switching}
                                        onClick={() => onNavigate(workspace.id, "/dashboard")}
                                    >
                                        {t("openWorkspace")}
                                        <ArrowTopRightOnSquareIcon className="size-4" />
                                    </Button>
                                ) : null}
                            </div>
                            <CollapsibleContent>
                                <div className="border-t border-border px-4 py-3">
                                    <WorkspaceMemberList
                                        workspace={workspace}
                                        switching={switching}
                                        onNavigate={onNavigate}
                                    />
                                </div>
                            </CollapsibleContent>
                        </div>
                    </Collapsible>
                </li>
            ))}
        </ol>
    );
}

function MemberLinks({
    workspace,
    switching,
    onNavigate,
}: {
    workspace: OrganizationLayoutWorkspace;
    switching: boolean;
    onNavigate: (workspaceId: number, href: string) => void;
}) {
    const t = useTranslations("OrgOverview");
    if (!workspace.rosterVisible) {
        return <span className="inline-flex items-center gap-1.5 text-muted-foreground"><LockClosedIcon className="size-4" />{t("restricted")}</span>;
    }
    if (workspace.memberships.length === 0) return <span className="text-muted-foreground">{t("rosterEmpty")}</span>;
    return (
        <ul className="space-y-1.5">
            {workspace.memberships.map((member: OrganizationLayoutWorkspaceMember) => (
                <li key={member.userId}>
                    {member.status === "active" ? (
                        <button
                            type="button"
                            disabled={switching}
                            className="inline-flex items-center gap-2 rounded-md text-left text-sm font-medium text-foreground outline-none hover:text-brand focus-visible:ring-2 focus-visible:ring-brand/40 disabled:opacity-50"
                            onClick={() => onNavigate(workspace.id, `/users/${member.userId}`)}
                        >
                            <MemberAvatar member={member} />
                            {member.displayName}
                        </button>
                    ) : (
                        <span className="inline-flex items-center gap-2 text-sm font-medium text-muted-foreground">
                            <MemberAvatar member={member} />
                            {member.displayName}
                            <Badge variant="outline">{t("pendingMembership")}</Badge>
                        </span>
                    )}
                </li>
            ))}
        </ul>
    );
}

function TableView({
    workspaces,
    activeWorkspaceId,
    switching,
    onNavigate,
}: Pick<
    OrganizationLayoutPanelProps,
    "workspaces" | "activeWorkspaceId" | "switching" | "onNavigate"
>) {
    const t = useTranslations("OrgOverview");
    return (
        <div className="overflow-x-auto">
            <table className="w-full min-w-2xl border-collapse text-left text-sm">
                <caption className="sr-only">{t("tableCaption")}</caption>
                <thead>
                    <tr className="border-b border-border bg-muted/30 text-xs text-muted-foreground">
                        <th scope="col" className="px-4 py-3 font-medium">{t("workspaceColumn")}</th>
                        <th scope="col" className="px-4 py-3 font-medium">{t("timezoneColumn")}</th>
                        <th scope="col" className="px-4 py-3 font-medium">{t("membersColumn")}</th>
                    </tr>
                </thead>
                <tbody className="divide-y divide-border">
                    {workspaces.map((workspace) => (
                        <tr key={workspace.id} className="align-top">
                            <th scope="row" className="px-4 py-4 font-normal">
                                {workspace.rosterVisible ? (
                                    <button
                                        type="button"
                                        disabled={switching}
                                        className="rounded-md text-left font-semibold text-foreground outline-none hover:text-brand focus-visible:ring-2 focus-visible:ring-brand/40 disabled:opacity-50"
                                        onClick={() => onNavigate(workspace.id, "/dashboard")}
                                    >
                                        {workspace.name}
                                    </button>
                                ) : (
                                    <span className="font-semibold text-foreground">{workspace.name}</span>
                                )}
                                {workspace.id === activeWorkspaceId ? (
                                    <Badge variant="secondary" className="ml-2">{t("currentWorkspace")}</Badge>
                                ) : null}
                                <p className="mt-1 font-mono text-xs text-muted-foreground">{workspace.slug}</p>
                            </th>
                            <td className="px-4 py-4 text-muted-foreground">
                                {workspace.timezone ?? t("accountTimezone")}
                            </td>
                            <td className="px-4 py-4">
                                <MemberLinks workspace={workspace} switching={switching} onNavigate={onNavigate} />
                                {workspace.membershipsTruncated ? (
                                    <p className="mt-2 text-xs text-muted-foreground">{t("rosterTruncated")}</p>
                                ) : null}
                            </td>
                        </tr>
                    ))}
                    {workspaces.length === 0 ? (
                        <tr><td colSpan={3} className="px-6 py-12 text-center text-muted-foreground">{t("workspacesEmpty")}</td></tr>
                    ) : null}
                </tbody>
            </table>
        </div>
    );
}

export default function OrganizationLayoutPanel({
    organization,
    authorityMemberships,
    workspaces,
    activeWorkspaceId,
    hasMore,
    loadingMore,
    switching,
    onLoadMore,
    onNavigate,
}: OrganizationLayoutPanelProps) {
    const t = useTranslations("OrgOverview");
    const [view, setView] = useState<"structure" | "table">("structure");

    return (
        <div className="overflow-hidden rounded-2xl border border-border bg-card">
            <div className="flex flex-wrap items-center justify-between gap-4 border-b border-border p-5">
                <div className="flex min-w-0 items-center gap-3">
                    <span aria-hidden className="grid size-11 shrink-0 place-items-center rounded-xl bg-foreground text-background">
                        <Squares2X2Icon className="size-5" />
                    </span>
                    <div className="min-w-0">
                        <p className="truncate text-base font-semibold text-foreground">{organization.name}</p>
                        <p className="truncate font-mono text-xs text-muted-foreground">{organization.slug}</p>
                    </div>
                </div>
                <div className="inline-flex rounded-lg bg-muted p-1" role="group" aria-label={t("viewLabel")}>
                    <Button
                        type="button"
                        size="sm"
                        variant={view === "structure" ? "secondary" : "ghost"}
                        aria-pressed={view === "structure"}
                        onClick={() => setView("structure")}
                    >
                        <Squares2X2Icon className="size-4" />
                        {t("structureView")}
                    </Button>
                    <Button
                        type="button"
                        size="sm"
                        variant={view === "table" ? "secondary" : "ghost"}
                        aria-pressed={view === "table"}
                        onClick={() => setView("table")}
                    >
                        <TableCellsIcon className="size-4" />
                        {t("tableView")}
                    </Button>
                </div>
            </div>

            <div className="border-b border-border bg-muted/20 px-5 py-4">
                <div className="flex flex-wrap items-center gap-3">
                    <div className="flex items-center gap-2 text-sm font-medium text-foreground">
                        <UserGroupIcon className="size-4 text-muted-foreground" />
                        {t("administrators")}
                    </div>
                    <ul className="flex flex-wrap gap-2">
                        {authorityMemberships.map((member) => (
                            <li key={member.userId} className="inline-flex items-center gap-2 rounded-full border border-border bg-background py-1 pr-2 pl-1">
                                <MemberAvatar member={member} />
                                <span className="text-xs font-medium text-foreground">{member.displayName}</span>
                                <span className="text-xs text-muted-foreground">
                                    {member.orgRole === "owner" ? t("roleOwner") : t("roleAdmin")}
                                </span>
                            </li>
                        ))}
                    </ul>
                </div>
            </div>

            {view === "structure" ? (
                <StructureView
                    workspaces={workspaces}
                    activeWorkspaceId={activeWorkspaceId}
                    switching={switching}
                    onNavigate={onNavigate}
                />
            ) : (
                <TableView
                    workspaces={workspaces}
                    activeWorkspaceId={activeWorkspaceId}
                    switching={switching}
                    onNavigate={onNavigate}
                />
            )}

            {hasMore ? (
                <div className="flex justify-center border-t border-border px-5 py-4">
                    <Button variant="outline" disabled={loadingMore} onClick={onLoadMore}>
                        {loadingMore ? <Loader2Icon className="size-4 animate-spin" /> : null}
                        {loadingMore ? t("loadingMore") : t("loadMore")}
                    </Button>
                </div>
            ) : null}
        </div>
    );
}
