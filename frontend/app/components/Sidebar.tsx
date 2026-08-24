"use client";

import {
    ArrowRightStartOnRectangleIcon,
    ArrowPathIcon,
    BellIcon,
    BriefcaseIcon,
    BuildingOffice2Icon,
    ChatBubbleLeftRightIcon,
    CheckCircleIcon,
    ChevronDownIcon,
    CubeIcon,
    DocumentDuplicateIcon,
    DocumentTextIcon,
    FlagIcon,
    FolderIcon,
    FunnelIcon,
    HomeIcon,
    InboxIcon,
    MegaphoneIcon,
    ShieldCheckIcon,
    TagIcon,
    UserCircleIcon,
    UserGroupIcon,
    UsersIcon,
    EllipsisVerticalIcon,
    ExclamationTriangleIcon,
    CalendarIcon,
    MapIcon,
    ArrowsRightLeftIcon,
    BookOpenIcon,
    ChartBarIcon,
    PresentationChartLineIcon,
    QuestionMarkCircleIcon,
    GlobeAltIcon,
    ClipboardDocumentListIcon,
    SunIcon,
    MoonIcon,
    ComputerDesktopIcon,
    CheckIcon,
    Cog6ToothIcon,
    BuildingLibraryIcon,
    BoltIcon,
    SignalIcon,
} from "@heroicons/react/24/outline";
import Link from "next/link";
import { usePathname, useRouter, useSearchParams } from "next/navigation";
import { useEffect, useMemo, useRef, useState } from "react";
import { AnimatePresence, motion, useReducedMotion } from "motion/react";
import { easeOut, instant, springSnappy } from "@/app/lib/motion";
import { DropdownMenu } from "radix-ui";
import { type User } from "@/app/lib/types";
// import { BubblesIcon, PanelLeftOpenIcon } from "lucide-react";
import { useLocale, useTranslations } from "next-intl";
import { useTheme } from "next-themes";
import { DropdownMenuItem, DropdownMenuPortal, DropdownMenuRadioGroup, DropdownMenuRadioItem, DropdownMenuSub, DropdownMenuSubContent, DropdownMenuSubTrigger } from "@/components/ui/dropdown-menu";
// import {  } from "@heroicons/react/24/solid";
import { persistAuthenticatedLocale } from '@/app/lib/locale-preference';
import { toastError } from '@/app/lib/toast';
import type { Locale } from '@/i18n/config';
import UserAvatar from '@/app/components/records/users/UserAvatar';
import NotificationBell from '@/app/components/notifications/NotificationBell';
import WorkspaceSwitcher from '@/app/components/WorkspaceSwitcher';
import QuickCreateLauncher from '@/app/components/actions/QuickCreateLauncher';
import { Tooltip, TooltipTrigger, TooltipContent } from '@/components/ui/tooltip';
import { useIsMobile } from '@/app/hooks/useIsMobile';
import { useSidebarMode } from '@/app/hooks/useSidebarMode';
import { cn } from '@/lib/utils';
import { useWorkspace } from '@/app/hooks/useWorkspace';
import { usePinnedViews } from '@/app/hooks/usePinnedViews';
import { useRecentRecords } from '@/app/hooks/useRecentRecords';
import { savedViewHref, savedViewRecordIcon, savedViewRecordPath, savedViewToken } from '@/app/lib/savedViewLink';
import { recentRecordHref } from '@/app/lib/recentRecords';
import type { SidebarSectionId } from '@/app/lib/sidebarSections';
import { CONNECTED_ACCOUNTS_ROUTE } from '@/app/lib/connectedAccountsSections';
import { settingsDestination, type SettingsDestination } from '@/app/lib/settingsEntryPoints';
import type { NavAccess } from '@/app/lib/navAccess';
import { useSidebarSections } from '@/app/hooks/useSidebarSections';
import { useNotifications } from '@/app/hooks/useNotifications';

/** Maximum number of recent records surfaced in the sidebar; the store retains more for the palette. */
const SIDEBAR_RECENTS_LIMIT = 5;

type NavItem = {
    label: string;
    href: string;
    icon: React.ComponentType<{ className?: string }>;
    nested?: boolean;
    /** Overrides path-based active matching for hrefs that carry a query (e.g. pinned saved views). */
    active?: boolean;
    /** Localized explanation shown when the item's capability could not be resolved. */
    availabilityLabel?: string;
};

type NavSection = {
    id: SidebarSectionId;
    label: string;
    items: NavItem[];
    activePaths?: readonly string[];
};

/**
 * A sidebar row for a settings, account, or administration destination (#1340 PR 7).
 *
 * The row carries no route and no label of its own: both come from the manifest entry named at the
 * call site, so a destination that consolidates takes its sidebar row to the canonical address under
 * the canonical name, and the manifest's `entryPoints` describes a registration that exists rather
 * than one somebody remembered to keep in step.
 *
 * @param destination - the manifest destination the row registers
 * @param icon - the row's leading icon, which the manifest does not describe
 * @param translate - resolves an absolute message key in the reader's locale
 * @param extra - row state the manifest does not describe, such as query-aware active matching
 */
function settingsNavItem(
    destination: SettingsDestination,
    icon: NavItem["icon"],
    translate: (key: string) => string,
    extra: Omit<Partial<NavItem>, "href" | "label"> = {},
): NavItem {
    return { label: translate(destination.titleKey), href: destination.href, icon, ...extra };
}

function useSections(navAccess: NavAccess): NavSection[] {
    const t = useTranslations("CommonSidebar");
    const tCapability = useTranslations("CapabilityUnavailable");
    const tManifest = useTranslations();
    const { activeWorkspace } = useWorkspace();
    const isOrgAdmin = activeWorkspace?.orgRole != null;
    const sectionPathname = usePathname() ?? "";
    const sectionSearchParams = useSearchParams();
    const captureReviewsActive =
        sectionPathname === CONNECTED_ACCOUNTS_ROUTE
        && sectionSearchParams.get("panel") === "reviews";
    const workspaceItems: NavItem[] = [
        settingsNavItem(settingsDestination("workspace.people-directory"), UserGroupIcon, tManifest),
        ...(navAccess.workflows
            ? [{ label: t("navWorkflows"), href: "/workflows", icon: BoltIcon }]
            : []),
        ...(navAccess.captureReviews !== "disabled"
            ? [settingsNavItem(settingsDestination("account.capture-reviews"), InboxIcon, tManifest, {
                active: captureReviewsActive,
                availabilityLabel: navAccess.captureReviews === "unavailable"
                    ? tCapability("title")
                    : undefined,
            })]
            : []),
        settingsNavItem(settingsDestination("settings.home"), Cog6ToothIcon, tManifest),
        ...(isOrgAdmin
            ? [settingsNavItem(settingsDestination("organization.administrators"), BuildingLibraryIcon, tManifest)]
            : []),
        ...(navAccess.auditLog
            ? [settingsNavItem(settingsDestination("workspace.audit-log"), ClipboardDocumentListIcon, tManifest)]
            : []),
    ];
    const marketingSection: NavSection = {
        id: "marketing",
        label: t("sectionMarketing"),
        activePaths: ["/marketing"],
        items: [
            { label: t("navCampaigns"), href: "/marketing/campaigns", icon: MegaphoneIcon },
        ],
    };
    return [
        {
            id: "overview",
            label: t("sectionOverview"),
            activePaths: ["/dashboard", "/radar", "/overview"],
            items: [
                { label: t("navDashboard"), href: "/dashboard", icon: HomeIcon },
                { label: t("navRadar"), href: "/radar", icon: SignalIcon },
                { label: t("navCalendar"), href: "/overview/calendar", icon: CalendarIcon },
                { label: t("navMap"), href: "/overview/map", icon: MapIcon },
                { label: t("navIntroductions"), href: "/overview/introductions", icon: ArrowsRightLeftIcon },
                { label: t("navAnalytics"), href: "/overview/analytics", icon: ChartBarIcon },
                { label: t("navReports"), href: "/overview/reports", icon: PresentationChartLineIcon },
                ...(navAccess.goals
                    ? [{ label: t("navGoals"), href: "/overview/reports/goals", icon: FlagIcon }]
                    : []),
            ]
        },
        {
            id: "records",
            label: t("sectionRecords"),
            items: [
                { label: t("navCompanies"), href: "/records/companies", icon: BuildingOffice2Icon },
                { label: t("navContacts"), href: "/records/contacts", icon: UsersIcon },
                { label: t("navDeals"), href: "/records/deals", icon: BriefcaseIcon },
                { label: t("navPipelines"), href: "/records/pipelines", icon: FunnelIcon },
                { label: t("navProducts"), href: "/records/products", icon: CubeIcon },
            ],
        },
        ...(navAccess.campaigns ? [marketingSection] : []),
        {
            id: "activity",
            label: t("sectionActivity"),
            activePaths: ["/activity"],
            items: [
                { label: t("navActivities"), href: "/activity/all", icon: ChatBubbleLeftRightIcon },
                { label: t("navTasks"), href: "/activity/tasks", icon: CheckCircleIcon },
                { label: t("navNotes"), href: "/activity/notes", icon: DocumentTextIcon },
            ],
        },
        {
            id: "library",
            label: t("sectionLibrary"),
            activePaths: ["/library"],
            items: [
                { label: t("navDocuments"), href: "/library/documents", icon: DocumentDuplicateIcon },
                settingsNavItem(
                    settingsDestination("workspace.approval-policies"),
                    ShieldCheckIcon,
                    tManifest,
                    { nested: true },
                ),
                { label: t("navTags"), href: "/library/tags", icon: TagIcon },
                { label: t("navFiles"), href: "/library/files", icon: FolderIcon },
            ],
        },
        {
            id: "workspace",
            label: t("sectionWorkspace"),
            activePaths: ["/users", "/workflows", "/settings", "/organization", "/admin"],
            items: workspaceItems,
        },
        {
            id: "help",
            label: t("sectionHelp"),
            activePaths: ["/docs"],
            items: [{ label: t("navDocs"), href: "/docs", icon: BookOpenIcon }],
        },
    ];
}

/**
 * Whether a row's destination is the page being read.
 *
 * The fragment is dropped first: a consolidated settings destination is addressed at the section
 * that absorbed the job, and a pathname never carries one, so matching the whole href would leave
 * every deep-linked row permanently inactive. `longestMatchingHref` then still resolves the most
 * specific row, so a reader on People & access sees that row marked rather than the Settings row
 * above it.
 */
function isActive(pathname: string, href: string): boolean {
    const path = href.split("#")[0];
    if (path === "/dashboard") return pathname === "/dashboard";
    return pathname === path || pathname.startsWith(`${path}/`);
}

function longestMatchingHref(items: readonly NavItem[], pathname: string): string | null {
    let longestMatch: string | null = null;
    let longestPath = 0;
    for (const item of items) {
        if (item.active !== undefined) continue;
        if (!isActive(pathname, item.href)) continue;
        const pathLength = item.href.split("#")[0].length;
        if (longestMatch === null || pathLength > longestPath) {
            longestMatch = item.href;
            longestPath = pathLength;
        }
    }
    return longestMatch;
}

function NavGroup({
    section,
    pathname,
    rail,
    collapsed,
    navigationKey,
    onCollapsedChange,
}: {
    section: NavSection;
    pathname: string;
    rail: boolean;
    collapsed: boolean;
    navigationKey: string;
    onCollapsedChange: (sectionId: SidebarSectionId, collapsed: boolean) => void;
}) {
    const activeHref = longestMatchingHref(section.items, pathname);
    const itemActive = activeHref !== null || section.items.some((item) => item.active === true);
    const groupActive = itemActive || section.activePaths?.some((href) => isActive(pathname, href)) === true;
    const [manualState, setManualState] = useState<{ navigationKey: string; collapsed: boolean } | null>(null);
    const manualStateCurrent = manualState?.navigationKey === navigationKey && manualState.collapsed === collapsed;
    const open = manualStateCurrent ? !manualState.collapsed : groupActive || !collapsed;
    const sectionId = `nav-group-${section.id}`;
    const reduce = useReducedMotion() ?? false;
    if (rail) {
        return (
            <ul aria-label={section.label} className="flex flex-col items-center gap-1">
                {section.items.map((item) => (
                    <NavLink key={item.href} item={item} active={item.active ?? item.href === activeHref} rail />
                ))}
            </ul>
        );
    }
    return (
        <motion.div
            layout={reduce ? false : "position"}
            transition={reduce ? instant : springSnappy}
        >
            <button
                type="button"
                onClick={() => {
                    const nextOpen = !open;
                    setManualState({ navigationKey, collapsed: !nextOpen });
                    onCollapsedChange(section.id, !nextOpen);
                }}
                aria-expanded={open}
                aria-controls={sectionId}
                className={cn(
                    "flex w-full items-center justify-between rounded-md px-3 py-1 text-xs font-medium uppercase tracking-[0.12em] text-muted-foreground transition hover:text-foreground",
                    groupActive && "text-foreground",
                )}
            >
                <span>{section.label}</span>
                <ChevronDownIcon
                    className={`size-3 transition-transform motion-reduce:transition-none ${open ? "" : "-rotate-90"}`}
                />
            </button>
            <AnimatePresence initial={false} mode="popLayout">
                {open && (
                    <motion.ul
                        key={section.id}
                        id={sectionId}
                        className="mt-1 flex origin-top flex-col gap-0.5"
                        initial={reduce ? false : { opacity: 0, y: -4 }}
                        animate={{ opacity: 1, y: 0 }}
                        exit={{ opacity: 0, y: -4 }}
                        transition={reduce ? instant : { duration: 0.18, ease: easeOut }}
                    >
                        {section.items.map((item) => (
                            <NavLink
                                key={item.href}
                                item={item}
                                active={item.active ?? item.href === activeHref}
                                rail={false}
                            />
                        ))}
                    </motion.ul>
                )}
            </AnimatePresence>
        </motion.div>
    );
}

function NavLink({ item, active, rail }: { item: NavItem; active: boolean; rail: boolean }) {
    const Icon = item.icon;
    const reduce = useReducedMotion();
    if (rail) {
        const link = (
            <Link
                href={item.href}
                aria-current={active ? "page" : undefined}
                aria-label={item.availabilityLabel
                    ? `${item.label}: ${item.availabilityLabel}`
                    : item.label}
                className="group flex justify-center rounded-lg outline-none focus-visible:ring-2 focus-visible:ring-brand"
            >
                <span
                    className={cn(
                        "relative flex size-9 items-center justify-center rounded-lg transition-colors",
                        active
                            ? "text-brand-dark"
                            : "text-muted-foreground group-hover:bg-sidebar-accent group-hover:text-sidebar-accent-foreground",
                    )}
                >
                    {active && (
                        <motion.span
                            layoutId="sidebar-rail-pill"
                            className="absolute inset-0 z-0 rounded-lg bg-brand-light"
                            transition={reduce ? instant : springSnappy}
                        />
                    )}
                    <Icon className="relative z-10 size-4 shrink-0" />
                    {item.availabilityLabel ? (
                        <QuestionMarkCircleIcon
                            aria-hidden
                            className="absolute right-0.5 top-0.5 z-10 size-3 text-muted-foreground"
                        />
                    ) : null}
                </span>
            </Link>
        );
        return (
            <li>
                <Tooltip>
                    <TooltipTrigger asChild>{link}</TooltipTrigger>
                    <TooltipContent side="right">
                        {item.availabilityLabel
                            ? `${item.label} — ${item.availabilityLabel}`
                            : item.label}
                    </TooltipContent>
                </Tooltip>
            </li>
        );
    }
    return (
        <li>
            <Link
                href={item.href}
                aria-current={active ? "page" : undefined}
                className={cn(
                    "group relative flex items-center gap-3 rounded-md px-3 py-2 text-sm transition-colors",
                    item.nested && "ml-6",
                    active
                        ? "font-medium text-brand-dark"
                        : "font-light text-sidebar-foreground hover:bg-sidebar-accent hover:text-sidebar-accent-foreground",
                )}
            >
                {active && (
                    <motion.span
                        layoutId="sidebar-nav-pill"
                        className="absolute inset-0 z-0 rounded-md bg-brand-light"
                        transition={reduce ? instant : springSnappy}
                    />
                )}
                <Icon
                    className={`relative z-10 size-4 shrink-0 ${active ? "text-brand-dark" : "text-muted-foreground group-hover:text-current"
                        }`}
                />
                <span className="relative z-10">{item.label}</span>
                {item.availabilityLabel ? (
                    <span
                        className="relative z-10 inline-flex text-muted-foreground"
                        title={item.availabilityLabel}
                    >
                        <QuestionMarkCircleIcon aria-hidden className="size-4" />
                        <span className="sr-only">{item.availabilityLabel}</span>
                    </span>
                ) : null}
            </Link>
        </li>
    );
}

function PinnedViewsUnavailable({
    rail,
    label,
    retryLabel,
    onRetry,
}: {
    rail: boolean;
    label: string;
    retryLabel: string;
    onRetry: () => void;
}) {
    if (rail) {
        return (
            <Tooltip>
                <TooltipTrigger asChild>
                    <button
                        type="button"
                        onClick={onRetry}
                        aria-label={`${label} ${retryLabel}`}
                        className="mx-auto flex size-9 items-center justify-center rounded-lg text-warning outline-none transition-colors hover:bg-sidebar-accent focus-visible:ring-2 focus-visible:ring-brand"
                    >
                        <ExclamationTriangleIcon className="size-4" aria-hidden />
                    </button>
                </TooltipTrigger>
                <TooltipContent side="right">{label}</TooltipContent>
            </Tooltip>
        );
    }

    return (
        <div
            role="status"
            className="rounded-md border border-sidebar-border bg-sidebar-accent/40 px-3 py-2 text-xs text-muted-foreground"
        >
            <div className="flex items-start gap-2">
                <ExclamationTriangleIcon className="mt-0.5 size-4 shrink-0 text-warning" aria-hidden />
                <span>{label}</span>
            </div>
            <button
                type="button"
                onClick={onRetry}
                className="mt-2 inline-flex items-center gap-1.5 font-medium text-sidebar-foreground transition-colors hover:text-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-brand"
            >
                <ArrowPathIcon className="size-3.5" aria-hidden />
                {retryLabel}
            </button>
        </div>
    );
}

function ThemeSubmenu() {
    const t = useTranslations("CommonSidebar");
    const { theme, setTheme } = useTheme();
    const options = [
        { value: "light", label: t("themeLight"), icon: SunIcon },
        { value: "dark", label: t("themeDark"), icon: MoonIcon },
        { value: "system", label: t("themeSystem"), icon: ComputerDesktopIcon },
    ];
    const TriggerIcon =
        theme === "dark" ? MoonIcon : theme === "system" ? ComputerDesktopIcon : SunIcon;
    return (
        <DropdownMenu.Item asChild>
            <DropdownMenuSub>
                <DropdownMenuSubTrigger>
                    <TriggerIcon className="size-4" />
                    {t("theme")}
                    <DropdownMenuPortal>
                        <DropdownMenuSubContent>
                            {options.map((opt) => {
                                const Icon = opt.icon;
                                return (
                                    <DropdownMenuItem key={opt.value} onClick={() => setTheme(opt.value)}>
                                        <Icon className="size-4" />
                                        {opt.label}
                                        {theme === opt.value && <CheckIcon className="ml-auto size-4" />}
                                    </DropdownMenuItem>
                                );
                            })}
                        </DropdownMenuSubContent>
                    </DropdownMenuPortal>
                </DropdownMenuSubTrigger>
            </DropdownMenuSub>
        </DropdownMenu.Item>
    );
}

function UserMenu({ user, onLogout, rail }: { user: User; onLogout: () => void; rail: boolean }) {
    const t = useTranslations("CommonSidebar");
    const tManifest = useTranslations();
    const tn = useTranslations("Notifications");
    const accountSettings = settingsDestination("account.home");
    const locale = useLocale();
    const router = useRouter();
    const { unread } = useNotifications();
    const [pendingLocale, setPendingLocale] = useState<Locale | null>(null);
    const localeRequestRef = useRef(0);

    async function selectLanguage(nextLocale: Locale) {
        const requestId = localeRequestRef.current + 1;
        localeRequestRef.current = requestId;
        setPendingLocale(nextLocale);
        try {
            await persistAuthenticatedLocale(nextLocale);
            if (localeRequestRef.current === requestId) router.refresh();
        } catch {
            if (localeRequestRef.current === requestId) toastError(t("languageSaveFailed"));
        } finally {
            if (localeRequestRef.current === requestId) setPendingLocale(null);
        }
    }

    return (
        <DropdownMenu.Root>
            <DropdownMenu.Trigger asChild>
                {rail ? (
                    <button
                        type="button"
                        aria-label={user.displayName}
                        className="flex justify-center rounded-md p-1 transition hover:bg-sidebar-accent focus:outline-none focus-visible:ring-2 focus-visible:ring-brand data-[state=open]:bg-sidebar-accent"
                    >
                        <UserAvatar user={user} />
                    </button>
                ) : (
                    <button
                        type="button"
                        className="flex w-full items-center gap-3 rounded-md p-2 text-left transition hover:bg-sidebar-accent focus:outline-none focus-visible:ring-2 focus-visible:ring-brand data-[state=open]:bg-sidebar-accent"
                    >
                        <UserAvatar user={user} />
                        <div className="min-w-0 flex-1">
                            <div className="truncate text-sm font-medium text-sidebar-foreground">
                                {user.displayName}
                            </div>
                            <div className="truncate text-xs text-muted-foreground">
                                @{user.username}
                            </div>
                        </div>
                        <EllipsisVerticalIcon className="size-4 shrink-0 text-muted-foreground" />
                    </button>
                )}
            </DropdownMenu.Trigger>
            <DropdownMenu.Portal>
                <DropdownMenu.Content
                    side="top"
                    align="start"
                    sideOffset={8}
                    className="z-50 min-w-[14rem] origin-[var(--radix-dropdown-menu-content-transform-origin)] rounded-lg border border-sidebar-border bg-popover p-1 text-popover-foreground shadow-lg data-[state=open]:animate-in data-[state=closed]:animate-out data-[state=closed]:fade-out-0 data-[state=open]:fade-in-0 data-[state=closed]:zoom-out-95 data-[state=open]:zoom-in-95 data-[side=top]:slide-in-from-bottom-2 data-[side=bottom]:slide-in-from-top-2 motion-reduce:animate-none!"
                >
                    <DropdownMenu.Label className="flex items-center gap-3 px-2 py-2">
                        <UserAvatar user={user} type="medium" />
                        <div className="min-w-0">
                            <div className="truncate text-sm font-medium">
                                {user.displayName}
                            </div>
                            <div className="truncate text-xs text-muted-foreground">
                                {user.email}
                            </div>
                        </div>
                    </DropdownMenu.Label>
                    <DropdownMenu.Separator className="my-1 h-px bg-sidebar-border" />
                    <DropdownMenu.Item asChild>
                        <Link
                            href="/me"
                            className="flex cursor-pointer items-center gap-2 rounded-sm px-2 py-1.5 text-sm outline-none data-[highlighted]:bg-brand-light data-[highlighted]:text-brand-dark"
                        >
                            <UserCircleIcon className="size-4" />
                            {t("profile")}
                        </Link>
                    </DropdownMenu.Item>
                    <DropdownMenu.Item asChild>
                        <Link
                            href={accountSettings.href}
                            className="flex cursor-pointer items-center gap-2 rounded-sm px-2 py-1.5 text-sm outline-none data-[highlighted]:bg-brand-light data-[highlighted]:text-brand-dark"
                        >
                            <Cog6ToothIcon className="size-4" />
                            {tManifest(accountSettings.titleKey)}
                        </Link>
                    </DropdownMenu.Item>
                    <DropdownMenu.Item asChild>
                        <Link
                            href="/notifications"
                            className="flex cursor-pointer items-center gap-2 rounded-sm px-2 py-1.5 text-sm outline-none data-[highlighted]:bg-brand-light data-[highlighted]:text-brand-dark"
                        >
                            <BellIcon className="size-4" />
                            {t("navNotifications")}
                            {unread > 0 ? (
                                <>
                                    <span className="sr-only">{tn("unreadCount", { count: unread })}</span>
                                    <span
                                        aria-hidden="true"
                                        className="ml-auto min-w-5 rounded-full bg-destructive px-1 text-center text-[10px] font-semibold leading-5 text-white"
                                    >
                                        {unread > 99 ? "99+" : unread}
                                    </span>
                                </>
                            ) : null}
                        </Link>
                    </DropdownMenu.Item>
                    <DropdownMenu.Item asChild>
                        <DropdownMenuSub>
                            <DropdownMenuSubTrigger>
                                <GlobeAltIcon className="size-4" />
                                {t("language")}
                                <DropdownMenuPortal>
                                    <DropdownMenuSubContent>
                                        <DropdownMenuRadioGroup
                                            value={pendingLocale ?? locale}
                                            onValueChange={(value) => {
                                                if (value === "en" || value === "ja") {
                                                    void selectLanguage(value);
                                                }
                                            }}
                                            aria-busy={pendingLocale !== null}
                                        >
                                            <DropdownMenuRadioItem value="en">
                                                {t("languageEnglish")}
                                            </DropdownMenuRadioItem>
                                            <DropdownMenuRadioItem value="ja">
                                                {t("languageJapanese")}
                                            </DropdownMenuRadioItem>
                                        </DropdownMenuRadioGroup>
                                    </DropdownMenuSubContent>
                                </DropdownMenuPortal>
                            </DropdownMenuSubTrigger>
                        </DropdownMenuSub>
                    </DropdownMenu.Item>
                    <ThemeSubmenu />
                    {/* <DropdownMenu.Separator className="my-1 h-px bg-sidebar-border" /> */}
                    <DropdownMenu.Item
                        onSelect={(event) => {
                            event.preventDefault();
                            onLogout();
                        }}
                        className="flex cursor-pointer items-center gap-2 rounded-sm px-2 py-1.5 text-sm text-destructive outline-none data-[highlighted]:bg-destructive/10 data-[highlighted]:text-destructive"
                    >
                        <ArrowRightStartOnRectangleIcon className="size-4" />
                        {t("logOut")}
                    </DropdownMenu.Item>
                </DropdownMenu.Content>
            </DropdownMenu.Portal>
        </DropdownMenu.Root>
    );
}

export default function Sidebar({
    user,
    navAccess,
    className,
}: {
    user: User;
    navAccess: NavAccess;
    className?: string;
}) {
    const pathname = usePathname() ?? "";
    const router = useRouter();
    const t = useTranslations("CommonSidebar");
    const sections = useSections(navAccess);
    const { activeWorkspaceId } = useWorkspace();
    const { isCollapsed, setCollapsed } = useSidebarSections(user.id, activeWorkspaceId);
    const { pins, status: pinnedViewsStatus, reload: reloadPinnedViews } = usePinnedViews();
    const { recents } = useRecentRecords();
    const searchParams = useSearchParams();
    const svParam = searchParams.get("sv");
    const navigationKey = `${user.id}:${activeWorkspaceId ?? "none"}:${pathname}:${svParam ?? ""}`;
    const pinnedSection = useMemo<NavSection | null>(() => {
        if (pins.length === 0) return null;
        return {
            id: "pinned-views",
            label: t("sectionPinnedViews"),
            items: pins.map((pin) => ({
                label: pin.name,
                href: savedViewHref(pin),
                icon: savedViewRecordIcon(pin.recordType),
                active: pathname === `/records/${savedViewRecordPath(pin.recordType)}` && svParam === savedViewToken(pin),
            })),
        };
    }, [pins, t, pathname, svParam]);
    const recentSection = useMemo<NavSection | null>(() => {
        if (recents.length === 0) return null;
        return {
            id: "recent-records",
            label: t("sectionRecent"),
            items: recents.slice(0, SIDEBAR_RECENTS_LIMIT).map((entry) => {
                const href = recentRecordHref(entry.t, entry.id);
                return {
                    label: entry.label,
                    href,
                    icon: savedViewRecordIcon(entry.t),
                    active: pathname === href,
                };
            }),
        };
    }, [recents, t, pathname]);
    const { mode } = useSidebarMode();
    const isMobile = useIsMobile();
    const rail = !isMobile && mode === "rail";

    const [transitionsEnabled, setTransitionsEnabled] = useState(false);
    useEffect(() => {
        let inner = 0;
        const outer = requestAnimationFrame(() => {
            inner = requestAnimationFrame(() => setTransitionsEnabled(true));
        });
        return () => {
            cancelAnimationFrame(outer);
            cancelAnimationFrame(inner);
        };
    }, []);

    async function handleLogout() {
        try {
            router.push("/auth/logout");
        } catch {
            console.error("Failed to logout");
        }
    }

    return (
        <div className="p-2 h-dvh">
            <aside
                className={cn(
                    "flex min-h-0 flex-col",
                    transitionsEnabled && "transition-[width,padding] duration-300 ease-out motion-reduce:transition-none",
                    rail ? "w-16 p-3" : "w-64 p-6",
                    className,
                )}
                aria-label={t("ariaPrimarySidebar")}
            >
                <header className={cn("mb-6 flex shrink-0 gap-2", rail ? "flex-col items-center" : "items-center justify-between")}>
                    <WorkspaceSwitcher compact={rail} />
                    <NotificationBell />
                </header>

                <QuickCreateLauncher compact={rail} />

                <nav
                    className={cn(
                        "flex min-h-0 flex-1 flex-col gap-5 overflow-y-auto",
                        rail
                            ? "overflow-x-hidden [scrollbar-width:none] [&::-webkit-scrollbar]:hidden"
                            : "pr-1",
                    )}
                >
                    {pinnedSection && (
                        <NavGroup
                            key={pinnedSection.id}
                            section={pinnedSection}
                            pathname={pathname}
                            rail={rail}
                            collapsed={isCollapsed(pinnedSection.id)}
                            navigationKey={navigationKey}
                            onCollapsedChange={setCollapsed}
                        />
                    )}
                    {pinnedViewsStatus === "unavailable" ? (
                        <PinnedViewsUnavailable
                            rail={rail}
                            label={t("pinnedViewsUnavailable")}
                            retryLabel={t("retryPinnedViews")}
                            onRetry={() => { void reloadPinnedViews(); }}
                        />
                    ) : null}
                    {recentSection && (
                        <NavGroup
                            key={recentSection.id}
                            section={recentSection}
                            pathname={pathname}
                            rail={rail}
                            collapsed={isCollapsed(recentSection.id)}
                            navigationKey={navigationKey}
                            onCollapsedChange={setCollapsed}
                        />
                    )}
                    {recentSection && (
                        <hr className="-my-1 border-t border-sidebar-border" />
                    )}
                    {sections.map((section) => (
                        <NavGroup
                            key={section.id}
                            section={section}
                            pathname={pathname}
                            rail={rail}
                            collapsed={isCollapsed(section.id)}
                            navigationKey={navigationKey}
                            onCollapsedChange={setCollapsed}
                        />
                    ))}
                </nav>

                <div className={cn("mt-4 shrink-0 border-t border-sidebar-border pt-4", rail && "flex justify-center")}>
                    <UserMenu user={user} onLogout={handleLogout} rail={rail} />
                </div>
            </aside>
        </div>
    );
}
