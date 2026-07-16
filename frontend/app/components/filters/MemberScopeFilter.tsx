"use client";

import { useTranslations } from "next-intl";
import { ChevronDownIcon } from "@heroicons/react/20/solid";
import { XMarkIcon, UserIcon, UserMinusIcon } from "@heroicons/react/24/outline";
import {
    DropdownMenu,
    DropdownMenuTrigger,
    DropdownMenuContent,
    DropdownMenuCheckboxItem,
    DropdownMenuLabel,
    DropdownMenuSeparator,
    DropdownMenuItem,
} from "@/components/ui/dropdown-menu";
import { Avatar, AvatarFallback, AvatarImage } from "@/components/ui/avatar";
import { cn } from "@/lib/utils";
import type { WorkspaceMember } from "@/app/lib/types";
import { pillClass } from "./FilterPill";

/** Sentinel value meaning "scoped to the current user"; resolved server-side, never a raw id. */
export const MEMBER_SCOPE_ME = "me";
/** Sentinel value meaning "records with no owner". */
export const MEMBER_SCOPE_UNASSIGNED = "__empty__";
/** Maximum selectable members, mirroring the server-side memberIds cap. */
export const MEMBER_SCOPE_MAX_MEMBERS = 50;

/**
 * Splits a member-scope value list into its exclusive interpretation: the "me" sentinel,
 * the unassigned sentinel, or a set of concrete member ids. Sentinels win over ids so a
 * malformed mixed list (e.g. hand-edited URL) degrades to a single well-defined mode.
 */
export function interpretMemberScope(values: string[] | undefined): {
    mode: "all" | "me" | "unassigned" | "members";
    memberIds: number[];
} {
    if (!values?.length) return { mode: "all", memberIds: [] };
    if (values.includes(MEMBER_SCOPE_ME)) return { mode: "me", memberIds: [] };
    if (values.includes(MEMBER_SCOPE_UNASSIGNED)) return { mode: "unassigned", memberIds: [] };
    const memberIds = Array.from(new Set(values.flatMap((value) => {
        const id = Number(value);
        return Number.isInteger(id) && id > 0 ? [id] : [];
    })));
    return memberIds.length > 0 ? { mode: "members", memberIds } : { mode: "all", memberIds: [] };
}

function MemberRow({ member }: { member: WorkspaceMember }) {
    return (
        <span className="flex min-w-0 items-center gap-2">
            <Avatar size="sm">
                {member.profilePictureUrl && <AvatarImage src={member.profilePictureUrl} alt="" />}
                <AvatarFallback className="bg-brand-light text-[10px] font-medium text-brand-dark">
                    {member.displayName.slice(0, 1).toUpperCase()}
                </AvatarFallback>
            </Avatar>
            <span className="truncate">{member.displayName}</span>
        </span>
    );
}

/**
 * The canonical owner/member scope control for record browsers: one pill in the FilterPill
 * family offering the exclusive scopes All team (default, no values), Me, Unassigned, or a
 * multi-selection of workspace members. Selection is exposed as the string-value list used
 * by {@code FilterState} — the sentinels {@link MEMBER_SCOPE_ME} / {@link MEMBER_SCOPE_UNASSIGNED}
 * or member ids — so it persists through URLs and saved views like any other filter key.
 */
export default function MemberScopeFilter({
    values,
    onChange,
    members,
    counts,
    unassignedCount,
}: {
    values: string[] | undefined;
    onChange: (values: string[]) => void;
    members: WorkspaceMember[];
    counts?: Map<string, number>;
    unassignedCount?: number;
}) {
    const t = useTranslations("MemberScope");
    const { mode, memberIds } = interpretMemberScope(values);
    const active = mode !== "all";
    const memberById = new Map(members.map((member) => [member.id, member]));

    const pillLabel = (() => {
        if (mode === "me") return t("me");
        if (mode === "unassigned") return t("unassigned");
        if (mode === "members" && memberIds.length === 1) {
            return memberById.get(memberIds[0])?.displayName ?? t("label");
        }
        return t("label");
    })();

    const toggleSentinel = (sentinel: string) => {
        onChange(values?.includes(sentinel) ? [] : [sentinel]);
    };
    const toggleMember = (id: number) => {
        const next = new Set(mode === "members" ? memberIds : []);
        if (next.has(id)) next.delete(id);
        else next.add(id);
        onChange(Array.from(next, String));
    };

    return (
        <DropdownMenu>
            <DropdownMenuTrigger asChild>
                <button type="button" aria-label={t("ariaLabel")} aria-pressed={active} className={pillClass(active)}>
                    <span>{pillLabel}</span>
                    {mode === "members" && memberIds.length > 1 && (
                        <span className="grid size-4 place-items-center rounded-full bg-brand text-[10px] font-semibold leading-none text-brand-foreground tabular-nums">
                            {memberIds.length}
                        </span>
                    )}
                    <ChevronDownIcon className="size-3.5 text-muted-foreground transition-transform duration-200 ease-out group-data-[state=open]:rotate-180" />
                </button>
            </DropdownMenuTrigger>
            <DropdownMenuContent align="start" className="w-64">
                <DropdownMenuLabel>{t("label")}</DropdownMenuLabel>
                <DropdownMenuSeparator />
                <DropdownMenuCheckboxItem
                    checked={mode === "me"}
                    onSelect={(e) => {
                        e.preventDefault();
                        toggleSentinel(MEMBER_SCOPE_ME);
                    }}
                >
                    <span className="flex flex-1 items-center gap-2">
                        <UserIcon className="size-4 text-muted-foreground" />
                        {t("me")}
                    </span>
                </DropdownMenuCheckboxItem>
                <DropdownMenuCheckboxItem
                    checked={mode === "unassigned"}
                    onSelect={(e) => {
                        e.preventDefault();
                        toggleSentinel(MEMBER_SCOPE_UNASSIGNED);
                    }}
                >
                    <span className="flex flex-1 items-center justify-between gap-2">
                        <span className="flex items-center gap-2">
                            <UserMinusIcon className="size-4 text-muted-foreground" />
                            {t("unassigned")}
                        </span>
                        {typeof unassignedCount === "number" && (
                            <span className="shrink-0 text-xs tabular-nums text-muted-foreground">{unassignedCount}</span>
                        )}
                    </span>
                </DropdownMenuCheckboxItem>
                {members.length > 0 && (
                    <>
                        <DropdownMenuSeparator />
                        <div className="max-h-72 overflow-y-auto">
                            {members.map((member) => {
                                const count = counts?.get(String(member.id));
                                const checked = mode === "members" && memberIds.includes(member.id);
                                const atCap = mode === "members" && memberIds.length >= MEMBER_SCOPE_MAX_MEMBERS;
                                return (
                                    <DropdownMenuCheckboxItem
                                        key={member.id}
                                        checked={checked}
                                        disabled={!checked && atCap}
                                        onSelect={(e) => {
                                            e.preventDefault();
                                            toggleMember(member.id);
                                        }}
                                    >
                                        <span className="flex flex-1 items-center justify-between gap-2">
                                            <MemberRow member={member} />
                                            {typeof count === "number" && (
                                                <span className="shrink-0 text-xs tabular-nums text-muted-foreground">{count}</span>
                                            )}
                                        </span>
                                    </DropdownMenuCheckboxItem>
                                );
                            })}
                        </div>
                    </>
                )}
                {active && (
                    <>
                        <DropdownMenuSeparator />
                        <DropdownMenuItem
                            onSelect={() => onChange([])}
                            className={cn("text-muted-foreground")}
                        >
                            <XMarkIcon className="size-4" />
                            {t("clear")}
                        </DropdownMenuItem>
                    </>
                )}
            </DropdownMenuContent>
        </DropdownMenu>
    );
}
