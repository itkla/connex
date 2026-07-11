import {
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
    UsersIcon,
} from "@heroicons/react/24/outline";

import { toastSuccess } from "@/app/lib/toast";
import type { ActiveRecordRef, AppAction, RecordType } from "./types";

const RECORD_PATHS: Record<RecordType, string | null> = {
    company: "/records/companies",
    person: "/records/contacts",
    deal: "/records/deals",
    task: "/activity/tasks",
    activity: "/activity/all",
    note: "/activity/notes",
};

/** Resolves the canonical in-app path for a record, or null when the type has no detail route. */
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
 * destinations, quick creation of the self-contained record types, and utility actions. Company,
 * person, and deal creation are intentionally deferred to the Quick Create work (#403), which extracts
 * their reusable form containers; the notification "mark all read" action is contributed separately by
 * a bridge that owns the notification context.
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
        id: "create.task",
        group: "create",
        labelKey: "create.task",
        icon: CheckCircleIcon,
        order: 10,
        shortcut: "mod+alt+t",
        keywordsKey: "keywords.create.task",
        execute: (_context, helpers) => {
            helpers.openOverlay({ kind: "create-task" });
        },
    },
    {
        id: "create.note",
        group: "create",
        labelKey: "create.note",
        icon: DocumentTextIcon,
        order: 20,
        shortcut: "mod+alt+n",
        keywordsKey: "keywords.create.note",
        execute: (_context, helpers) => {
            helpers.openOverlay({ kind: "create-note" });
        },
    },
    {
        id: "create.activity",
        group: "create",
        labelKey: "create.activity",
        icon: ChatBubbleLeftRightIcon,
        order: 30,
        shortcut: "mod+alt+a",
        keywordsKey: "keywords.create.activity",
        execute: (_context, helpers) => {
            helpers.openOverlay({ kind: "create-activity" });
        },
    },

    {
        id: "record.copy-link",
        group: "record",
        labelKey: "record.copyLink",
        icon: LinkIcon,
        order: 10,
        isAvailable: (context) => context.record !== null,
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
