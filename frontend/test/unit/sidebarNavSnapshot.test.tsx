import { readFileSync, readdirSync } from "node:fs";
import path from "node:path";
import {
    createElement,
    type AnchorHTMLAttributes,
    type ComponentProps,
    type PropsWithChildren,
} from "react";
import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it, vi } from "vitest";

import Sidebar from "@/app/components/Sidebar";
import type { NavAccess } from "@/app/lib/navAccess";
import type { User, Workspace } from "@/app/lib/types";

const LOCALES = ["en", "ja"] as const;

type Locale = (typeof LOCALES)[number];

/** One sidebar row: the address it leads to and the name each locale gives it. */
type ExpectedItem = {
    href: string;
    en: string;
    ja: string;
};

/** One sidebar group: the name each locale gives it, and the rows it holds in order. */
type ExpectedGroup = {
    en: string;
    ja: string;
    items: ExpectedItem[];
};

/**
 * The D13 sidebar, written out as the founder signed it off (#1323 WS4).
 *
 * This is the assertion, not the derivation: the actual side of every expectation below is read
 * back out of what `Sidebar` renders for a member who can reach everything, so a section that
 * moves, a row that changes address, or a label that is renamed in one locale and not the other
 * fails here. Both locales come from the shipped catalogs, so a Japanese rename cannot ride along
 * behind an English one.
 */
const D13_SIDEBAR: ExpectedGroup[] = [
    {
        en: "Dashboard",
        ja: "ダッシュボード",
        items: [{ href: "/dashboard", en: "Dashboard", ja: "ダッシュボード" }],
    },
    {
        en: "My Work",
        ja: "マイワーク",
        items: [{ href: "/me", en: "My Work", ja: "マイワーク" }],
    },
    {
        en: "Intelligence",
        ja: "人脈",
        items: [
            { href: "/intelligence/radar", en: "Radar", ja: "レーダー" },
            { href: "/intelligence/introductions", en: "Introductions", ja: "紹介" },
            { href: "/intelligence/map", en: "Map", ja: "マップ" },
        ],
    },
    {
        en: "Records",
        ja: "レコード",
        items: [
            { href: "/records/companies", en: "Companies", ja: "会社" },
            { href: "/records/contacts", en: "Contacts", ja: "連絡先" },
            { href: "/records/deals", en: "Deals", ja: "案件" },
            { href: "/records/pipelines", en: "Pipelines", ja: "パイプライン" },
            { href: "/records/products", en: "Products", ja: "商品" },
        ],
    },
    {
        en: "Activity",
        ja: "活動記録",
        items: [
            { href: "/activity/all", en: "Activities", ja: "アクティビティ" },
            { href: "/activity/tasks", en: "Tasks", ja: "タスク" },
            { href: "/activity/notes", en: "Notes", ja: "メモ" },
            { href: "/activity/calendar", en: "Calendar", ja: "カレンダー" },
        ],
    },
    {
        en: "Insights",
        ja: "業績",
        items: [
            { href: "/insights/analytics", en: "Analytics", ja: "分析" },
            { href: "/insights/reports", en: "Reports", ja: "レポート" },
            { href: "/insights/reports/goals", en: "Goals", ja: "目標" },
        ],
    },
    {
        en: "Marketing",
        ja: "マーケティング",
        items: [{ href: "/marketing/campaigns", en: "Campaigns", ja: "キャンペーン" }],
    },
    {
        en: "Library",
        ja: "ライブラリ",
        items: [
            { href: "/library/documents", en: "Documents", ja: "ドキュメント" },
            {
                href: "/settings/workspace/crm#approval-policies",
                en: "Approval policies",
                ja: "承認ポリシー",
            },
            { href: "/library/tags", en: "Tags", ja: "タグ" },
            { href: "/library/files", en: "Files", ja: "ファイル" },
        ],
    },
    {
        en: "Workflows",
        ja: "ワークフロー",
        items: [{ href: "/workflows", en: "Workflows", ja: "ワークフロー" }],
    },
    {
        en: "Workspace",
        ja: "ワークスペース",
        items: [
            {
                href: "/settings/workspace/people#directory",
                en: "Member directory",
                ja: "メンバー一覧",
            },
            {
                href: "/settings/personal/connected-accounts#reviews",
                en: "Capture reviews",
                ja: "取り込みの確認",
            },
            { href: "/settings", en: "Settings", ja: "設定" },
            {
                href: "/settings/organization/identity#administrators",
                en: "Administrators",
                ja: "管理者",
            },
            {
                href: "/settings/workspace/audit-diagnostics#audit",
                en: "Audit log",
                ja: "監査ログ",
            },
        ],
    },
];

/**
 * The rows behind the member's own name, which D13 made Documentation's home.
 *
 * Documentation left the sidebar in the same change that gave My Work a sidebar row of its own, so
 * the menu is part of the navigation the snapshot has to hold: dropping the row would otherwise
 * leave the docs reachable from nowhere while every sidebar assertion still passed.
 */
const D13_USER_MENU: ExpectedItem[] = [
    { href: "/me", en: "My Work", ja: "マイワーク" },
    { href: "/settings/personal/profile", en: "Account settings", ja: "アカウント設定" },
    { href: "/docs", en: "Documentation", ja: "ヘルプドキュメント" },
    { href: "/notifications", en: "Notifications", ja: "通知" },
];

const localeState = vi.hoisted(() => ({ locale: "en" }));

const messageCatalogs = new Map<string, Record<string, unknown>>();

/**
 * The shipped catalog for a locale, merged the way the app merges it.
 *
 * `i18n/request.ts` flattens every namespace file into one object before next-intl sees it, so a
 * label the sidebar reads through `useTranslations()` resolves against the same shape here. Reading
 * the directory rather than restating the namespace list keeps a newly added catalog in scope.
 */
function catalog(locale: string): Record<string, unknown> {
    const cached = messageCatalogs.get(locale);
    if (cached !== undefined) return cached;
    const directory = path.join(process.cwd(), "messages", locale);
    const merged = readdirSync(directory)
        .filter((file) => file.endsWith(".json"))
        .reduce<Record<string, unknown>>((accumulator, file) => ({
            ...accumulator,
            ...(JSON.parse(readFileSync(path.join(directory, file), "utf8")) as Record<string, unknown>),
        }), {});
    messageCatalogs.set(locale, merged);
    return merged;
}

/** Resolves an absolute message key against a shipped catalog, refusing to invent a label. */
function translate(locale: string, key: string): string {
    const resolved = key.split(".").reduce<unknown>(
        (node, segment) =>
            typeof node === "object" && node !== null && !Array.isArray(node)
                ? (node as Record<string, unknown>)[segment]
                : undefined,
        catalog(locale),
    );
    if (typeof resolved !== "string") {
        throw new Error(`messages/${locale} has no string at ${key}`);
    }
    return resolved;
}

vi.mock("next-intl", () => ({
    useLocale: () => localeState.locale,
    useTranslations: (namespace?: string) => (key: string) =>
        translate(localeState.locale, namespace === undefined ? key : `${namespace}.${key}`),
}));

vi.mock("next/link", async () => {
    const React = await import("react");
    type LinkProps = PropsWithChildren<AnchorHTMLAttributes<HTMLAnchorElement> & { href: string }>;
    return {
        default: ({ children, href, ...props }: LinkProps) =>
            React.createElement("a", { ...props, href }, children),
    };
});

vi.mock("next/navigation", () => ({
    usePathname: () => "/dashboard",
    useRouter: () => ({ push: vi.fn(), refresh: vi.fn(), replace: vi.fn() }),
    useSearchParams: () => new URLSearchParams(),
}));

vi.mock("motion/react", async () => {
    const React = await import("react");
    type MotionProps<T extends "div" | "span" | "ul"> = ComponentProps<T> & {
        animate?: unknown;
        exit?: unknown;
        initial?: unknown;
        layout?: unknown;
        layoutId?: string;
        transition?: unknown;
    };
    return {
        AnimatePresence: ({ children }: PropsWithChildren) =>
            React.createElement(React.Fragment, null, children),
        motion: {
            div: ({ children, ...props }: MotionProps<"div">) =>
                React.createElement("div", { className: props.className }, children),
            span: ({ children, ...props }: MotionProps<"span">) =>
                React.createElement("span", { className: props.className }, children),
            ul: ({ children, ...props }: MotionProps<"ul">) =>
                React.createElement("ul", { className: props.className }, children),
        },
        useReducedMotion: () => true,
    };
});

vi.mock("next-themes", () => ({
    useTheme: () => ({ setTheme: vi.fn(), theme: "system" }),
}));

vi.mock("radix-ui", async () => {
    const React = await import("react");
    const Wrapper = ({ children }: PropsWithChildren) =>
        React.createElement(React.Fragment, null, children);
    return {
        DropdownMenu: {
            Content: Wrapper,
            Item: Wrapper,
            Label: Wrapper,
            Portal: Wrapper,
            Root: Wrapper,
            Separator: Wrapper,
            Trigger: Wrapper,
        },
    };
});

vi.mock("@/components/ui/dropdown-menu", async () => {
    const React = await import("react");
    const Wrapper = ({ children }: PropsWithChildren) =>
        React.createElement(React.Fragment, null, children);
    return {
        DropdownMenuItem: Wrapper,
        DropdownMenuPortal: Wrapper,
        DropdownMenuRadioGroup: Wrapper,
        DropdownMenuRadioItem: Wrapper,
        DropdownMenuSub: Wrapper,
        DropdownMenuSubContent: Wrapper,
        DropdownMenuSubTrigger: Wrapper,
    };
});

vi.mock("@/components/ui/tooltip", async () => {
    const React = await import("react");
    const Wrapper = ({ children }: PropsWithChildren) =>
        React.createElement(React.Fragment, null, children);
    return { Tooltip: Wrapper, TooltipContent: Wrapper, TooltipTrigger: Wrapper };
});

vi.mock("@/app/components/WorkspaceSwitcher", () => ({ default: () => null }));
vi.mock("@/app/components/notifications/NotificationBell", () => ({ default: () => null }));
vi.mock("@/app/components/actions/QuickCreateLauncher", () => ({ default: () => null }));
vi.mock("@/app/components/records/users/UserAvatar", () => ({ default: () => null }));

vi.mock("@/app/hooks/useWorkspace", () => ({
    useWorkspace: () => ({ activeWorkspace: WORKSPACE, activeWorkspaceId: WORKSPACE.id }),
}));

vi.mock("@/app/hooks/useSidebarSections", () => ({
    useSidebarSections: () => ({ isCollapsed: () => false, setCollapsed: vi.fn() }),
}));

vi.mock("@/app/hooks/usePinnedViews", () => ({
    usePinnedViews: () => ({ pins: [], reload: vi.fn(), status: "resolved" }),
}));

vi.mock("@/app/hooks/useRecentRecords", () => ({
    useRecentRecords: () => ({ recents: [] }),
}));

vi.mock("@/app/hooks/useSidebarMode", () => ({
    useSidebarMode: () => ({ mode: "expanded" }),
}));

vi.mock("@/app/hooks/useIsMobile", () => ({ useIsMobile: () => false }));

vi.mock("@/app/hooks/useNotifications", () => ({
    useNotifications: () => ({ unread: 0 }),
}));

const USER = {
    id: 9,
    username: "member",
    displayName: "Member",
    email: "member@connex.test",
    createdAt: "2026-01-01T00:00:00Z",
    updatedAt: "2026-01-01T00:00:00Z",
    timezone: "UTC",
    locale: "en",
} satisfies User;

const WORKSPACE = {
    id: 7,
    name: "Workspace",
    slug: "workspace",
    timezone: "UTC",
    identityVersion: 1,
    role: "owner",
    orgId: 3,
    orgName: "Organization",
    orgIdentityVersion: 1,
    orgRole: "owner",
} satisfies Workspace;

/**
 * A member who can reach every gated destination.
 *
 * The snapshot is of the whole D13 list, so every capability and permission gate is open: a run
 * with anything switched off would silently assert a shorter sidebar than the one D13 describes.
 */
const FULL_NAV_ACCESS = {
    goals: true,
    auditLog: true,
    captureReviews: "enabled",
    campaigns: true,
    workflows: true,
    diagnostics: true,
} satisfies NavAccess;

/** A group as the sidebar rendered it. */
type RenderedGroup = {
    label: string;
    items: { label: string; href: string }[];
};

function attribute(attributes: string, name: string): string | null {
    const match = new RegExp(`${name}="([^"]*)"`).exec(attributes);
    return match === null ? null : match[1];
}

function decode(value: string): string {
    return value
        .replaceAll("&lt;", "<")
        .replaceAll("&gt;", ">")
        .replaceAll("&quot;", "\"")
        .replaceAll("&#x27;", "'")
        .replaceAll("&amp;", "&");
}

/**
 * The text of a markup fragment with every tag removed. Stripping runs to a fixed point so a
 * malformed nesting cannot leave a partial tag behind, and entities decode only after the last
 * tag is gone — the order CodeQL's double-unescape and incomplete-sanitization rules require.
 */
function textContent(fragment: string): string {
    let text = fragment;
    let previous = "";
    while (text !== previous) {
        previous = text;
        text = text.replaceAll(/<[^>]*>/g, "");
    }
    return decode(text).trim();
}

function sidebarMarkup(locale: Locale): string {
    localeState.locale = locale;
    return renderToStaticMarkup(createElement(Sidebar, { user: USER, navAccess: FULL_NAV_ACCESS }));
}

function navMarkup(markup: string): string {
    const start = markup.indexOf("<nav ");
    const end = markup.indexOf("</nav>", start);
    if (start < 0 || end < 0) throw new Error("Sidebar rendered no navigation landmark");
    return markup.slice(start, end);
}

/**
 * Reads the groups back out of the rendered sidebar.
 *
 * A group is one list: a headed group carries its name on the collapse button that precedes its
 * list, and a headless single-row group — Dashboard, My Work, Workflows — carries the same name as
 * the list's accessible name instead. Both shapes are read here, so a group that changes shape
 * still has to hold the rows D13 gives it.
 */
function renderedGroups(locale: Locale): RenderedGroup[] {
    const markup = navMarkup(sidebarMarkup(locale));
    const token = /<ul\b([^>]*)>|<\/ul>|<a\b([^>]*)>|<span>([^<]*)<\/span>|<span class="relative z-10">([^<]*)<\/span>/g;
    const groups: RenderedGroup[] = [];
    let group: RenderedGroup | null = null;
    let heading: string | null = null;
    let href: string | null = null;
    for (const match of markup.matchAll(token)) {
        const [text, listAttributes, anchorAttributes, headingText, itemLabel] = match;
        if (listAttributes !== undefined) {
            const label = attribute(listAttributes, "aria-label") ?? heading;
            if (label === null) throw new Error("a sidebar group rendered without a name");
            group = { label: decode(label), items: [] };
            heading = null;
            continue;
        }
        if (text === "</ul>") {
            if (group !== null) groups.push(group);
            group = null;
            continue;
        }
        if (anchorAttributes !== undefined) {
            href = attribute(anchorAttributes, "href");
            continue;
        }
        if (headingText !== undefined) {
            heading = headingText;
            continue;
        }
        if (itemLabel !== undefined && group !== null && href !== null) {
            group.items.push({ label: decode(itemLabel), href: decode(href) });
            href = null;
        }
    }
    const empty = groups.filter((candidate) => candidate.items.length === 0);
    if (empty.length > 0) {
        throw new Error(`read no rows out of ${empty.map((candidate) => candidate.label).join(", ")}`);
    }
    return groups;
}

/** Reads the member-menu rows back out of everything the sidebar renders below its navigation. */
function renderedUserMenu(locale: Locale): { label: string; href: string }[] {
    const markup = sidebarMarkup(locale);
    const menu = markup.slice(markup.indexOf("</nav>"));
    return [...menu.matchAll(/<a\b([^>]*)>([\s\S]*?)<\/a>/g)].flatMap((match) => {
        const href = attribute(match[1], "href");
        if (href === null) return [];
        const label = textContent(match[2]);
        return [{ label, href }];
    });
}

function expectedGroups(locale: Locale): RenderedGroup[] {
    return D13_SIDEBAR.map((group) => ({
        label: group[locale],
        items: group.items.map((item) => ({ label: item[locale], href: item.href })),
    }));
}

describe("the sidebar renders the D13 navigation", () => {
    it.each(LOCALES)("lists every section, row, and address in %s", (locale) => {
        expect(renderedGroups(locale)).toEqual(expectedGroups(locale));
    });

    it.each(LOCALES)("keeps the member menu's rows in %s", (locale) => {
        expect(renderedUserMenu(locale)).toEqual(
            D13_USER_MENU.map((item) => ({ label: item[locale], href: item.href })),
        );
    });

    it("offers no address the route move retired", () => {
        const addresses = renderedGroups("en")
            .flatMap((group) => group.items.map((item) => item.href))
            .concat(renderedUserMenu("en").map((item) => item.href));

        expect(addresses.filter((href) => href === "/radar" || href.startsWith("/overview"))).toEqual([]);
    });
});
