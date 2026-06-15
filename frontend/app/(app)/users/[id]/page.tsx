// user detail page, ripped straight from /me

import { headers } from "next/headers";
import { notFound, redirect } from "next/navigation";
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
import Attachments from "@/app/components/attachments/Attachments";

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

    return (
        <div className="mx-auto w-full max-w-5xl md:flex md:min-h-0 md:flex-1 md:flex-col">
            <Link
                href="/users"
                className="inline-flex w-fit items-center gap-2 text-base text-brand hover:text-brand-hover"
            >
                <ArrowLeftIcon className="h-4 w-4" />
                <span>{t("allUsers")}</span>
            </Link>

            <header className="mt-8 flex items-center gap-6 py-8">
                <UserAvatar user={user} type="xlarge" />
                <div className="flex flex-col gap-2">
                    <h1 className="text-4xl font-extrabold tracking-tight text-foreground">{user.displayName}</h1>
                    <h3 className="flex flex-wrap items-center gap-2 text-sm text-muted-foreground">
                        <span className="rounded-md bg-muted px-2 py-1">@{user.username}</span>
                        <span>{user.email}</span>
                    </h3>
                </div>
            </header>

            <div className="mt-4 grid grid-cols-1 gap-8 md:grid-cols-[minmax(0,1fr)_minmax(0,2fr)] md:min-h-0 md:flex-1">
                <aside>
                    <div className="mb-3 flex h-8 items-center">
                        <h2 className="px-6 text-xs font-medium uppercase tracking-[0.12em] text-muted-foreground">
                            {t("profile")}
                        </h2>
                    </div>
                    <dl className="divide-y divide-border overflow-hidden rounded-2xl bg-muted ring-1 ring-border">
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

                <section className="md:flex md:min-h-0 md:flex-col">
                    <div className="mb-3 flex h-8 items-center">
                        <h2 className="px-6 text-xs font-medium uppercase tracking-[0.12em] text-muted-foreground">
                            {t("theirActivity")}
                        </h2>
                    </div>

                    <div className="grid grid-cols-3 gap-3">
                        <StatCard label={t("activities")} value={activities.length} />
                        <StatCard
                            label={t("tasks")}
                            value={tasks.length}
                            subtitle={tasks.length > 0 ? t("openCount", { count: openTasks }) : undefined}
                        />
                        <StatCard label={t("notes")} value={notes.length} />
                    </div>

                    <div className="mb-3 mt-6 flex h-8 items-center">
                        <h2 className="px-6 text-xs font-medium uppercase tracking-[0.12em] text-muted-foreground">
                            {t("timeline")}
                        </h2>
                    </div>
                    <div className="overflow-hidden rounded-2xl bg-card ring-1 ring-border md:flex md:min-h-0 md:flex-1 md:flex-col">
                        <div className="md:min-h-0 md:flex-1 md:overflow-y-auto md:[-webkit-mask-image:linear-gradient(to_bottom,transparent_0,black_24px)] md:[mask-image:linear-gradient(to_bottom,transparent_0,black_24px)]">
                            <Timeline
                                tasks={tasks}
                                activities={activities}
                                notes={notes}
                                users={users}
                                persons={persons}
                                deals={deals}
                                currentUserId={currentUser.id}
                            />
                        </div>
                    </div>
                </section>
            </div>
        </div>
    );
}