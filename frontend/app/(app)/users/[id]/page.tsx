import { headers } from "next/headers";
import { notFound, redirect } from "next/navigation";
import { CrumbLabel } from "@/app/hooks/useNavTrail";
import Link from "next/link";
import { ArrowLeftIcon } from "@heroicons/react/24/outline";
import { getLocale, getTranslations } from "next-intl/server";

import {
    getAttachmentsFromCookie,
    getContacts,
    getCurrentUserFromCookie,
    getDeals,
    getUserActivitiesFromCookie,
    getUserById,
    getUserNotesFromCookie,
    getUserTasksFromCookie,
    getUsers,
} from "@/app/lib/api";
import { type Contact, type Deal, type User } from "@/app/lib/types";
import { formatDate, formatDateTime } from "@/app/lib/utils";

import UserAvatar from "@/app/components/records/users/UserAvatar";
import InfoRow from "@/app/components/me/InfoRow";
import StatCard from "@/app/components/me/StatCard";
import Timeline from "@/app/components/me/Timeline";
import EmptyState from "@/app/components/me/EmptyState";
import Attachments from "@/app/components/attachments/Attachments";
import Rise from "@/app/components/motion/Rise";
import SectionHeader from "@/app/components/dashboard/SectionHeader";

export default async function UserPage({ params }: { params: { id: number } }) {
    const { id } = await params;
    const cookie = (await headers()).get("cookie");
    const init = { headers: { cookie: cookie ?? "" } } as const;
    const t = await getTranslations("UsersPage");
    const locale = await getLocale();

    const [user, currentUser, tasks, activities, notes, users, persons, deals, attachments] = await Promise.all([
        getUserById(id, init).catch(() => null),
        getCurrentUserFromCookie(cookie),
        getUserTasksFromCookie(id, cookie),
        getUserActivitiesFromCookie(id, cookie),
        getUserNotesFromCookie(id, cookie),
        getUsers(init).catch(() => [] as User[]),
        getContacts({}, init).catch(() => [] as Contact[]),
        getDeals(init).catch(() => [] as Deal[]),
        getAttachmentsFromCookie("user", id, cookie),
    ]);

    if (!currentUser) {
        redirect("/auth/login");
    }
    if (!user) {
        notFound();
    }

    const openTasks = tasks.filter((task) => !task.completed).length;
    const hasActivity = tasks.length > 0 || activities.length > 0 || notes.length > 0;

    return (
        <div className="min-h-full bg-background px-2 pt-8 pb-12">
            <div className="mx-auto flex w-full max-w-5xl flex-col gap-10">
                <Rise>
                    <div className="flex flex-col gap-8">
                        <Link
                            href="/users"
                            className="inline-flex w-fit items-center gap-2 text-base text-brand hover:text-brand-hover"
                        >
                            <ArrowLeftIcon className="h-4 w-4" />
                            <span>{t("allUsers")}</span>
                        </Link>

                        <CrumbLabel value={user.displayName} />
                        <header className="flex items-center gap-6">
                            <UserAvatar user={user} type="xlarge" />
                            <div className="flex flex-col gap-2">
                                <h1 className="text-4xl font-extrabold tracking-tight text-foreground">
                                    {user.displayName}
                                </h1>
                                <h3 className="flex flex-wrap items-center gap-2 text-sm text-muted-foreground">
                                    <span className="rounded-md bg-muted px-2 py-1">@{user.username}</span>
                                    <span>{user.email}</span>
                                </h3>
                            </div>
                        </header>
                    </div>
                </Rise>

                <div className="grid grid-cols-1 gap-8 md:grid-cols-[minmax(0,1fr)_minmax(0,2fr)]">
                    <Rise delay={0.06}>
                        <aside>
                            <SectionHeader title={t("profile")} />
                            <dl className="divide-y divide-border overflow-hidden rounded-2xl border border-border bg-card">
                                <InfoRow label={t("username")} value={`@${user.username}`} />
                                <InfoRow label={t("email")} value={user.email ?? ""} />
                                <InfoRow
                                    label={t("lastLogin")}
                                    value={user.lastLoginAt ? formatDateTime(user.lastLoginAt, locale) : t("neverLoggedIn")}
                                />
                                <InfoRow label={t("memberSince")} value={formatDate(user.createdAt, locale)} />
                                <InfoRow label={t("updated")} value={formatDateTime(user.updatedAt, locale)} />
                            </dl>

                            <Attachments
                                entityType="user"
                                entityId={user.id}
                                initialAttachments={attachments}
                                className="mt-6"
                            />
                        </aside>
                    </Rise>

                    <Rise delay={0.12}>
                        <section>
                            <SectionHeader title={t("theirActivity")} />

                            <div className="grid grid-cols-3 gap-3">
                                <StatCard label={t("activities")} value={activities.length} />
                                <StatCard
                                    label={t("tasks")}
                                    value={tasks.length}
                                    subtitle={tasks.length > 0 ? t("openCount", { count: openTasks }) : undefined}
                                />
                                <StatCard label={t("notes")} value={notes.length} />
                            </div>

                            <div className="mt-6">
                                <SectionHeader title={t("timeline")} />
                                <div className="overflow-hidden rounded-2xl border border-border bg-card">
                                    {hasActivity ? (
                                        <Timeline
                                            tasks={tasks}
                                            activities={activities}
                                            notes={notes}
                                            users={users}
                                            persons={persons}
                                            deals={deals}
                                            currentUserId={currentUser.id}
                                        />
                                    ) : (
                                        <EmptyState message={t("emptyActivity")} />
                                    )}
                                </div>
                            </div>
                        </section>
                    </Rise>
                </div>
            </div>
        </div>
    );
}
