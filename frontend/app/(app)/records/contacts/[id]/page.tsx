import { getCompanies, getContactById, getContacts, getCurrentUserFromCookie, getDeals, getTags, getUserById } from "@/app/lib/api";
import { notFound, redirect } from "next/navigation";
import { type Company, type Deal, type Tag, type Contact, type User } from "@/app/lib/types";
import { cookies } from "next/headers";
import Link from "next/link";
import { ArrowLeftIcon, UserIcon } from "@heroicons/react/24/outline";
import { getTranslations } from "next-intl/server";

import ContactActionsMenu from "@/app/components/records/contacts/ContactActionsMenu";
import ContactAvatar from "@/app/components/records/contacts/ContactAvatar";
import ContactStatCard from "@/app/components/records/contacts/ContactStatCard";
import NewActivityDialog from "@/app/components/records/contacts/NewActivityDialog";
import NewTaskDialog from "@/app/components/records/contacts/NewTaskDialog";
import TagEditor from "@/app/components/records/contacts/TagEditor";
import { Avatar, AvatarFallback, AvatarGroup, AvatarImage } from "@/components/ui/avatar";
import InfoRow from "@/app/components/me/InfoRow";
import Timeline from "@/app/components/me/Timeline";
import { formatCompactCurrency, formatDate, formatDateTime } from "@/app/lib/utils";
import { Tooltip, TooltipTrigger, TooltipContent } from "@/components/ui/tooltip";

export default async function ContactPage({ params }: { params: { id: number } }) {
    const { id } = await params;
    const cookie = (await cookies()).toString();
    const init = { headers: { cookie } } as const;
    const t = await getTranslations("ContactsPage");

    const [contact, currentUser, allTags, allCompanies, allPersons, allDeals] = await Promise.all([
        getContactById(id, init) as Promise<Contact>,
        getCurrentUserFromCookie((await cookies()).toString()),
        getTags(init).catch(() => [] as Tag[]),
        getCompanies(init).catch(() => [] as Company[]),
        getContacts({}, init).catch(() => [] as Contact[]),
        getDeals(init).catch(() => [] as Deal[]),
    ]);
    if (!contact) {
        console.error(`Contact not found: ${id}`);
        notFound(); // TODO: gracefully handle 404 contacts in-page instead of redirecting to 404 page
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
        <div className="mx-auto w-full max-w-5xl md:flex md:min-h-0 md:flex-1 md:flex-col">
            <div className="flex flex-row justify-between">
                <Link
                    href="/records/contacts"
                    className="inline-flex items-center gap-2 text-base text-brand hover:text-brand-hover w-fit"
                >
                    <ArrowLeftIcon className="h-4 w-4" />
                    <span>{t("allContacts")}</span>
                </Link>
            </div>


            <header className="mt-8 flex flex-wrap items-center justify-between gap-6">
                <div className="flex items-center gap-6 py-8">
                    <ContactAvatar contact={contact} type="xlarge" />
                    <div className="flex flex-col gap-2">
                        <div className="flex flex-row flex-wrap items-center gap-3">
                            <h1 className="text-4xl font-extrabold tracking-tight text-black">
                                {contact.name}
                            </h1>
                            <TagEditor
                                contactId={contact.id}
                                currentTags={contact.tags ?? []}
                                allTags={allTags}
                            />
                        </div>
                        <h3 className="flex flex-wrap items-center gap-2 text-sm text-neutral-500">
                            {contact.title ? (
                                <Link href={`/records/contacts?title=${contact.title}`} className="rounded-md bg-neutral-100 px-2 py-1 text-neutral-500 transition-colors duration-200 hover:bg-brand-hover hover:text-white">
                                    {contact.title}
                                </Link>
                            ) : null}
                            {contact.company?.name ? (
                                <>
                                    <span>@</span>
                                    <Link
                                        href={`/records/companies/${contact.company.id}`}
                                        className="rounded-md bg-neutral-100 px-2 py-1 text-neutral-500 transition-colors duration-200 hover:bg-brand-hover hover:text-white"
                                    >
                                        {contact.company.name}
                                    </Link>
                                </>
                            ) : null}
                        </h3>
                    </div>
                </div>

                <div className="flex flex-col items-end gap-2">
                    <span className="text-xs font-medium tracking-[0.12em] text-neutral-500 uppercase">
                        {t("relations")}
                    </span>
                    {interactionUsers.length === 0 ? (
                        <span className="text-xs text-neutral-400">{t("noRecordedInteractions")}</span>
                    ) : (
                        <AvatarGroup>
                            {interactionUsers.map((user) => (
                                <Tooltip key={user.id}>
                                    <TooltipTrigger asChild>
                                        <Link href={`/users/${user.id}`}>
                                            <Avatar
                                                className="h-12 w-12 bg-neutral-500"
                                            >
                                                {user.profilePictureUrl ? (
                                                    <AvatarImage
                                                        src={user.profilePictureUrl}
                                                        alt={user.displayName || user.username}
                                                    />
                                                ) : (
                                                    <AvatarFallback>
                                                        <UserIcon className="size-4 text-white" />
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

            <div className="mt-12 grid grid-cols-1 gap-8 md:grid-cols-[minmax(0,1fr)_minmax(0,2fr)] md:min-h-0 md:flex-1">
                <aside>
                    <div className="mb-3 flex h-8 items-center">
                        <h2 className="px-6 text-xs font-medium tracking-[0.12em] text-neutral-500 uppercase">
                            {t("profile")}
                        </h2>
                    </div>
                    <dl className="divide-y divide-neutral-200 overflow-hidden rounded-2xl bg-neutral-100 ring-1 ring-black/5">
                        <InfoRow label={t("email")} value={contact.email ?? ''} />
                        <InfoRow label={t("phone")} value={contact.phone ?? ''} />
                        <InfoRow label={t("title")} value={contact.title ?? ''} />
                        <InfoRow label={t("company")} value={contact.company?.name ?? t("companyPlaceholder")} />
                        <InfoRow label={t("added")} value={formatDate(contact.createdAt)} />
                        <InfoRow label={t("updated")} value={formatDateTime(contact.updatedAt)} />
                    </dl>
                </aside>

                <section className="md:flex md:min-h-0 md:flex-col">
                    <div className="mb-3 flex h-8 items-center">
                        <h2 className="px-6 text-xs font-medium tracking-[0.12em] text-neutral-500 uppercase">
                            {t("theirActivity")}
                        </h2>
                    </div>
                    <div className="grid grid-cols-3 gap-3">
                        <ContactStatCard
                            label={t("activities")}
                            value={activities.length}
                            subtitle={notes.length > 0 ? t("notesCount", { count: notes.length }) : undefined}
                            addAction={
                                <NewActivityDialog
                                    contactId={contact.id}
                                    contactName={contact.name}
                                    currentUserId={currentUser.id}
                                />
                            }
                            viewHref={`/activity?contactId=${contact.id}`}
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

                    <div className="mt-6 mb-3 flex h-8 items-center">
                        <h2 className="px-6 text-xs font-medium tracking-[0.12em] text-neutral-500 uppercase">
                            {t("activePipeline")}
                        </h2>
                    </div>
                    <div className="overflow-hidden rounded-2xl bg-neutral-100 ring-1 ring-black/5">
                        {deals.length === 0 ? (
                            <p className="px-6 py-6 text-sm text-neutral-500">{t("noActiveDeals")}</p>
                        ) : (
                            <ul className="divide-y divide-neutral-200">
                                {deals.map((deal) => (
                                    <li key={deal.id}>
                                        <Link
                                            href={`/records/deals/${deal.id}`}
                                            className="flex items-center justify-between px-6 py-4 transition-colors hover:bg-neutral-200/60"
                                        >
                                            <span className="text-sm font-medium text-black">
                                                {deal.name}
                                            </span>
                                            <span className="text-sm text-neutral-500">
                                                {formatCompactCurrency(deal.value, deal.currency)}
                                            </span>
                                        </Link>
                                    </li>
                                ))}
                            </ul>
                        )}
                    </div>

                    <div className="mt-6 mb-3 flex h-8 items-center">
                        <h2 className="px-6 text-xs font-medium tracking-[0.12em] text-neutral-500 uppercase">
                            {t("timeline")}
                        </h2>
                    </div>
                    <div className="overflow-hidden rounded-2xl bg-white ring-1 ring-black/5 md:flex md:min-h-0 md:flex-1 md:flex-col">
                        <div className="md:min-h-0 md:flex-1 md:overflow-y-auto md:[-webkit-mask-image:linear-gradient(to_bottom,transparent_0,black_24px)] md:[mask-image:linear-gradient(to_bottom,transparent_0,black_24px)]">
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
            </div>
        </div>
    );
}