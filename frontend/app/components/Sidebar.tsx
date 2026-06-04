"use client";

import {
    ArrowRightStartOnRectangleIcon,
    BriefcaseIcon,
    BuildingOffice2Icon,
    ChatBubbleLeftRightIcon,
    CheckCircleIcon,
    ChevronDownIcon,
    ChevronUpDownIcon,
    DocumentTextIcon,
    FunnelIcon,
    HomeIcon,
    TagIcon,
    UserCircleIcon,
    UserGroupIcon,
    UsersIcon,
    EllipsisVerticalIcon,
    CalendarIcon,
    MapIcon,
    ChartBarIcon,
    ChartPieIcon,
    PresentationChartLineIcon,
    GlobeAltIcon,
} from "@heroicons/react/24/outline";
import Image from "next/image";
import Link from "next/link";
import { usePathname, useRouter } from "next/navigation";
import { useState } from "react";
import { DropdownMenu } from "radix-ui";
import { type User } from "@/app/lib/types";
// import { BubblesIcon, PanelLeftOpenIcon } from "lucide-react";
import { useTranslations } from "next-intl";
import { DropdownMenuItem, DropdownMenuPortal, DropdownMenuSub, DropdownMenuSubContent, DropdownMenuSubTrigger } from "@/components/ui/dropdown-menu";
// import {  } from "@heroicons/react/24/solid";
import UserAvatar from '@/app/components/records/users/UserAvatar';

type NavItem = {
    label: string;
    href: string;
    icon: React.ComponentType<{ className?: string }>;
    disabled?: boolean;
};

type NavSection = {
    label: string;
    items: NavItem[];
};

function useSections(): NavSection[] {
    const t = useTranslations("CommonSidebar");
    return [
        {
            label: t("sectionOverview"),
            items: [
                { label: t("navDashboard"), href: "/dashboard", icon: HomeIcon },
                { label: t("navCalendar"), href: "/overview/calendar", icon: CalendarIcon, disabled: false },
                { label: t("navMap"), href: "/overview/map", icon: MapIcon, disabled: false },
                { label: t("navAnalytics"), href: "/overview/analytics", icon: ChartBarIcon, disabled: false },
                // { label: t("navInsights"), href: "/overview/insights", icon: ChartPieIcon, disabled: true },
                { label: t("navReports"), href: "/overview/reports", icon: PresentationChartLineIcon, disabled: true }
            ]
        },
        {
            label: t("sectionRecords"),
            items: [
                { label: t("navCompanies"), href: "/records/companies", icon: BuildingOffice2Icon },
                { label: t("navContacts"), href: "/records/contacts", icon: UsersIcon },
                { label: t("navDeals"), href: "/records/deals", icon: BriefcaseIcon },
                { label: t("navPipelines"), href: "/records/pipelines", icon: FunnelIcon },
            ],
        },
        {
            label: t("sectionActivity"),
            items: [
                { label: t("navActivities"), href: "/activity/all", icon: ChatBubbleLeftRightIcon },
                { label: t("navTasks"), href: "/activity/tasks", icon: CheckCircleIcon },
                { label: t("navNotes"), href: "/activity/notes", icon: DocumentTextIcon },
            ],
        },
        {
            label: t("sectionLibrary"),
            items: [
                { label: t("navTags"), href: "/library/tags", icon: TagIcon },
            ],
        },
        {
            label: t("sectionTeam"),
            items: [
                { label: t("navUsers"), href: "/users", icon: UserGroupIcon },
            ],
        },
    ];
}

function isActive(pathname: string, href: string): boolean {
    if (href === "/dashboard") return pathname === "/dashboard";
    // handle discrepancy between /activity and /activity/tasks both showing as active in the Sidebar; unintended behavior
    // if (href === "/activity") return pathname === "/activity" || pathname.startsWith("/activity/");
    return pathname === href || pathname.startsWith(`${href}/`);
}

// function toggleSidebar() {
//     const sidebar = document.querySelector(".sidebar");
//     if (sidebar) {
//         sidebar.classList.toggle("hidden");
//     }
// }

function NavGroup({
    section,
    pathname,
}: {
    section: NavSection;
    pathname: string;
}) {
    const [open, setOpen] = useState(true);
    const sectionId = `nav-group-${section.label.toLowerCase()}`;
    return (
        <div>
            <button
                type="button"
                onClick={() => setOpen((o) => !o)}
                aria-expanded={open}
                aria-controls={sectionId}
                className="flex w-full items-center justify-between rounded-md px-3 py-1 text-xs font-medium uppercase tracking-[0.12em] text-neutral-500 transition hover:text-neutral-700"
            >
                <span>{section.label}</span>
                <ChevronDownIcon
                    className={`size-3 transition-transform ${open ? "" : "-rotate-90"}`}
                />
            </button>
            {open && (
                <ul id={sectionId} className="mt-1 flex flex-col gap-0.5">
                    {section.items.map((item) => (
                        <NavLink
                            key={item.href}
                            item={item}
                            active={isActive(pathname, item.href)} // TODO: add active state for nested routes (/activity and /activity/tasks both show as active in the Sidebar; unintended behavior)
                        />
                    ))}
                </ul>
            )}
        </div>
    );
}

function NavLink({ item, active }: { item: NavItem; active: boolean }) {
    const Icon = item.icon;
    return (
        <li className={item.disabled ? "opacity-50 disabled cursor-not-allowed" : ""}>
            <Link
                href={item.disabled ? "#" : item.href}
                aria-current={active ? "page" : undefined}
                className={`group flex items-center gap-3 rounded-md px-3 py-2 text-sm transition ${active
                    ? "bg-brand-light text-brand-dark font-medium"
                    : "font-light text-sidebar-foreground hover:bg-sidebar-accent hover:text-sidebar-accent-foreground"
                    }`}
            >
                <Icon
                    className={`size-4 shrink-0 ${active ? "text-brand-dark" : "text-neutral-500 group-hover:text-current"
                        }`}
                />
                <span>{item.label}</span>
            </Link>
        </li>
    );
}

function UserMenu({ user, onLogout }: { user: User; onLogout: () => void }) {
    const t = useTranslations("CommonSidebar");
    return (
        <DropdownMenu.Root>
            <DropdownMenu.Trigger asChild>
                <button
                    type="button"
                    className="flex w-full items-center gap-3 rounded-md p-2 text-left transition hover:bg-sidebar-accent focus:outline-none focus-visible:ring-2 focus-visible:ring-brand data-[state=open]:bg-sidebar-accent"
                >
                    <UserAvatar user={user} />
                    <div className="min-w-0 flex-1">
                        <div className="truncate text-sm font-medium text-sidebar-foreground">
                            {user.displayName}
                        </div>
                        <div className="truncate text-xs text-neutral-500">
                            @{user.username}
                        </div>
                    </div>
                    <EllipsisVerticalIcon className="size-4 shrink-0 text-neutral-500" />
                </button>
            </DropdownMenu.Trigger>
            <DropdownMenu.Portal>
                <DropdownMenu.Content
                    side="top"
                    align="start"
                    sideOffset={8}
                    className="z-50 min-w-[14rem] origin-[var(--radix-dropdown-menu-content-transform-origin)] rounded-lg border border-sidebar-border bg-popover p-1 text-popover-foreground shadow-lg data-[state=open]:animate-in data-[state=closed]:animate-out data-[state=closed]:fade-out-0 data-[state=open]:fade-in-0 data-[state=closed]:zoom-out-95 data-[state=open]:zoom-in-95 data-[side=top]:slide-in-from-bottom-2 data-[side=bottom]:slide-in-from-top-2"
                >
                    <DropdownMenu.Label className="flex items-center gap-3 px-2 py-2">
                        <UserAvatar user={user} type="medium" />
                        <div className="min-w-0">
                            <div className="truncate text-sm font-medium">
                                {user.displayName}
                            </div>
                            <div className="truncate text-xs text-neutral-500">
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
                        <DropdownMenuSub>
                            <DropdownMenuSubTrigger onClick={() => {
                            }}>
                                <GlobeAltIcon className="size-4" />
                                Language
                                <DropdownMenuPortal>
                                    <DropdownMenuSubContent>
                                        {/* // set cookie to store the language for next-intl */}
                                        <DropdownMenuItem onClick={() => {
                                            document.cookie = "NEXT_LOCALE=en; path=/";
                                            window.location.reload();
                                        }}>English</DropdownMenuItem>
                                        <DropdownMenuItem onClick={() => {
                                            document.cookie = "NEXT_LOCALE=ja; path=/";
                                            window.location.reload();
                                        }}>日本語</DropdownMenuItem>
                                    </DropdownMenuSubContent>
                                </DropdownMenuPortal>
                            </DropdownMenuSubTrigger>
                        </DropdownMenuSub>
                    </DropdownMenu.Item>
                    {/* <DropdownMenu.Separator className="my-1 h-px bg-sidebar-border" /> */}
                    <DropdownMenu.Item
                        onSelect={(event) => {
                            event.preventDefault();
                            onLogout();
                        }}
                        className="flex cursor-pointer items-center gap-2 rounded-sm px-2 py-1.5 text-sm text-red-600 outline-none data-[highlighted]:bg-red-50 data-[highlighted]:text-red-700"
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
    className,
}: {
    user: User;
    className?: string;
}) {
    const pathname = usePathname() ?? "";
    const router = useRouter();
    const t = useTranslations("CommonSidebar");
    const sections = useSections();

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
                className={`flex flex-col min-h-0 ${className ?? ""}`}
                aria-label={t("ariaPrimarySidebar")}
            >
                {/* TODO: make this section a menu so that the user can select the organization they want to use */}
                <header className="mb-6 shrink-0">
                    <h1 className="text-2xl font-bold tracking-tight">{process.env.ORG_NAME ?? t("brand")}</h1>
                </header>

                <nav className="flex min-h-0 flex-1 flex-col gap-5 overflow-y-auto pr-1">
                    {sections.map((section) => (
                        <NavGroup
                            key={section.label}
                            section={section}
                            pathname={pathname}
                        />
                    ))}
                </nav>

                <div className="mt-4 shrink-0 border-t border-sidebar-border pt-4">
                    <UserMenu user={user} onLogout={handleLogout} />
                </div>
            </aside>
        </div>
    );
}
