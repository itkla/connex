// NOTE to Hunter: yo, im trying out suspense here and i wanna see how it performs. if it sucks, delete it -hunter

import { cookies } from "next/headers";
import Link from "next/link";
import { Suspense } from "react";
import { notFound, redirect } from "next/navigation";
import { getTranslations } from "next-intl/server";
import { ArrowLeftIcon, UserIcon, PlusIcon } from "@heroicons/react/24/outline";
import PipelineCard from "@/app/components/records/PipelineCard";
import NewContactDialog from "@/app/components/records/contacts/NewContactDialog";
import { Skeleton } from "@/components/ui/skeleton";

import {
    getActivities,
    getCompanyById,
    getCompanyDeals,
    getCompanyPeople,
    getCompanyTags,
    getCurrentUserFromCookie,
    getNotes,
    getTags,
    getTasks,
    getUserById,
} from "@/app/lib/api";
import { type Activity, type Company, type Contact, type Deal, type Note, type Tag, type Task, type User } from "@/app/lib/types";
import { formatCompactCurrency, formatDate, formatDateTime } from "@/app/lib/utils";

import CompanyActionsMenu from "@/app/components/records/companies/CompanyActionsMenu";
import CompanyAvatar from "@/app/components/records/companies/CompanyAvatar";
import { EngagementSparkline, RevenueTiles, type EngagementPoint } from "@/app/components/records/companies/CompanyCard";
import ContactAvatar from "@/app/components/records/contacts/ContactAvatar";
import ContactStatCard from "@/app/components/records/contacts/ContactStatCard";
import TagEditor from "@/app/components/records/contacts/TagEditor";
import InfoRow from "@/app/components/me/InfoRow";
import Timeline from "@/app/components/me/Timeline";
import { Avatar, AvatarFallback, AvatarGroup, AvatarImage } from "@/components/ui/avatar";
import { Tooltip, TooltipContent, TooltipTrigger } from "@/components/ui/tooltip";
import ContactCard from "@/app/components/records/contacts/ContactCard";
import QuickEditSheet from "@/app/components/records/contacts/QuickEditSheet";
import ContactsGrid from "@/app/components/records/companies/ContactsGrid";

const WEEK_MS = 7 * 24 * 60 * 60 * 1000;

export default async function CompanyPage({ params }: { params: { id: number } }) {
    const { id } = await params;
    const cookie = (await cookies()).toString();
    const init = { headers: { cookie } } as const;
    const t = await getTranslations("CompaniesDetail");

    const [
        company,
        currentUser,
        allTags,
        people,
        deals,
        companyTags,
        allTasks,
        allActivities,
        allNotes,
    ] = await Promise.all([
        getCompanyById(id, init) as Promise<Company>,
        getCurrentUserFromCookie(cookie),
        getTags(init).catch(() => [] as Tag[]),
        getCompanyPeople(id, init).catch(() => [] as Contact[]),
        getCompanyDeals(id, init).catch(() => [] as Deal[]),
        getCompanyTags(id, init).catch(() => [] as Tag[]),
        getTasks(init).catch(() => [] as Task[]),
        getActivities(init).catch(() => [] as Activity[]),
        getNotes(init).catch(() => [] as Note[]),
    ]);

    if (!company) {
        console.error(`Company not found: ${id}`);
        notFound();
    }
    if (!currentUser) {
        redirect('/auth/login');
    }

    const personIds = new Set(people.map((p) => p.id));
    const dealIds = new Set(deals.map((d) => d.id));

    const tasks = allTasks.filter(
        (t) => (t.personId != null && personIds.has(t.personId)) ||
            (t.dealId != null && dealIds.has(t.dealId)),
    );
    const activities = allActivities.filter(
        (a) => (a.personId != null && personIds.has(a.personId)) ||
            (a.dealId != null && dealIds.has(a.dealId)),
    );
    const notes = allNotes.filter(
        (n) => (n.person != null && personIds.has(n.person)) ||
            (n.deal != null && dealIds.has(n.deal)),
    );
    const openTasks = tasks.filter((t) => !t.completed).length;

    const interactionUserIds = Array.from(new Set<number>([
        ...activities.map((a) => a.createdById),
        ...notes.map((n) => n.author),
        ...tasks.map((t) => t.assignedToId),
    ].filter((v): v is number => typeof v === "number")));

    const interactionUsers: User[] = (
        await Promise.all(interactionUserIds.map((uid) => getUserById(uid, init).catch(() => null)))
    ).filter((u): u is User => u !== null);

    const now = Date.now();
    const firstWeekStart = now - 11 * WEEK_MS;
    const weeklyEngagement: EngagementPoint[] = Array.from({ length: 12 }, (_, i) => ({
        weekStart: firstWeekStart + i * WEEK_MS,
        count: 0,
        activities: 0,
        tasks: 0,
        notes: 0,
    }));
    const bucket = (ts: number, kind: 'activities' | 'tasks' | 'notes') => {
        if (!Number.isFinite(ts)) return;
        const idx = Math.floor((ts - firstWeekStart) / WEEK_MS);
        if (idx < 0 || idx >= weeklyEngagement.length) return;
        weeklyEngagement[idx][kind]++;
        weeklyEngagement[idx].count++;
    };
    for (const a of activities) bucket(Date.parse(a.timestamp ?? ''), 'activities');
    for (const t of tasks) bucket(Date.parse(t.createdAt ?? ''), 'tasks');
    for (const n of notes) bucket(Date.parse(n.createdAt ?? ''), 'notes');

    let pastRevenue = 0;
    let projectedRevenue = 0;
    for (const d of deals) {
        const closed = d.closedAt ? Date.parse(d.closedAt) : NaN;
        if (Number.isFinite(closed) && closed <= now) {
            pastRevenue += d.value ?? 0;
        } else {
            projectedRevenue += d.value ?? 0;
        }
    }

    const onQuickEdit = (contact: Contact) => {

    };
    const onDelete = (contact: Contact) => {
        console.log('onDelete', contact);
    };

    return (
        <div className="mx-auto w-full max-w-5xl md:flex md:min-h-0 md:flex-1 md:flex-col">
            <Link
                href="/records/companies"
                className="inline-flex items-center gap-2 text-base text-brand hover:text-brand-hover w-fit"
            >
                <ArrowLeftIcon className="h-4 w-4" />
                <span>{t("backToAll")}</span>
            </Link>

            <header className="mt-8 flex flex-wrap items-center justify-between gap-6">
                <div className="flex items-center gap-6 py-8">
                    <CompanyAvatar company={company} type="2xlarge" />
                    <div className="flex flex-col gap-2">
                        <div className="flex flex-row flex-wrap items-center gap-3">
                            <h1 className="text-4xl font-extrabold tracking-tight text-black">
                                {company.name}
                            </h1>
                            <TagEditor
                                companyId={company.id}
                                currentTags={companyTags}
                                allTags={allTags}
                            />
                        </div>
                        <h3 className="flex flex-wrap items-center gap-2 text-sm text-neutral-500">
                            {company.industry ? (
                                <Link
                                    href={`/records/companies?industry=${company.industry}`}
                                    className="rounded-md bg-neutral-100 px-2 py-1 text-neutral-500 transition-colors duration-200 hover:bg-brand-hover hover:text-white"
                                >
                                    {company.industry}
                                </Link>
                            ) : null}
                        </h3>
                    </div>
                </div>

                <div className="flex flex-col items-end gap-2">
                    <span className="text-xs font-medium tracking-[0.12em] text-neutral-500 uppercase">
                        {t("pastRelations")}
                    </span>
                    {interactionUsers.length === 0 ? (
                        <span className="text-xs text-neutral-400">{t("noRecordedInteractions")}</span>
                    ) : (
                        <AvatarGroup>
                            {interactionUsers.map((user) => (
                                <Tooltip key={user.id}>
                                    <TooltipTrigger asChild>
                                        <Link href={`/users/${user.id}`}>
                                            <Avatar className="h-12 w-12 bg-neutral-500">
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
                <CompanyActionsMenu company={company} />
            </div>

            <div className="mt-8 grid grid-cols-1 gap-8 md:grid-cols-[minmax(0,1fr)_minmax(0,2fr)] md:min-h-0 md:flex-1">
                <aside>
                    <div className="mb-3 flex h-8 items-center">
                        <h2 className="px-6 text-xs font-medium tracking-[0.12em] text-neutral-500 uppercase">
                            {t("profile")}
                        </h2>
                    </div>
                    <dl className="divide-y divide-neutral-200 overflow-hidden rounded-2xl bg-neutral-100 ring-1 ring-black/5">
                        <InfoRow label={t("website")} value={company.website ?? ''} />
                        <InfoRow label={t("phone")} value={company.phone ?? ''} />
                        <InfoRow label={t("address")} value={company.address ?? ''} />
                        <InfoRow label={t("industry")} value={company.industry ?? ''} />
                        <InfoRow label={t("added")} value={formatDate(company.createdAt)} />
                        <InfoRow label={t("updated")} value={formatDateTime(company.updatedAt)} />
                    </dl>
                </aside>

                <section className="md:flex md:min-h-0 md:flex-col">
                    <div className="mb-3 flex h-8 items-center">
                        <h2 className="px-6 text-xs font-medium tracking-[0.12em] text-neutral-500 uppercase">
                            {t("theirActivity")}
                        </h2>
                    </div>
                    <div className="grid grid-cols-3 gap-3">
                        <Suspense fallback={<div>{t("loading")}</div>}>
                            <ContactStatCard
                                label={t("activities")}
                                value={activities.length}
                                subtitle={notes.length > 0 ? t("notesSubtitle", { count: notes.length }) : undefined}
                                viewHref={`/activity?companyId=${company.id}`}
                            />
                        </Suspense>
                        <Suspense fallback={<div>{t("loading")}</div>}>
                            <ContactStatCard
                                label={t("tasks")}
                                value={tasks.length}
                                subtitle={tasks.length > 0 ? t("openTasksSubtitle", { count: openTasks }) : undefined}
                                viewHref={`/activity/tasks?companyId=${company.id}`}
                            />
                        </Suspense>
                        <Suspense fallback={<div>{t("loading")}</div>}>
                            <ContactStatCard
                                label={t("deals")}
                                value={deals.length}
                                subtitle={deals.length > 0 ? t("dealsSubtitle", { count: deals.length }) : undefined}
                                viewHref={`/activity/deals?companyId=${company.id}`}
                            />
                        </Suspense>
                    </div>

                    <div className="mt-6 grid grid-cols-1 gap-3 md:grid-cols-3">
                        <Suspense fallback={<div>{t("loading")}</div>}>
                            <EngagementSparkline data={weeklyEngagement} />
                            <RevenueTiles pastRevenue={pastRevenue} projectedRevenue={projectedRevenue} />
                        </Suspense>
                    </div>

                    {/* pipeline goes here */}
                    <Suspense fallback={<div>{t("loading")}</div>}>
                        <PipelineCard deals={deals} render="active" />
                    </Suspense>

                    {/* <div className="mt-6 mb-3 flex h-8 items-center justify-between">
                        <h2 className="px-6 text-xs font-medium tracking-[0.12em] text-neutral-500 uppercase">
                            Contacts
                        </h2>
                        <button onClick={() => {
                            // open NewContactDialog
                        }}>
                            <PlusIcon className="size-4" />
                        </button>
                    </div>
                    {people.length === 0 ? (
                        <div className="overflow-hidden rounded-2xl bg-neutral-100 ring-1 ring-black/5">
                            <p className="px-6 py-6 text-sm text-neutral-500">No contacts associated with this company.</p>
                        </div>
                    ) : (
                        <ul className="grid grid-cols-2 gap-3 sm:grid-cols-3">
                            {people.map((person) => (
                                console.log(person),
                                // <li key={person.id}>
                                //     <Link
                                //         href={`/records/contacts/${person.id}`}
                                //         className="group flex flex-col items-start gap-3 rounded-2xl bg-neutral-100 p-4 ring-1 ring-black/5 transition hover:bg-neutral-200/60"
                                //     >
                                //         <ContactAvatar contact={person} type="large" />
                                //         <div className="min-w-0">
                                //             <p className="truncate text-sm font-semibold text-black">{person.name}</p>
                                //             {person.title ? (
                                //                 <p className="mt-0.5 truncate text-xs uppercase tracking-wide text-neutral-500">
                                //                     {person.title}
                                //                 </p>
                                //             ) : null}
                                //             <p className="mt-1 truncate text-xs text-neutral-500">{company.name}</p>
                                //         </div>
                                //     </Link>
                                // </li>
                                <Suspense key={person.id} fallback={<div>Loading...</div>}>
                                    <ContactCard
                                        key={person.id}
                                        id={person.id}
                                        name={person.name}
                                        title={person.title}
                                        imageUrl={person.imageUrl}
                                        company={company.name}
                                        companyId={person.companyId ?? company.id}
                                        email={person.email}
                                        tags={person.tagIds?.map((tagId) => allTags.find((t) => t.id === tagId))?.filter((t): t is Tag => t !== undefined) ?? []}
                                        phone={person.phone}
                                    // onQuickEdit={onQuickEdit ? () => onQuickEdit(person) : undefined}
                                    // onDelete={onDelete ? () => onDelete(person) : undefined}
                                    />
                                </Suspense>
                            ))}
                        </ul>
                    )} */}
                    <Suspense fallback={<Skeleton className="w-full h-full" />}>
                        <ContactsGrid contacts={people} company={company} allTags={allTags} />
                    </Suspense>

                    <div className="mt-6 mb-3 flex h-8 items-center">
                        <h2 className="px-6 text-xs font-medium tracking-[0.12em] text-neutral-500 uppercase">
                            {t("timeline")}
                        </h2>
                    </div>
                    <div className="overflow-hidden rounded-2xl bg-white ring-1 ring-black/5 md:flex md:min-h-0 md:flex-1 md:flex-col">
                        <div className="md:min-h-0 md:flex-1 md:overflow-y-auto md:[-webkit-mask-image:linear-gradient(to_bottom,transparent_0,black_24px)] md:[mask-image:linear-gradient(to_bottom,transparent_0,black_24px)]">
                            <Timeline tasks={tasks} activities={activities} notes={notes} />
                        </div>
                    </div>
                </section>
            </div>
        </div>
    );
}