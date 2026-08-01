import { cookies } from "next/headers";
import Link from "next/link";
import { Suspense } from "react";
import { notFound, redirect } from "next/navigation";
import AccessDeniedPage from "@/app/components/AccessDeniedPage";
import { loadRecord } from "@/app/lib/recordAccess";
import { CrumbLabel } from "@/app/hooks/useNavTrail";
import RecentRecordBridge from "@/app/components/actions/RecentRecordBridge";
import { getLocale, getTranslations } from "next-intl/server";
import { ArrowLeftIcon, UserIcon } from "@heroicons/react/24/outline";
import PipelineCard from "@/app/components/records/PipelineCard";
import { Skeleton } from "@/components/ui/skeleton";

import {
    getAttachmentsFromCookie,
    getCompanyById,
    getCompanyDeals,
    getCompanyEngagement,
    getCompanyPeople,
    getCompanyTags,
    getCompanyTimeline,
    getCompanyEvidence,
    getCurrentUserFromCookie,
    getEntityCustomFieldsFromCookie,
    getTags,
    getUserReferences,
} from "@/app/lib/api";
import { type Company, type Contact, type Deal, type Tag } from "@/app/lib/types";
import { formatDate, formatDateTime } from "@/app/lib/utils";

import Rise from "@/app/components/motion/Rise";
import SectionHeader from "@/app/components/dashboard/SectionHeader";
import CompanyActionsMenu from "@/app/components/records/companies/CompanyActionsMenu";
import CompanyAvatar from "@/app/components/records/companies/CompanyAvatar";
import { EngagementSparkline, RevenueTiles } from "@/app/components/records/companies/CompanyCard";
import ContactStatCard from "@/app/components/records/contacts/ContactStatCard";
import TagEditor from "@/app/components/records/contacts/TagEditor";
import InfoRow from "@/app/components/me/InfoRow";
import Timeline from "@/app/components/me/Timeline";
import { Avatar, AvatarFallback, AvatarGroup, AvatarImage } from "@/components/ui/avatar";
import { Tooltip, TooltipContent, TooltipTrigger } from "@/components/ui/tooltip";
import ContactsGrid from "@/app/components/records/companies/ContactsGrid";
import Attachments from "@/app/components/attachments/Attachments";
import CustomFieldRows from "@/app/components/records/CustomFieldRows";
import RelationshipEvidencePanel from "@/app/components/records/RelationshipEvidencePanel";
import RecordStickyContext from "@/app/components/records/RecordStickyContext";
import RecordReturnLink from "@/app/components/records/RecordReturnLink";
import { resolveRecordReturnPath } from "@/app/lib/recordReturnPath";

type CompanyPageProps = {
    params: Promise<{ id: number }>;
    searchParams: Promise<{ returnTo?: string | string[] }>;
};

export default async function CompanyPage({ params, searchParams }: CompanyPageProps) {
    const [{ id }, query] = await Promise.all([params, searchParams]);
    const returnPath = resolveRecordReturnPath("companies", query.returnTo);
    const cookie = (await cookies()).toString();
    const init = { headers: { cookie } } as const;

    const [
        t,
        locale,
        companyAccess,
        currentUser,
        allTags,
        people,
        deals,
        companyTags,
        engagement,
        timeline,
        attachments,
        customFields,
        evidence,
    ] = await Promise.all([
        getTranslations("CompaniesDetail"),
        getLocale(),
        loadRecord<Company>(() => getCompanyById(id, init)),
        getCurrentUserFromCookie(cookie),
        getTags(init).catch(() => [] as Tag[]),
        getCompanyPeople(id, init).catch(() => [] as Contact[]),
        getCompanyDeals(id, init).catch(() => [] as Deal[]),
        getCompanyTags(id, init).catch(() => [] as Tag[]),
        getCompanyEngagement(id, init),
        getCompanyTimeline(id, 100, init),
        getAttachmentsFromCookie("company", id, cookie),
        getEntityCustomFieldsFromCookie("company", id, cookie),
        getCompanyEvidence(id, init).catch(() => null),
    ]);

    if (companyAccess.kind === "forbidden") {
        return <AccessDeniedPage />;
    }
    if (companyAccess.kind === "missing") {
        notFound();
    }
    const company = companyAccess.record;
    const websiteUrl = company.website ? (/^https?:\/\//i.test(company.website) ? company.website : `https://${company.website}`) : undefined;
    if (!currentUser) {
        redirect('/auth/login');
    }

    const { activities, tasks, notes } = timeline;
    const relatedUserIds = new Set<number>([
        ...engagement.relatedUserIds,
        ...activities.map((activity) => activity.createdById),
        ...tasks.map((task) => task.assignedToId),
        ...notes.map((note) => note.author),
        company.ownerId,
    ].filter((userId): userId is number => typeof userId === "number"));
    const relatedUsers = await getUserReferences([...relatedUserIds], init).catch(() => []);
    const interactionUserIds = new Set(engagement.relatedUserIds);
    const interactionUsers = relatedUsers.filter((user) => interactionUserIds.has(user.id));

    return (
        <div className="min-h-full bg-background px-2 pt-8 pb-12">
            <div className="mx-auto flex w-full max-w-5xl flex-col gap-10">
                <RecordStickyContext
                    anchorId="company-record-identity"
                    backHref={returnPath}
                    backLabel={t("backToAll")}
                    name={company.name}
                    temperature={evidence?.temperature}
                />
                <Rise>
                    <RecordReturnLink
                        href={returnPath}
                        className="inline-flex items-center gap-2 text-base text-brand hover:text-brand-hover w-fit"
                    >
                        <ArrowLeftIcon className="h-4 w-4" />
                        <span>{t("backToAll")}</span>
                    </RecordReturnLink>

                    <CrumbLabel value={company.name} />
                    <RecentRecordBridge type="company" id={company.id} label={company.name} />
                    <header id="company-record-identity" className="mt-8 flex flex-wrap items-center justify-between gap-6">
                        <div className="flex items-center gap-6 py-8">
                            <CompanyAvatar company={company} type="2xlarge" />
                            <div className="flex flex-col gap-2">
                                <div className="flex flex-row flex-wrap items-center gap-3">
                                    <h1 className="text-4xl font-extrabold tracking-tight text-foreground">
                                        {company.name}
                                    </h1>
                                    <TagEditor
                                        companyId={company.id}
                                        currentTags={companyTags}
                                        allTags={allTags}
                                    />
                                </div>
                                <h3 className="flex flex-wrap items-center gap-2 text-sm text-muted-foreground">
                                    {company.industry ? (
                                        <Link
                                            href={`/records/companies?industry=${company.industry}`}
                                            className="rounded-md bg-muted px-2 py-1 text-muted-foreground transition-colors duration-200 hover:bg-brand-hover hover:text-brand-foreground"
                                        >
                                            {company.industry}
                                        </Link>
                                    ) : null}
                                </h3>
                            </div>
                        </div>

                        <div className="flex flex-col items-end gap-2">
                            <span className="text-xs font-medium tracking-[0.12em] text-muted-foreground uppercase">
                                {t("pastRelations")}
                            </span>
                            {interactionUsers.length === 0 ? (
                                <span className="text-xs text-muted-foreground">{t("noRecordedInteractions")}</span>
                            ) : (
                                <AvatarGroup>
                                    {interactionUsers.map((user) => (
                                        <Tooltip key={user.id}>
                                            <TooltipTrigger asChild>
                                                <Link href={`/users/${user.id}`}>
                                                    <Avatar className="h-12 w-12 bg-muted-foreground/40">
                                                        {user.profilePictureUrl ? (
                                                            <AvatarImage
                                                                src={user.profilePictureUrl}
                                                                alt={user.displayName || user.username || ""}
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
                                                {user.displayName || user.username || ""}
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
                    <RelationshipEvidencePanel evidence={evidence} />
                </Rise>

                <div className="grid grid-cols-1 gap-8 md:grid-cols-[minmax(0,1fr)_minmax(0,2fr)]">
                    <Rise delay={0.06}>
                        <aside>
                            <SectionHeader title={t("profile")} />
                            <dl className="divide-y divide-border overflow-hidden rounded-2xl border border-border bg-card">
                                <InfoRow label={t("website")} value={company.website ?? ''} href={websiteUrl} />
                                <InfoRow label={t("phone")} value={company.phone ?? ''} />
                                <InfoRow label={t("address")} value={company.address ?? ''} />
                                <InfoRow label={t("industry")} value={company.industry ?? ''} />
                                <InfoRow
                                    label={t("owner")}
                                    value={company.ownerId != null
                                        ? relatedUsers.find((user) => user.id === company.ownerId)?.displayName ?? ''
                                        : t("ownerUnassigned")}
                                />
                                <InfoRow label={t("added")} value={formatDate(company.createdAt, locale)} />
                                <InfoRow label={t("updated")} value={formatDateTime(company.updatedAt, locale)} />
                                <CustomFieldRows entityType="company" entityId={company.id} initialEntries={customFields} />
                            </dl>

                            <Attachments
                                entityType="company"
                                entityId={company.id}
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
                                    value={engagement.numActivities}
                                    subtitle={engagement.numNotes > 0 ? t("notesSubtitle", { count: engagement.numNotes }) : undefined}
                                    viewHref={`/activity/all?companyId=${company.id}`}
                                />
                                <ContactStatCard
                                    label={t("tasks")}
                                    value={engagement.numTasks}
                                    subtitle={engagement.numTasks > 0 ? t("openTasksSubtitle", { count: engagement.openTasks }) : undefined}
                                    viewHref={`/activity/tasks?companyId=${company.id}`}
                                />
                                <ContactStatCard
                                    label={t("deals")}
                                    value={engagement.numDeals}
                                    subtitle={engagement.numDeals > 0 ? t("dealsSubtitle", { count: engagement.numDeals }) : undefined}
                                    viewHref={`/activity/deals?companyId=${company.id}`}
                                />
                            </div>

                            <div className="mt-6 grid grid-cols-1 gap-3 md:grid-cols-3">
                                <EngagementSparkline data={engagement.weeklyEngagement} />
                                <RevenueTiles pastRevenue={engagement.pastRevenue} projectedRevenue={engagement.projectedRevenue} currency={engagement.currency} />
                            </div>

                            <PipelineCard deals={deals} render="active" />

                            <Suspense fallback={<Skeleton className="mt-6 h-40 w-full rounded-2xl" />}>
                                <ContactsGrid contacts={people} company={company} allTags={allTags} />
                            </Suspense>

                            <div className="mt-6">
                                <SectionHeader title={t("timeline")} />
                                <div className="overflow-hidden rounded-2xl border border-border bg-card">
                                    <Timeline
                                        tasks={tasks}
                                        activities={activities}
                                        notes={notes}
                                        users={relatedUsers}
                                        persons={people}
                                        deals={deals}
                                        currentUserId={currentUser.id}
                                        companyId={company.id}
                                        limit={100}
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
