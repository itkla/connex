import { getAttachmentsFromCookie, getCompanies, getContactById, getContactConnections, getContactEmployment, getContactIntroPath, getContacts, getContextNotifications, getCurrentUserFromCookie, getDeals, getEntityCustomFieldsFromCookie, getTags, getUserById } from "@/app/lib/api";
import { notFound, redirect } from "next/navigation";
import { CrumbLabel } from "@/app/hooks/useNavTrail";
import { type Company, type Deal, type Tag, type Contact, type IntroPath, type PersonConnection, type PersonEmployment, type User } from "@/app/lib/types";
import { cookies } from "next/headers";
import Link from "next/link";
import { ArrowLeftIcon, UserIcon } from "@heroicons/react/24/outline";
import { getLocale, getTranslations } from "next-intl/server";

import Rise from "@/app/components/motion/Rise";
import SectionHeader from "@/app/components/dashboard/SectionHeader";
import ContactActionsMenu from "@/app/components/records/contacts/ContactActionsMenu";
import ContactAvatar from "@/app/components/records/contacts/ContactAvatar";
import ContactConnections from "@/app/components/records/contacts/ContactConnections";
import ContactStatCard from "@/app/components/records/contacts/ContactStatCard";
import NewActivityDialog from "@/app/components/records/contacts/NewActivityDialog";
import NewTaskDialog from "@/app/components/records/contacts/NewTaskDialog";
import TagEditor from "@/app/components/records/contacts/TagEditor";
import { Avatar, AvatarFallback, AvatarGroup, AvatarImage } from "@/components/ui/avatar";
import InfoRow from "@/app/components/me/InfoRow";
import Timeline from "@/app/components/me/Timeline";
import Attachments from "@/app/components/attachments/Attachments";
import CustomFieldRows from "@/app/components/records/CustomFieldRows";
import { formatCompactCurrency, formatDate, formatDateTime, formatShortDate } from "@/app/lib/utils";
import { Tooltip, TooltipTrigger, TooltipContent } from "@/components/ui/tooltip";
import EntityNotificationBanner from "@/app/components/notifications/EntityNotificationBanner";

export default async function ContactPage({ params }: { params: { id: number } }) {
    const { id } = await params;
    const cookie = (await cookies()).toString();
    const init = { headers: { cookie } } as const;
    const t = await getTranslations("ContactsPage");
    const locale = await getLocale();

    const [contact, currentUser, allTags, allCompanies, allPersons, allDeals, attachments, notificationPage, employment, connections, introPath, customFields] = await Promise.all([
        getContactById(id, init) as Promise<Contact>,
        getCurrentUserFromCookie(cookie),
        getTags(init).catch(() => [] as Tag[]),
        getCompanies(init).catch(() => [] as Company[]),
        getContacts({}, init).catch(() => [] as Contact[]),
        getDeals(init).catch(() => [] as Deal[]),
        getAttachmentsFromCookie("person", id, cookie),
        getContextNotifications("person", id, init).catch(() => ({ items: [], total: 0 })),
        getContactEmployment(id, init).catch(() => [] as PersonEmployment[]),
        getContactConnections(id, init).catch(() => [] as PersonConnection[]),
        getContactIntroPath(id, init).catch(() => ({ reachable: false, directlyKnown: false, steps: [] }) as IntroPath),
        getEntityCustomFieldsFromCookie("person", id, cookie),
    ]);
    if (!contact) {
        notFound();
    }
    if (!currentUser) {
        redirect('/auth/login');
    }

    const tasks = contact.tasks ?? [];
    const activities = contact.activities ?? [];
    const notes = contact.notes ?? [];
    const deals = contact.deals ?? [];
    const openTasks = tasks.filter((t) => !t.completed).length;

    const interactionUserIds = Array.from(new Set<number>([
        ...activities.map((a) => a.createdById),
        ...notes.map((n) => n.author),
        ...tasks.map((t) => t.assignedToId),
    ].filter((v): v is number => typeof v === "number")));

    const interactionUsers: User[] = (
        await Promise.all(interactionUserIds.map((uid) => getUserById(uid, init).catch(() => null)))
    ).filter((u): u is User => u !== null);

    return (
        <div className="min-h-full bg-background px-2 pt-8 pb-12">
            <div className="mx-auto flex w-full max-w-5xl flex-col gap-10">
                <Rise>
                    <div className="flex flex-row justify-between">
                        <Link
                            href="/records/contacts"
                            className="inline-flex items-center gap-2 text-base text-brand hover:text-brand-hover w-fit"
                        >
                            <ArrowLeftIcon className="h-4 w-4" />
                            <span>{t("allContacts")}</span>
                        </Link>
                    </div>

                    <CrumbLabel value={contact.name} />
                    <header className="mt-8 flex flex-wrap items-center justify-between gap-6">
                        <div className="flex items-center gap-6 py-8">
                            <ContactAvatar contact={contact} type="xlarge" />
                            <div className="flex flex-col gap-2">
                                <div className="flex flex-row flex-wrap items-center gap-3">
                                    <h1 className="text-4xl font-extrabold tracking-tight text-foreground">
                                        {contact.name}
                                    </h1>
                                    <TagEditor
                                        contactId={contact.id}
                                        currentTags={contact.tags ?? []}
                                        allTags={allTags}
                                    />
                                </div>
                                <h3 className="flex flex-wrap items-center gap-2 text-sm text-muted-foreground">
                                    {contact.title ? (
                                        <Link href={`/records/contacts?title=${contact.title}`} className="rounded-md bg-muted px-2 py-1 text-muted-foreground transition-colors duration-200 hover:bg-brand-hover hover:text-white">
                                            {contact.title}
                                        </Link>
                                    ) : null}
                                    {contact.company?.name ? (
                                        <>
                                            <span>@</span>
                                            <Link
                                                href={`/records/companies/${contact.company.id}`}
                                                className="rounded-md bg-muted px-2 py-1 text-muted-foreground transition-colors duration-200 hover:bg-brand-hover hover:text-white"
                                            >
                                                {contact.company.name}
                                            </Link>
                                        </>
                                    ) : null}
                                </h3>
                            </div>
                        </div>

                        <div className="flex flex-col items-end gap-2">
                            <span className="text-xs font-medium tracking-[0.12em] text-muted-foreground uppercase">
                                {t("relations")}
                            </span>
                            {interactionUsers.length === 0 ? (
                                <span className="text-xs text-muted-foreground">{t("noRecordedInteractions")}</span>
                            ) : (
                                <AvatarGroup>
                                    {interactionUsers.map((user) => (
                                        <Tooltip key={user.id}>
                                            <TooltipTrigger asChild>
                                                <Link href={`/users/${user.id}`}>
                                                    <Avatar
                                                        className="h-12 w-12 bg-muted-foreground/40"
                                                    >
                                                        {user.profilePictureUrl ? (
                                                            <AvatarImage
                                                                src={user.profilePictureUrl}
                                                                alt={user.displayName || user.username}
                                                            />
                                                        ) : (
                                                            <AvatarFallback>
                                                                <UserIcon className="size-4 text-muted-foreground" />
                                                            </AvatarFallback>
                                                        )}
                                                    </Avatar>
                                                </Link>
                                            </TooltipTrigger>
                                            <TooltipContent side="bottom" align="center">
                                                {user.displayName || user.username}
                                            </TooltipContent>
                                        </Tooltip>
                                    ))}
                                </AvatarGroup>
                            )}
                        </div>
                    </header>

                    <div className="mt-4 flex justify-end">
                        <ContactActionsMenu
                            contact={contact}
                            companies={allCompanies}
                            currentUserId={currentUser.id}
                            persons={allPersons}
                            deals={allDeals}
                        />
                    </div>
                    <EntityNotificationBanner initialNotifications={notificationPage.items} />
                </Rise>

                <div className="grid grid-cols-1 gap-8 md:grid-cols-[minmax(0,1fr)_minmax(0,2fr)]">
                    <Rise delay={0.06}>
                        <aside>
                            <SectionHeader title={t("profile")} />
                            <dl className="divide-y divide-border overflow-hidden rounded-2xl border border-border bg-card">
                                <InfoRow label={t("email")} value={contact.email ?? ''} />
                                <InfoRow label={t("phone")} value={contact.phone ?? ''} />
                                <InfoRow label={t("title")} value={contact.title ?? ''} />
                                <InfoRow label={t("company")} value={contact.company?.name ?? t("companyPlaceholder")} />
                                <InfoRow label={t("added")} value={formatDate(contact.createdAt, locale)} />
                                <InfoRow label={t("updated")} value={formatDateTime(contact.updatedAt, locale)} />
                                <CustomFieldRows entityType="person" entityId={contact.id} initialEntries={customFields} />
                            </dl>

                            {employment.length > 0 && (
                                <div className="mt-6">
                                    <SectionHeader title={t("employmentHistory")} />
                                    <ol className="divide-y divide-border overflow-hidden rounded-2xl border border-border bg-card">
                                        {employment.map((stint) => (
                                            <li key={stint.id} className="px-6 py-4">
                                                <div className="flex items-center justify-between gap-2">
                                                    {stint.companyId ? (
                                                        <Link
                                                            href={`/records/companies/${stint.companyId}`}
                                                            className="truncate text-sm font-medium text-foreground transition-colors hover:text-brand-hover"
                                                        >
                                                            {stint.companyName ?? t("unknownCompany")}
                                                        </Link>
                                                    ) : (
                                                        <span className="truncate text-sm font-medium text-foreground">
                                                            {stint.companyName ?? t("unknownCompany")}
                                                        </span>
                                                    )}
                                                    {stint.current && (
                                                        <span className="shrink-0 rounded-full bg-brand-light px-2 py-0.5 text-[10px] font-medium uppercase tracking-wider text-brand-dark">
                                                            {t("current")}
                                                        </span>
                                                    )}
                                                </div>
                                                {stint.title ? (
                                                    <p className="mt-0.5 text-xs text-muted-foreground">{stint.title}</p>
                                                ) : null}
                                                <p className="mt-1 text-xs tabular-nums text-muted-foreground">
                                                    {formatShortDate(stint.startedAt ?? undefined, locale)} – {stint.endedAt ? formatShortDate(stint.endedAt, locale) : t("present")}
                                                </p>
                                            </li>
                                        ))}
                                    </ol>
                                </div>
                            )}

                            <Attachments
                                entityType="person"
                                entityId={contact.id}
                                initialAttachments={attachments}
                                className="mt-6"
                            />
                        </aside>
                    </Rise>

                    <Rise delay={0.12}>
                        <section>
                            <SectionHeader title={t("theirActivity")} />
                            <div className="grid grid-cols-3 gap-3">
                                <ContactStatCard
                                    label={t("activities")}
                                    value={activities.length}
                                    subtitle={notes.length > 0 ? t("notesCount", { count: notes.length }) : undefined}
                                    addAction={
                                        <NewActivityDialog
                                            contactId={contact.id}
                                            contactName={contact.name}
                                            companyId={contact.companyId ?? contact.company?.id}
                                            currentUserId={currentUser.id}
                                        />
                                    }
                                    viewHref={`/activity/all?contactId=${contact.id}`}
                                />
                                <ContactStatCard
                                    label={t("tasks")}
                                    value={tasks.length}
                                    subtitle={tasks.length > 0 ? t("openCount", { count: openTasks }) : undefined}
                                    addAction={
                                        <NewTaskDialog
                                            contactId={contact.id}
                                            contactName={contact.name}
                                            companyId={contact.companyId ?? contact.company?.id}
                                            currentUserId={currentUser.id}
                                        />
                                    }
                                    viewHref={`/activity/tasks?contactId=${contact.id}`}
                                />
                                <ContactStatCard
                                    label={t("deals")}
                                    value={deals.length}
                                    subtitle={deals.length > 0 ? t("dealsCount", { count: deals.length }) : undefined}
                                    viewHref={`/activity/deals?contactId=${contact.id}`}
                                />
                            </div>

                            <div className="mt-6">
                                <SectionHeader title={t("activePipeline")} />
                                <div className="overflow-hidden rounded-2xl border border-border bg-card">
                                    {deals.length === 0 ? (
                                        <p className="px-6 py-6 text-sm text-muted-foreground">{t("noActiveDeals")}</p>
                                    ) : (
                                        <ul className="divide-y divide-border">
                                            {deals.map((deal) => (
                                                <li key={deal.id}>
                                                    <Link
                                                        href={`/records/deals/${deal.id}`}
                                                        className="flex items-center justify-between px-6 py-4 transition-colors hover:bg-muted/60"
                                                    >
                                                        <span className="text-sm font-medium text-foreground">
                                                            {deal.name}
                                                        </span>
                                                        <span className="text-sm text-muted-foreground">
                                                            {formatCompactCurrency(deal.value, deal.currency, locale)}
                                                        </span>
                                                    </Link>
                                                </li>
                                            ))}
                                        </ul>
                                    )}
                                </div>
                            </div>

                            <div className="mt-6">
                                <SectionHeader title={t("connections")} />
                                <ContactConnections
                                    contactId={contact.id}
                                    contactName={contact.name}
                                    contacts={allPersons}
                                    initialConnections={connections}
                                    initialIntroPath={introPath}
                                />
                            </div>

                            <div className="mt-6">
                                <SectionHeader title={t("timeline")} />
                                <div className="overflow-hidden rounded-2xl border border-border bg-card">
                                    <Timeline
                                        tasks={tasks}
                                        activities={activities}
                                        notes={notes}
                                        users={interactionUsers}
                                        persons={allPersons}
                                        deals={allDeals}
                                        currentUserId={currentUser.id}
                                        companyId={contact.companyId ?? contact.company?.id ?? null}
                                    />
                                </div>
                            </div>
                        </section>
                    </Rise>
                </div>
            </div>
        </div>
    );
}
