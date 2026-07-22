import { useTranslations } from "next-intl";
import { cn } from "@/lib/utils";
import { type CampaignSendStatus } from "@/app/lib/types";

const STATUS_CLASS: Record<CampaignSendStatus, string> = {
    draft: "bg-muted text-muted-foreground ring-border",
    queued: "bg-brand-light text-brand-dark ring-brand/30",
    running: "bg-brand text-brand-foreground ring-brand",
    paused: "bg-risk-medium/15 text-risk-medium ring-risk-medium/30",
    completed: "bg-secondary text-secondary-foreground ring-border",
    failed: "bg-destructive/15 text-destructive ring-destructive/30",
    cancelled: "bg-muted text-muted-foreground/70 ring-border",
};

/** A workspace-consistent status pill for a campaign send's dispatch lifecycle. */
export default function SendStatusBadge({ status }: { status: CampaignSendStatus }) {
    const t = useTranslations("CampaignSends");
    return (
        <span
            className={cn(
                "inline-flex items-center rounded-full px-2 py-0.5 text-xs font-medium ring-1 ring-inset",
                STATUS_CLASS[status],
            )}
        >
            {t(`status.${status}`)}
        </span>
    );
}
