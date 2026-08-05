import { getAttachmentsFromCookie, getContactById, getContactConnections, getContactEmployment, getContactEvidence, getContactIntroPath, getContextNotifications, getCurrentUserFromCookie, getEntityCustomFieldsFromCookie, getTags, getUserReferences } from "@/app/lib/api";
import { notFound, redirect } from "next/navigation";
import AccessDeniedPage from "@/app/components/AccessDeniedPage";
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
import NewActivityDialog from "@/app/components/records/contacts/NewActivityDialog";
import NewTaskDialog from "@/app/components/records/contacts/NewTaskDialog";
import TagEditor from "@/app/components/records/contacts/TagEditor";
import { Avatar, AvatarFallback, AvatarGroup, AvatarImage } from "@/components/ui/avatar";
import InfoRow from "@/app/components/me/InfoRow";
import Timeline from "@/app/components/me/Timeline";
import Attachments from "@/app/components/attachments/Attachments";
import CustomFieldRows from "@/app/components/records/CustomFieldRows";
import EngineEvaluationPanel from "@/app/components/records/EngineEvaluationPanel";
import RecordDetailSection from "@/app/components/records/RecordDetailSection";
import { formatCompactCurrency, formatDate, formatDateTime, formatShortDate } from "@/app/lib/utils";
import { Tooltip, TooltipTrigger, TooltipContent } from "@/components/ui/tooltip";
import EntityNotificationBanner from "@/app/components/notifications/EntityNotificationBanner";
import RelationshipEvidencePanel from "@/app/components/records/RelationshipEvidencePanel";
import RecordStickyContext from "@/app/components/records/RecordStickyContext";
import TemperaturePill from "@/app/components/records/TemperaturePill";

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

    const [t, locale, contactAccess, currentUser, allTags, attachments, notificationPage, employment, connections, introPath, customFields, evidence] = await Promise.all([
        getTranslations("ContactsPage"),
        getLocale(),
        loadRecord<Contact>(() => getContactById(id, init)),
        getCurrentUserFromCookie(cookie),
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
    ]);
    if (contactAccess.kind === "forbidden") {
        return <AccessDeniedPage />;
    }
    if (contactAccess.kind === "missing") {
        notFound();
    }
    const contact = contactAccess.record;
    if (!currentUser) {
        redirect('/auth/login');
    }

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
    ].filter((value): value is number => typeof value === "number"));
    const userIds = new Set(interactionUserIds);
    if (contact.ownerId != null) userIds.add(contact.ownerId);
    const relatedUsers = await getUserReferences([...userIds], init).catch(() => []);
    const interactionUsers = relatedUsers.filter((user) => interactionUserIds.has(user.id));
    const owner = contact.ownerId != null
        ? relatedUsers.find((user) => user.id === contact.ownerId) ?? null
        : null;

    return (
        <PageShell tier="reading">
                <RecordStickyContext
                    anchorId="contact-record-identity"
                    name={contact.name}
                    temperature={evidence?.temperature}
                />
                <Rise>
                    <CrumbLabel value={contact.name} />
                    <ActionRecordBridge type="person" id={contact.id} label={contact.name} />
                    <RecentRecordBridge type="person" id={contact.id} label={contact.name} />
                    <RecordDetailSection recordKind="contact" section="identity">
                        <header id="contact-record-identity" className="flex flex-wrap items-center justify-between gap-6">
                            <div className="flex items-center gap-6 py-8">
                                <ContactAvatar contact={contact} type="xlarge" />
                                <div className="flex flex-col gap-2">
                                    <div className="flex flex-row flex-wrap items-center gap-3">
                                        <h1 className="text-4xl font-extrabold tracking-tight text-foreground">
                                            {contact.name}
                                        </h1>
                                        {evidence?.temperature ? (
                                            <TemperaturePill temp={evidence.temperature} />
                                        ) : null}
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
                    <RecordDetailSection recordKind="contact" section="notifications">
                        <EntityNotificationBanner
                            key={`${notificationPage.stateVersion}:${notificationPage.items.map((item) => item.id).join(",")}`}
                            initialNotifications={notificationPage.items}
                            contextType="person"
                            contextId={id}
                            initialStateVersion={notificationPage.stateVersion}
                        />
                    </RecordDetailSection>
                </Rise>

                <Rise delay={0.06}>
                    <div className="grid grid-cols-1 gap-8 md:grid-cols-[minmax(0,1fr)_minmax(0,2fr)]">
                        <RecordDetailSection recordKind="contact" section="profile">
                            <aside>
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
                            </aside>
                        </RecordDetailSection>

                        <RecordDetailSection recordKind="contact" section="metrics" aria-label={t("theirActivity")}>
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
                        </RecordDetailSection>
                    </div>
                </Rise>

                <Rise delay={0.08}>
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
                </Rise>

                <Rise delay={0.1}>
                    <RecordDetailSection recordKind="contact" section="relationship">
                        <RelationshipEvidencePanel evidence={evidence} className="mt-0" />
                        {ownsContact ? (
                            <div className="mt-6">
                                <EngineEvaluationPanel
                                    kind="contact"
                                    id={contact.id}
                                    riskExcluded={contact.riskExcluded ?? false}
                                    introExcluded={contact.introExcluded ?? false}
                                />
                            </div>
                        ) : null}
                    </RecordDetailSection>
                </Rise>

                <Rise delay={0.12}>
                    <RecordDetailSection recordKind="contact" section="related" className="grid grid-cols-1 gap-8 md:grid-cols-2">
                        <div>
                            <SectionHeader title={t("connections")} />
                            <ContactConnections
                                contactId={contact.id}
                                contactName={contact.name}
                                initialConnections={connections}
                                initialIntroPath={introPath}
                            />
                        </div>
                        {employment.length > 0 ? (
                            <div>
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
                </Rise>

                <Rise delay={0.14}>
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
                                currentUserId={currentUser.id}
                                companyId={contact.companyId ?? contact.company?.id ?? null}
                            />
                        </div>
                    </RecordDetailSection>
                </Rise>
        </PageShell>
    );
}
