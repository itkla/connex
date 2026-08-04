import { headers } from "next/headers";
import { notFound, redirect } from "next/navigation";
import AccessDeniedPage from "@/app/components/AccessDeniedPage";
import { loadRecord } from "@/app/lib/recordAccess";
import Link from "next/link";
import { getLocale, getTranslations } from "next-intl/server";
import { BriefcaseIcon, UserIcon } from "@heroicons/react/24/outline";

import {
    getActivityById,
    getContactsFromCookie,
    getCurrentUserFromCookie,
    getDealsFromCookie,
    getUsers,
} from "@/app/lib/api";
import type { User } from "@/app/lib/types";
import { formatDateTime } from "@/app/lib/utils";
import { CrumbLabel } from "@/app/hooks/useNavTrail";
import Rise from "@/app/components/motion/Rise";
import { PageShell } from "@/app/components/PageShell";
import NoteContent from "@/app/components/activity/notes/NoteContent";
import BacklinksPanel from "@/app/components/activity/notes/BacklinksPanel";
import ProviderCaptureEvidence from "@/app/components/activity/ProviderCaptureEvidence";
import { TYPE_META, normalizeType } from "@/app/components/activity/activities/activityTypeMeta";
import { Avatar, AvatarFallback, AvatarImage } from "@/components/ui/avatar";
import { cn } from "@/lib/utils";

function MetaRow({ label, children }: { label: string; children: React.ReactNode }) {
    return (
        <div className="flex flex-col gap-1.5 px-6 py-4">
            <dt className="text-sm text-muted-foreground">{label}</dt>
            <dd className="text-base text-foreground">{children}</dd>
        </div>
    );
}

export default async function ActivityDetailPage({
    params,
}: {
    params: Promise<{ id: string }>;
}) {
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
    const activityAccess = await loadRecord(() => getActivityById(numericId, init));
    if (activityAccess.kind === "forbidden") {
        return <AccessDeniedPage />;
    }
    if (activityAccess.kind === "missing") {
        notFound();
    }
    const activity = activityAccess.record;

    const [persons, deals, users, t, tPage, locale] = await Promise.all([
        getContactsFromCookie(cookie),
        getDealsFromCookie(cookie),
        (init ? getUsers(init) : Promise.resolve([])).catch(() => [] as User[]),
        getTranslations("ActivityActivityDetail"),
        getTranslations("ActivityPage"),
        getLocale(),
    ]);

    const person = activity.personId ? persons.find((p) => p.id === activity.personId) ?? null : null;
    const deal = activity.dealId ? deals.find((d) => d.id === activity.dealId) ?? null : null;
    const author = users.find((u) => u.id === activity.createdById) ?? null;
    const kind = normalizeType(activity.type);
    const meta = TYPE_META[kind];
    const Icon = meta.Icon;
    const typeLabel = tPage(`type${kind}` as "typeCall");

    return (
        <PageShell tier="form">
                <Rise className="flex flex-col gap-6">
                    <CrumbLabel value={activity.subject} />

                    <div className="flex items-start gap-3.5">
                        <span
                            className={cn(
                                "mt-0.5 flex size-10 shrink-0 items-center justify-center rounded-full ring-1 ring-inset",
                                meta.chip,
                            )}
                            aria-hidden
                        >
                            <Icon className="size-5" />
                        </span>
                        <div className="min-w-0 flex-1">
                            <h1 className="text-2xl leading-tight font-semibold tracking-tight text-balance text-foreground">
                                {activity.subject}
                            </h1>
                            <p className="mt-1 text-sm text-muted-foreground">
                                {typeLabel}
                                {activity.timestamp ? ` · ${formatDateTime(activity.timestamp, locale)}` : ""}
                            </p>
                        </div>
                    </div>
                </Rise>

                {activity.notes ? (
                    <Rise delay={0.05}>
                        <div className="rounded-2xl border border-border bg-card px-6 py-5 text-foreground">
                            <NoteContent content={activity.notes} references={activity.references} block />
                        </div>
                    </Rise>
                ) : null}

                <Rise delay={0.1}>
                    <dl className="divide-y divide-border overflow-hidden rounded-2xl border border-border bg-card">
                        <MetaRow label={t("type")}>{typeLabel}</MetaRow>

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

                        {author ? (
                            <MetaRow label={t("loggedBy")}>
                                <span className="inline-flex items-center gap-2">
                                    <Avatar size="sm" className="ring-1 ring-border">
                                        {author.profilePictureUrl ? (
                                            <AvatarImage src={author.profilePictureUrl} alt="" />
                                        ) : null}
                                        <AvatarFallback>
                                            <UserIcon className="size-3 text-muted-foreground" />
                                        </AvatarFallback>
                                    </Avatar>
                                    <span>{author.displayName || author.username}</span>
                                </span>
                            </MetaRow>
                        ) : null}

                        {activity.timestamp ? (
                            <MetaRow label={t("when")}>
                                <span className="tabular-nums">
                                    {formatDateTime(activity.timestamp, locale)}
                                </span>
                            </MetaRow>
                        ) : null}
                    </dl>
                </Rise>

                {activity.captureEvidence ? (
                    <ProviderCaptureEvidence evidence={activity.captureEvidence} />
                ) : null}

                <BacklinksPanel refType="activity" refId={activity.id} />
        </PageShell>
    );
}
