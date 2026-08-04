import { headers } from "next/headers";
import { notFound, redirect } from "next/navigation";
import AccessDeniedPage from "@/app/components/AccessDeniedPage";
import { loadRecord } from "@/app/lib/recordAccess";
import Link from "next/link";
import { getLocale, getTranslations } from "next-intl/server";
import {
    BriefcaseIcon,
    CheckCircleIcon,
    UserIcon,
} from "@heroicons/react/24/outline";

import {
    getContactsFromCookie,
    getCurrentUserFromCookie,
    getDealsFromCookie,
    getTaskById,
    getUsers,
} from "@/app/lib/api";
import type { TaskStatus, User } from "@/app/lib/types";
import { formatDate } from "@/app/lib/utils";
import { CrumbLabel } from "@/app/hooks/useNavTrail";
import Rise from "@/app/components/motion/Rise";
import { PageShell } from "@/app/components/PageShell";
import NoteContent from "@/app/components/activity/notes/NoteContent";
import BacklinksPanel from "@/app/components/activity/notes/BacklinksPanel";
import { DUE_CHIP, formatDue } from "@/app/components/activity/tasks/taskDue";
import { noteSnippet } from "@/app/lib/noteText";
import { Avatar, AvatarFallback, AvatarImage } from "@/components/ui/avatar";
import { cn } from "@/lib/utils";

const STATUS_TONE: Record<TaskStatus, string> = {
    todo: "bg-muted text-muted-foreground ring-border",
    in_progress:
        "bg-sky-50 text-sky-700 ring-sky-600/20 dark:bg-sky-950/40 dark:text-sky-300 dark:ring-sky-400/20",
    done: "bg-emerald-50 text-emerald-700 ring-emerald-600/20 dark:bg-emerald-950/40 dark:text-emerald-300 dark:ring-emerald-400/20",
};

function MetaRow({ label, children }: { label: string; children: React.ReactNode }) {
    return (
        <div className="flex flex-col gap-1.5 px-6 py-4">
            <dt className="text-sm text-muted-foreground">{label}</dt>
            <dd className="text-base text-foreground">{children}</dd>
        </div>
    );
}

export default async function TaskDetailPage({ params }: { params: Promise<{ id: string }> }) {
    const { id } = await params;
    const cookie = (await headers()).get("cookie");
    const user = await getCurrentUserFromCookie(cookie);
    if (!user) {
        redirect("/auth/login");
    }

    const numericId = Number(id);
    if (!Number.isInteger(numericId)) {
        notFound();
    }

    const init = cookie ? { headers: { cookie }, cache: "no-store" as const } : undefined;
    const taskAccess = await loadRecord(() => getTaskById(numericId, init));
    if (taskAccess.kind === "forbidden") {
        return <AccessDeniedPage />;
    }
    if (taskAccess.kind === "missing") {
        notFound();
    }
    const task = taskAccess.record;

    const [persons, deals, users, t, tTasks, locale] = await Promise.all([
        getContactsFromCookie(cookie),
        getDealsFromCookie(cookie),
        (init ? getUsers(init) : Promise.resolve([])).catch(() => [] as User[]),
        getTranslations("ActivityTaskDetail"),
        getTranslations("ActivityTasks"),
        getLocale(),
    ]);

    const person = task.personId ? persons.find((p) => p.id === task.personId) ?? null : null;
    const deal = task.dealId ? deals.find((d) => d.id === task.dealId) ?? null : null;
    const assignee = users.find((u) => u.id === task.assignedToId) ?? null;
    const due = formatDue(task.dueDate, tTasks, locale);
    const statusLabel = tTasks(
        task.status === "done"
            ? "statusDone"
            : task.status === "in_progress"
              ? "statusInProgress"
              : "statusTodo",
    );

    return (
        <PageShell tier="form">
                <Rise className="flex flex-col gap-6">
                    <CrumbLabel value={noteSnippet(task.description, 60) || t("untitled")} />

                    <div className="flex items-start gap-3">
                        <CheckCircleIcon
                            className={cn(
                                "mt-1 size-6 shrink-0",
                                task.completed ? "text-emerald-500" : "text-muted-foreground/50",
                            )}
                        />
                        <h1
                            className={cn(
                                "text-2xl leading-tight font-semibold tracking-tight text-balance",
                                task.completed ? "text-muted-foreground line-through" : "text-foreground",
                            )}
                        >
                            <NoteContent content={task.description} references={task.references} />
                        </h1>
                    </div>
                </Rise>

                <Rise delay={0.05}>
                    <dl className="divide-y divide-border overflow-hidden rounded-2xl border border-border bg-card">
                        <MetaRow label={t("status")}>
                            <span
                                className={cn(
                                    "inline-flex items-center rounded-full px-2.5 py-0.5 text-sm font-medium ring-1 ring-inset",
                                    STATUS_TONE[task.status],
                                )}
                            >
                                {statusLabel}
                            </span>
                        </MetaRow>

                        <MetaRow label={t("dueDate")}>
                            {due ? (
                                <span
                                    className={cn(
                                        "inline-flex items-center rounded-full px-2.5 py-0.5 text-sm font-medium tabular-nums ring-1 ring-inset",
                                        DUE_CHIP[due.tone],
                                    )}
                                >
                                    {due.label}
                                </span>
                            ) : (
                                <span className="text-muted-foreground">{t("noDueDate")}</span>
                            )}
                        </MetaRow>

                        <MetaRow label={t("assignee")}>
                            {assignee ? (
                                <span className="inline-flex items-center gap-2">
                                    <Avatar size="sm" className="ring-1 ring-border">
                                        {assignee.profilePictureUrl ? (
                                            <AvatarImage src={assignee.profilePictureUrl} alt="" />
                                        ) : null}
                                        <AvatarFallback>
                                            <UserIcon className="size-3 text-muted-foreground" />
                                        </AvatarFallback>
                                    </Avatar>
                                    <span>{assignee.displayName || assignee.username}</span>
                                </span>
                            ) : (
                                <span className="text-muted-foreground">{t("unassigned")}</span>
                            )}
                        </MetaRow>

                        {person ? (
                            <MetaRow label={t("contact")}>
                                <Link
                                    href={`/records/contacts/${person.id}`}
                                    className="inline-flex max-w-full items-center gap-1.5 rounded-full bg-brand-light/50 px-2.5 py-1 text-sm font-medium text-brand-dark ring-1 ring-inset ring-brand-dark/10 transition hover:bg-brand-light"
                                >
                                    <UserIcon className="size-3.5 shrink-0" />
                                    <span className="truncate">{person.name}</span>
                                </Link>
                            </MetaRow>
                        ) : null}

                        {deal ? (
                            <MetaRow label={t("deal")}>
                                <Link
                                    href={`/records/deals/${deal.id}`}
                                    className="inline-flex max-w-full items-center gap-1.5 rounded-full bg-brand-light/50 px-2.5 py-1 text-sm font-medium text-brand-dark ring-1 ring-inset ring-brand-dark/10 transition hover:bg-brand-light"
                                >
                                    <BriefcaseIcon className="size-3.5 shrink-0" />
                                    <span className="truncate">{deal.name}</span>
                                </Link>
                            </MetaRow>
                        ) : null}

                        <MetaRow label={t("created")}>
                            <span className="tabular-nums">{formatDate(task.createdAt, locale)}</span>
                        </MetaRow>
                    </dl>
                </Rise>

                <BacklinksPanel refType="task" refId={task.id} />
        </PageShell>
    );
}
