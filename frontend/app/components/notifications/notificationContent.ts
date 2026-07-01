import {
    ArrowsRightLeftIcon,
    ArrowTrendingDownIcon,
    BellIcon,
    BriefcaseIcon,
    CheckCircleIcon,
    UserGroupIcon,
} from "@heroicons/react/24/outline";

import { type Notification } from "@/app/lib/types";
import { formatDate } from "@/app/lib/utils";

type Translator = (key: string, values?: Record<string, string | number | Date>) => string;

const RELATIONSHIP_REASON_KEYS: Record<string, string> = {
    closing_soon: "relationshipReasonClosingSoon",
    high_value: "relationshipReasonHighValue",
    late_stage: "relationshipReasonLateStage",
    key_role: "relationshipReasonKeyContact",
};

const DEAL_RISK_FACTOR_KEYS: Record<string, string> = {
    close_overdue: "dealRiskReasonOverdue",
    closing_soon_quiet: "dealRiskReasonClosingQuiet",
    stalled: "dealRiskReasonStalled",
    stakeholder_cold: "dealRiskReasonStakeholderCold",
    no_stakeholders: "dealRiskReasonNoStakeholders",
};

/**
 * Notification content for a given notification.
 * @param notification - The notification to get the content for.
 * @param t - The translator to use.
 * @param locale - The locale to use.
 * @returns The notification content.
 */
export function notificationContent(notification: Notification, t: Translator, locale: string) {
    const data = notification.data ?? {};
    if (notification.type === "task.due") {
        return {
            title: t(notification.severity === "critical" ? "taskOverdueTitle" : "taskDueTitle"),
            body: t(notification.severity === "critical" ? "taskOverdueBody" : "taskDueBody", {
                task: text(data.task, notification.sourceLabel ?? notification.title),
                date: dateText(data.dueDate, locale),
            }),
        };
    }
    if (notification.type === "deal.close") {
        return {
            title: t(notification.severity === "critical" ? "dealOverdueTitle" : "dealDueTitle"),
            body: t(notification.severity === "critical" ? "dealOverdueBody" : "dealDueBody", {
                deal: text(data.deal, notification.sourceLabel ?? notification.title),
                date: dateText(data.expectedCloseDate, locale),
            }),
        };
    }
    if (notification.type === "deal.risk") {
        const topFactor = typeof data.topFactor === "string" ? data.topFactor : undefined;
        const reasonKey = topFactor ? DEAL_RISK_FACTOR_KEYS[topFactor] : undefined;
        const deal = text(data.deal, notification.sourceLabel ?? notification.title);
        return {
            title: t(notification.severity === "critical" ? "dealRiskHighTitle" : "dealRiskMediumTitle"),
            body: reasonKey ? t("dealRiskBody", { deal, reason: t(reasonKey) }) : t("dealRiskBodyGeneric", { deal }),
        };
    }
    if (notification.type === "relationship.intro_opportunity") {
        return {
            title: t("introOpportunityTitle"),
            body: t("introOpportunityBody", {
                personA: text(data.personAName, notification.sourceLabel ?? ""),
                personB: text(data.personBName, notification.contextLabel ?? ""),
            }),
        };
    }
    if (notification.type === "relationship.cooling") {
        const cold = data.band === "cold";
        const reasons = Array.isArray(data.priorityReasons) ? data.priorityReasons : [];
        const primaryReason = typeof reasons[0] === "string" ? reasons[0] : null;
        const reasonKey = primaryReason ? RELATIONSHIP_REASON_KEYS[primaryReason] : undefined;
        const body = t(cold ? "relationshipColdBody" : "relationshipCoolingBody", {
            person: text(data.person, notification.sourceLabel ?? notification.title),
            deal: text(data.deal, notification.contextLabel ?? ""),
            days: text(data.daysSinceTouch, ""),
        });
        return {
            title: t(cold ? "relationshipColdTitle" : "relationshipCoolingTitle"),
            body: reasonKey ? `${body} · ${t(reasonKey)}` : body,
        };
    }
    if (notification.type === "workspace.join") {
        return {
            title: t("workspaceJoinTitle"),
            body: t("workspaceJoinBody", { workspace: notification.workspaceName ?? "" }),
        };
    }
    return { title: notification.title, body: notification.body ?? "" };
}

export function safeNotificationUrl(value?: string | null) {
    return value?.startsWith("/") && !value.startsWith("//") ? value : null;
}

/** Leading icon for a notification, mirroring the sidebar's entity icons. */
export function notificationIcon(notification: Notification) {
    if (notification.type.startsWith("task.")) return CheckCircleIcon;
    if (notification.type.startsWith("deal.")) return BriefcaseIcon;
    if (notification.type === "relationship.intro_opportunity") return ArrowsRightLeftIcon;
    if (notification.type.startsWith("relationship.")) return ArrowTrendingDownIcon;
    if (notification.type.startsWith("workspace.")) return UserGroupIcon;
    return BellIcon;
}

export type NotificationSeverityStyle = {
    container: string;
    chip: string;
    dot: string;
    accent: string;
};

const SEVERITY_STYLES: Record<string, NotificationSeverityStyle> = {
    critical: {
        container: "border-destructive/30 bg-destructive/10",
        chip: "bg-red-100 text-red-700 dark:bg-red-950/40 dark:text-red-300",
        dot: "bg-red-500",
        accent: "text-destructive",
    },
    warning: {
        container: "border-amber-500/30 bg-amber-500/10",
        chip: "bg-amber-100 text-amber-700 dark:bg-amber-950/40 dark:text-amber-300",
        dot: "bg-amber-500",
        accent: "text-amber-600 dark:text-amber-400",
    },
    info: {
        container: "border-blue-500/30 bg-blue-500/10",
        chip: "bg-blue-100 text-blue-700 dark:bg-blue-950/40 dark:text-blue-300",
        dot: "bg-blue-500",
        accent: "text-blue-600 dark:text-blue-400",
    },
};

/** Severity-driven color classes shared by the inbox rows and the entity banner. */
export function notificationSeverityStyle(severity: string): NotificationSeverityStyle {
    return SEVERITY_STYLES[severity] ?? SEVERITY_STYLES.info;
}

function text(value: unknown, fallback: string) {
    return typeof value === "string" || typeof value === "number" ? String(value) : fallback;
}

function dateText(value: unknown, locale: string) {
    return typeof value === "string" ? formatDate(value, locale) : "";
}