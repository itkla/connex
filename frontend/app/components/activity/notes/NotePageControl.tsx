"use client";

import { useTranslations } from "next-intl";
import { Button } from "@/components/ui/button";

/** Shared continuation state for note previews and backlinks. */
export default function NotePageControl({ loading, failed, onLoadMore }: {
    loading: boolean;
    failed: boolean;
    onLoadMore: () => void;
}) {
    const t = useTranslations("NotePagination");
    return (
        <div className="flex flex-col items-center gap-2 px-6 py-4">
            {failed && <p role="alert" className="text-sm text-muted-foreground">{t("failed")}</p>}
            <Button variant="outline" size="toolbar" disabled={loading} aria-busy={loading} onClick={onLoadMore}>
                {loading ? t("loading") : failed ? t("retry") : t("loadMore")}
            </Button>
        </div>
    );
}
