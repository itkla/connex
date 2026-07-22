import { useTranslations } from "next-intl";
import { cn } from "@/lib/utils";
import { type CampaignStatus } from "@/app/lib/types";

const STATUS_CLASS: Record<CampaignStatus, string> = {
    draft: "bg-muted text-muted-foreground ring-border",
    scheduled: "bg-brand-light text-brand-dark ring-brand/30",
    active: "bg-brand text-brand-foreground ring-brand",
    paused: "bg-risk-medium/15 text-risk-medium ring-risk-medium/30",
    completed: "bg-secondary text-secondary-foreground ring-border",
    archived: "bg-muted text-muted-foreground/70 ring-border",
};

/** A workspace-consistent status pill for a campaign's lifecycle state. */
export default function CampaignStatusBadge({ status }: { status: CampaignStatus }) {
    const t = useTranslations("CampaignStatus");
    return (
        <span
            className={cn(
                "inline-flex items-center rounded-full px-2 py-0.5 text-xs font-medium ring-1 ring-inset",
                STATUS_CLASS[status],
            )}
        >
            {t(status)}
        </span>
    );
}
