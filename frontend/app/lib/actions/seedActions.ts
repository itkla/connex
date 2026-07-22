import {
    ArrowTopRightOnSquareIcon,
    ArrowUpRightIcon,
    ArrowUpTrayIcon,
    BoltIcon,
    BriefcaseIcon,
    BuildingLibraryIcon,
    BuildingOffice2Icon,
    CalendarIcon,
    ChartBarIcon,
    ChatBubbleLeftRightIcon,
    CheckCircleIcon,
    Cog6ToothIcon,
    DocumentTextIcon,
    FunnelIcon,
    HomeIcon,
    LinkIcon,
    UserPlusIcon,
    UsersIcon,
} from "@heroicons/react/24/outline";

import { toastSuccess } from "@/app/lib/toast";
import { deriveCreateDefaults } from "./createDefaults";
import type { ActiveRecordRef, AppAction, RecordType } from "./types";

/** Detail-page base path per record type; `null` for types without a detail route. */
export const RECORD_PATHS: Record<RecordType, string | null> = {
    company: "/records/companies",
    person: "/records/contacts",
    deal: "/records/deals",
    task: "/activity/tasks",
    activity: "/activity/all",
    note: "/activity/notes",
};

function recordHref(record: ActiveRecordRef): string | null {
    const base = RECORD_PATHS[record.type];
    return base ? `${base}/${record.id}` : null;
}

function navigateAction(
    id: string,
    labelKey: string,
    href: string,
    icon: AppAction["icon"],
    order: number,
    options: { isAvailable?: AppAction["isAvailable"]; keywordsKey?: string } = {},
): AppAction {
    return {
        id,
        group: "navigate",
        labelKey,
        icon,
        order,
        keywordsKey: options.keywordsKey,
        isAvailable: options.isAvailable,
        execute: (_context, helpers) => {
            helpers.router.push(href);
        },
    };
}

/**
 * The always-registered global actions the provider contributes: navigation to the primary
 * destinations, quick creation of every core record type, and utility actions. Each create action
 * opens a shell-owned overlay seeded with context-aware prefills derived from the current record; the
 * notification "mark all read" action is contributed separately by a bridge that owns the notification
 * context.
 */
export const SEED_ACTIONS: readonly AppAction[] = [
    navigateAction("navigate.dashboard", "navigate.dashboard", "/dashboard", HomeIcon, 10),
    navigateAction("navigate.companies", "navigate.companies", "/records/companies", BuildingOffice2Icon, 20),
    navigateAction("navigate.contacts", "navigate.contacts", "/records/contacts", UsersIcon, 30, {
        keywordsKey: "keywords.navigate.contacts",
    }),
    navigateAction("navigate.deals", "navigate.deals", "/records/deals", BriefcaseIcon, 40),
    navigateAction("navigate.pipelines", "navigate.pipelines", "/records/pipelines", FunnelIcon, 50),
    navigateAction("navigate.analytics", "navigate.analytics", "/overview/analytics", ChartBarIcon, 60),
    navigateAction("navigate.calendar", "navigate.calendar", "/overview/calendar", CalendarIcon, 70),
    navigateAction("navigate.settings", "navigate.settings", "/settings/members", Cog6ToothIcon, 80),
    navigateAction("navigate.organization", "navigate.organization", "/organization/members", BuildingLibraryIcon, 90, {
        isAvailable: (context) => context.can("ORGANIZATION_VIEW"),
    }),

    {
        id: "create.company",
        group: "create",
        labelKey: "create.company",
        icon: BuildingOffice2Icon,
        order: 10,
        keywordsKey: "keywords.create.company",
        execute: (context, helpers) => {
            helpers.openOverlay({ kind: "create-company", defaults: deriveCreateDefaults(context, "company") });
        },
    },
    {
        id: "create.person",
        group: "create",
        labelKey: "create.person",
        icon: UserPlusIcon,
        order: 20,
        keywordsKey: "keywords.create.person",
        execute: (context, helpers) => {
            helpers.openOverlay({ kind: "create-person", defaults: deriveCreateDefaults(context, "person") });
        },
    },
    {
        id: "create.deal",
        group: "create",
        labelKey: "create.deal",
        icon: BriefcaseIcon,
        order: 30,
        keywordsKey: "keywords.create.deal",
        execute: (context, helpers) => {
            helpers.openOverlay({ kind: "create-deal", defaults: deriveCreateDefaults(context, "deal") });
        },
    },
    {
        id: "create.task",
        group: "create",
        labelKey: "create.task",
        icon: CheckCircleIcon,
        order: 40,
        shortcut: "mod+alt+t",
        keywordsKey: "keywords.create.task",
        execute: (context, helpers) => {
            helpers.openOverlay({ kind: "create-task", defaults: deriveCreateDefaults(context, "task") });
        },
    },
    {
        id: "create.note",
        group: "create",
        labelKey: "create.note",
        icon: DocumentTextIcon,
        order: 50,
        shortcut: "mod+alt+n",
        keywordsKey: "keywords.create.note",
        execute: (context, helpers) => {
            helpers.openOverlay({ kind: "create-note", defaults: deriveCreateDefaults(context, "note") });
        },
    },
    {
        id: "create.activity",
        group: "create",
        labelKey: "create.activity",
        icon: ChatBubbleLeftRightIcon,
        order: 60,
        shortcut: "mod+alt+a",
        keywordsKey: "keywords.create.activity",
        execute: (context, helpers) => {
            helpers.openOverlay({ kind: "create-activity", defaults: deriveCreateDefaults(context, "activity") });
        },
    },

    {
        id: "utility.import-companies",
        group: "utility",
        labelKey: "utility.importCompanies",
        icon: ArrowUpTrayIcon,
        order: 30,
        keywordsKey: "keywords.utility.importCompanies",
        execute: (_context, helpers) => {
            helpers.openOverlay({ kind: "import-companies" });
        },
    },
    {
        id: "utility.import-contacts",
        group: "utility",
        labelKey: "utility.importContacts",
        icon: ArrowUpTrayIcon,
        order: 40,
        keywordsKey: "keywords.utility.importContacts",
        execute: (_context, helpers) => {
            helpers.openOverlay({ kind: "import-contacts" });
        },
    },
    {
        id: "utility.import-deals",
        group: "utility",
        labelKey: "utility.importDeals",
        icon: ArrowUpTrayIcon,
        order: 50,
        keywordsKey: "keywords.utility.importDeals",
        execute: (_context, helpers) => {
            helpers.openOverlay({ kind: "import-deals" });
        },
    },

    {
        id: "record.open",
        group: "record",
        labelKey: "record.open",
        icon: ArrowUpRightIcon,
        order: 4,
        isAvailable: (context) => context.record !== null && recordHref(context.record) !== null,
        execute: (context, helpers) => {
            if (!context.record) return;
            const href = recordHref(context.record);
            if (href) helpers.router.push(href);
        },
    },
    {
        id: "record.open-new-tab",
        group: "record",
        labelKey: "record.openNewTab",
        icon: ArrowTopRightOnSquareIcon,
        order: 6,
        isAvailable: (context) => context.record !== null && recordHref(context.record) !== null,
        execute: (context) => {
            if (!context.record) return;
            const href = recordHref(context.record);
            if (href) window.open(`${window.location.origin}${href}`, "_blank", "noopener,noreferrer");
        },
    },
    {
        id: "record.copy-link",
        group: "record",
        labelKey: "record.copyLink",
        icon: LinkIcon,
        order: 10,
        isAvailable: (context) => context.record !== null && recordHref(context.record) !== null,
        execute: async (context, helpers) => {
            if (!context.record) return;
            const href = recordHref(context.record);
            if (!href) return;
            await navigator.clipboard.writeText(`${window.location.origin}${href}`);
            toastSuccess(helpers.translate("feedback.recordLinkCopied", { label: context.record.label }));
        },
    },

    {
        id: "utility.copy-page-link",
        group: "utility",
        labelKey: "utility.copyPageLink",
        icon: BoltIcon,
        order: 10,
        execute: async (_context, helpers) => {
            await navigator.clipboard.writeText(window.location.href);
            toastSuccess(helpers.translate("feedback.linkCopied"));
        },
    },
];
