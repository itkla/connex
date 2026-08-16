import { getAttachmentsFromCookie, getContactById, getContactLifecycle, getContactLifecycleHistory, getContactConnections, getContactEmployment, getContactEvidence, getContactIntroPath, getContextNotifications, getCurrentUserResultFromCookie, getEffectivePermissionsFromCookie, getEntityCustomFieldsFromCookie, getTags, getUserReferences } from "@/app/lib/api";
import { notFound, redirect } from "next/navigation";
import AccessDeniedPage from "@/app/components/AccessDeniedPage";
import WorkspaceUnavailablePage from "@/app/components/WorkspaceUnavailablePage";
import { loadRecord } from "@/app/lib/recordAccess";
import { CrumbLabel } from "@/app/hooks/useNavTrail";
import ActionRecordBridge from "@/app/components/actions/ActionRecordBridge";
import RecentRecordBridge from "@/app/components/actions/RecentRecordBridge";
import { type Tag, type Contact, type IntroPath, type PersonConnection, type PersonEmployment } from "@/app/lib/types";
import { cookies } from "next/headers";
import Link from "next/link";
import { UserIcon } from "@heroicons/react/24/outline";
import { getLocale, getTranslations } from "next-intl/server";

import Rise from "@/app/components/motion/Rise";
import { PageShell } from "@/app/components/PageShell";
import SectionHeader from "@/app/components/dashboard/SectionHeader";
import ContactActionsMenu from "@/app/components/records/contacts/ContactActionsMenu";
import ContactAvatar from "@/app/components/records/contacts/ContactAvatar";
import ContactConnections from "@/app/components/records/contacts/ContactConnections";
import ContactStatCard from "@/app/components/records/contacts/ContactStatCard";
import TemperatureEvidenceChip from "@/app/components/records/TemperatureEvidenceChip";
import NewActivityDialog from "@/app/components/records/contacts/NewActivityDialog";
import NewTaskDialog from "@/app/components/records/contacts/NewTaskDialog";
import TagEditor from "@/app/components/records/contacts/TagEditor";
import { Avatar, AvatarFallback, AvatarGroup, AvatarImage } from "@/components/ui/avatar";
import InfoRow from "@/app/components/me/InfoRow";
import Timeline from "@/app/components/me/Timeline";
import Attachments from "@/app/components/attachments/Attachments";
import CommentsSection from "@/app/components/records/comments/CommentsSection";
import CustomFieldRows from "@/app/components/records/CustomFieldRows";
import ContactLifecyclePanel from "@/app/components/records/contacts/ContactLifecyclePanel";
import ContactProvenancePanel from "@/app/components/records/contacts/ContactProvenancePanel";
import EngineEvaluationPanel from "@/app/components/records/EngineEvaluationPanel";
import RecordDetailSection from "@/app/components/records/RecordDetailSection";
import { formatCompactCurrency, formatDate, formatDateTime, formatShortDate } from "@/app/lib/utils";
import { Tooltip, TooltipTrigger, TooltipContent } from "@/components/ui/tooltip";
import EntityNotificationBanner from "@/app/components/notifications/EntityNotificationBanner";

type ContactPageProps = {
    params: Promise<{ id: number }>;
};

export default async function ContactPage({ params }: ContactPageProps) {
    const { id } = await params;
    const cookieStore = await cookies();
    const cookie = cookieStore.toString();
    const activeWorkspaceCookie = cookieStore.get("connex_workspace")?.value;
    const activeWorkspaceId = activeWorkspaceCookie ? Number(activeWorkspaceCookie) : null;
    const init = { headers: { cookie } } as const;
    const currentUserResult = await getCurrentUserResultFromCookie(cookie);
    if (!currentUserResult.ok) {
        return <WorkspaceUnavailablePage />;
    }
    const currentUser = currentUserResult.data;
    if (!currentUser) {
        redirect('/auth/login');
    }

    const [t, locale, contactAccess, allTags, attachments, notificationPage, employment, connections, introPath, customFields, evidence, effectivePermissions, lifecycle, lifecycleHistory] = await Promise.all([
        getTranslations("ContactsPage"),
        getLocale(),
        loadRecord<Contact>(() => getContactById(id, init)),
        getTags(init).catch(() => [] as Tag[]),
        getAttachmentsFromCookie("person", id, cookie),
        getContextNotifications("person", id, init).catch(() => ({
            items: [],
            total: 0,
            stateVersion: 0,
            asOf: "1970-01-01T00:00:00Z",
        })),
        getContactEmployment(id, init).catch(() => [] as PersonEmployment[]),
        getContactConnections(id, init).catch(() => [] as PersonConnection[]),
        getContactIntroPath(id, init).catch(() => ({ reachable: false, directlyKnown: false, steps: [] }) as IntroPath),
        getEntityCustomFieldsFromCookie("person", id, cookie),
        getContactEvidence(id, init).catch(() => null),
        getEffectivePermissionsFromCookie(cookie),
        getContactLifecycle(id, init).catch(() => null),
        getContactLifecycleHistory(id, init).catch(() => []),
    ]);
    if (contactAccess.kind === "forbidden") {
        return <AccessDeniedPage />;
    }
    if (contactAccess.kind === "missing") {
        notFound();
    }
    const contact = contactAccess.record;
    const referrer = contact.referrerPersonId != null
        ? await getContactById(contact.referrerPersonId, init).catch(() => null)
        : null;

    const tasks = contact.tasks ?? [];
    const activities = contact.activities ?? [];
    const notes = contact.notes ?? [];
    const deals = contact.deals ?? [];
    const openTasks = tasks.filter((task) => !task.completed).length;
    const ownsContact = activeWorkspaceId !== null
        && Number.isFinite(activeWorkspaceId)
        && contact.workspaceId === activeWorkspaceId;

    const interactionUserIds = new Set<number>([
        ...activities.map((activity) => activity.createdById),
        ...notes.map((note) => note.author),
        ...tasks.map((task) => task.assignedToId),
        ...lifecycleHistory.map((entry) => entry.changedById),
    ].filter((value): value is number => typeof value === "number"));
    const userIds = new Set(interactionUserIds);
    if (contact.ownerId != null) userIds.add(contact.ownerId);
    const relatedUsers = await getUserReferences([...userIds], init).catch(() => []);
    const interactionUsers = relatedUsers.filter((user) => interactionUserIds.has(user.id));
    const owner = contact.ownerId != null
        ? relatedUsers.find((user) => user.id === contact.ownerId) ?? null
        : null;

    return (
        <PageShell tier="wide">
                <Rise>
                    <CrumbLabel value={contact.name} />
                    <ActionRecordBridge type="person" id={contact.id} label={contact.name} />
                    <RecentRecordBridge type="person" id={contact.id} label={contact.name} />
                    <RecordDetailSection recordKind="contact" section="identity">
                        <header
                            id="contact-record-identity"
                            className="flex flex-col gap-6 py-4 sm:flex-row sm:items-end sm:justify-between"
                        >
                            <div className="flex min-w-0 items-center gap-6">
                                <ContactAvatar contact={contact} type="xlarge" />
                                <div className="flex min-w-0 flex-col gap-3">
                                    <div className="flex flex-row flex-wrap items-center gap-3">
                                        <h1 className="text-balance text-4xl font-extrabold tracking-tight text-foreground">
                                            {contact.name}
                                        </h1>
                                        {contact.suspendedAt ? (
                                            <span className="shrink-0 rounded-full border border-destructive/30 bg-destructive/10 px-2 py-0.5 text-[10px] font-medium uppercase tracking-wider text-destructive">
                                                {t("processingSuspended")}
                                            </span>
                                        ) : null}
                                        {contact.provisionCeasedAt ? (
                                            <span className="shrink-0 rounded-full border border-destructive/30 bg-destructive/10 px-2 py-0.5 text-[10px] font-medium uppercase tracking-wider text-destructive">
                                                {t("provisionCeased")}
                                            </span>
                                        ) : null}
                                        <TagEditor
                                            contactId={contact.id}
                                            currentTags={contact.tags ?? []}
                                            allTags={allTags}
                                        />
                                    </div>
                                    <h3 className="flex flex-wrap items-center gap-2 text-sm text-muted-foreground">
                                        {contact.title ? (
                                            <Link href={`/records/contacts?title=${contact.title}`} className="rounded-md bg-muted px-2 py-1 text-muted-foreground transition-colors duration-200 hover:bg-brand-hover hover:text-brand-foreground">
                                                {contact.title}
                                            </Link>
                                        ) : null}
                                        {contact.company?.name ? (
                                            <>
                                                <span>@</span>
                                                <Link
                                                    href={`/records/companies/${contact.company.id}`}
                                                    className="rounded-md bg-muted px-2 py-1 text-muted-foreground transition-colors duration-200 hover:bg-brand-hover hover:text-brand-foreground"
                                                >
                                                    {contact.company.name}
                                                </Link>
                                            </>
                                        ) : null}
                                        {evidence ? (
                                            <TemperatureEvidenceChip evidence={evidence} />
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
                    </RecordDetailSection>

                    <RecordDetailSection recordKind="contact" section="actions" className="mt-4 flex justify-end">
                        <ContactActionsMenu
                            contact={contact}
                            currentUserId={currentUser.id}
                            dealSeeds={deals}
                        />
                    </RecordDetailSection>
                    <RecordDetailSection recordKind="contact" section="notifications" className="mt-6">
                        <EntityNotificationBanner
                            key={`${notificationPage.stateVersion}:${notificationPage.items.map((item) => item.id).join(",")}`}
                            initialNotifications={notificationPage.items}
                            contextType="person"
                            contextId={id}
                            initialStateVersion={notificationPage.stateVersion}
                        />
                    </RecordDetailSection>
                </Rise>

                <div className="grid grid-cols-1 gap-8 xl:grid-cols-[minmax(16rem,20rem)_minmax(0,1fr)] xl:items-start">
                    <aside className="flex flex-col gap-6">
                        <RecordDetailSection recordKind="contact" section="profile" className="flex flex-col gap-6">
                            <div>
                                <SectionHeader title={t("profile")} />
                                <dl className="divide-y divide-border overflow-hidden rounded-2xl border border-border bg-card">
                                    <InfoRow label={t("email")} value={contact.email ?? ''} />
                                    <InfoRow label={t("phone")} value={contact.phone ?? ''} />
                                    <InfoRow label={t("title")} value={contact.title ?? ''} />
                                    <InfoRow label={t("company")} value={contact.company?.name ?? t("companyPlaceholder")} />
                                    <InfoRow
                                        label={t("owner")}
                                        value={contact.ownerId != null ? owner?.displayName ?? '' : t("ownerUnassigned")}
                                    />
                                    <InfoRow label={t("added")} value={formatDate(contact.createdAt, locale)} />
                                    <InfoRow label={t("updated")} value={formatDateTime(contact.updatedAt, locale)} />
                                    <CustomFieldRows entityType="person" entityId={contact.id} initialEntries={customFields} />
                                </dl>
                            </div>

                            {employment.length > 0 ? (
                                <div>
                                    <SectionHeader title={t("employmentHistory")} />
                                    <ol className="divide-y divide-border overflow-hidden rounded-2xl border border-border bg-card">
                                        {employment.map((stint) => (
                                            <li key={stint.id} className="px-6 py-4 xl:px-4">
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
                                                    {stint.current ? (
                                                        <span className="shrink-0 rounded-full bg-brand-light px-2 py-0.5 text-[10px] font-medium uppercase tracking-wider text-brand-dark">
                                                            {t("current")}
                                                        </span>
                                                    ) : null}
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
                            ) : null}
                        </RecordDetailSection>

                        {ownsContact ? (
                            <ContactProvenancePanel
                                contactId={contact.id}
                                ownerWorkspaceId={contact.workspaceId}
                                leadSource={contact.leadSource ?? null}
                                leadSourceDetail={contact.leadSourceDetail ?? null}
                                referrerPersonId={contact.referrerPersonId ?? null}
                                referrer={referrer}
                                canEdit={effectivePermissions.includes("PERSON_UPDATE")
                                    && !contact.archivedAt
                                    && !contact.suspendedAt
                                    && !contact.provisionCeasedAt}
                                className="mt-0"
                            />
                        ) : null}

                        {ownsContact && lifecycle ? (
                            <ContactLifecyclePanel
                                contactId={contact.id}
                                lifecycle={lifecycle}
                                canEdit={effectivePermissions.includes("PERSON_UPDATE")
                                    && !contact.archivedAt
                                    && !contact.suspendedAt
                                    && !contact.provisionCeasedAt}
                                hasLinkedDeal={deals.length > 0}
                                className="mt-0"
                            />
                        ) : null}

                        {ownsContact ? (
                            <EngineEvaluationPanel
                                kind="contact"
                                id={contact.id}
                                riskExcluded={contact.riskExcluded ?? false}
                                introExcluded={contact.introExcluded ?? false}
                                className="mt-0"
                            />
                        ) : null}
                    </aside>

                    <Rise delay={0.06} className="flex min-w-0 flex-col gap-8">
                        <RecordDetailSection recordKind="contact" section="metrics" aria-label={t("theirActivity")}>
                            <SectionHeader title={t("theirActivity")} />
                            <div className="grid grid-cols-1 gap-3 sm:grid-cols-3">
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
                        </RecordDetailSection>

                        <RecordDetailSection recordKind="contact" section="activity">
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
                        </RecordDetailSection>

                        <RecordDetailSection recordKind="contact" section="related">
                            <SectionHeader title={t("connections")} />
                            <ContactConnections
                                contactId={contact.id}
                                contactName={contact.name}
                                initialConnections={connections}
                                initialIntroPath={introPath}
                            />
                        </RecordDetailSection>
                    </Rise>
                </div>

                <Rise delay={0.1}>
                    <RecordDetailSection recordKind="contact" section="comments">
                        <CommentsSection
                            key={`person-${contact.id}`}
                            targetType="person"
                            targetId={contact.id}
                            currentUser={{ id: currentUser.id, displayName: currentUser.displayName, profilePictureUrl: currentUser.profilePictureUrl }}
                            canComment={effectivePermissions.includes("COMMENT_CREATE")}
                            canModerate={effectivePermissions.includes("COMMENT_MODERATE")}
                        />
                    </RecordDetailSection>
                </Rise>

                <Rise delay={0.12}>
                    <RecordDetailSection recordKind="contact" section="files">
                        <Attachments
                            entityType="person"
                            entityId={contact.id}
                            initialAttachments={attachments}
                        />
                    </RecordDetailSection>
                </Rise>

                <Rise delay={0.16}>
                    <RecordDetailSection recordKind="contact" section="history">
                        <SectionHeader title={t("timeline")} />
                        <div className="overflow-hidden rounded-2xl border border-border bg-card">
                            <Timeline
                                tasks={tasks}
                                activities={activities}
                                notes={notes}
                                users={interactionUsers}
                                persons={[contact]}
                                deals={deals}
                                lifecycleHistory={ownsContact ? lifecycleHistory : []}
                                currentUserId={currentUser.id}
                                companyId={contact.companyId ?? contact.company?.id ?? null}
                            />
                        </div>
                    </RecordDetailSection>
                </Rise>
        </PageShell>
    );
}
