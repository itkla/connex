import { useTranslations } from "next-intl";
import { cn } from "@/lib/utils";
import { type CampaignExportStatus } from "@/app/lib/types";

const STATUS_CLASS: Record<CampaignExportStatus, string> = {
    draft: "bg-muted text-muted-foreground ring-border",
    running: "bg-brand text-brand-foreground ring-brand",
    completed: "bg-secondary text-secondary-foreground ring-border",
    failed: "bg-destructive/15 text-destructive ring-destructive/30",
    needs_reconciliation: "bg-warning/15 text-warning-foreground ring-warning/30",
};

/** A workspace-consistent status pill for a campaign audience export's lifecycle. */
export default function ExportStatusBadge({ status }: { status: CampaignExportStatus }) {
    const t = useTranslations("CampaignExports");
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
