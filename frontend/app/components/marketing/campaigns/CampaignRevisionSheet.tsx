"use client";

import { useTranslations } from "next-intl";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Textarea } from "@/components/ui/textarea";
import {
    Drawer, DrawerContent, DrawerDescription, DrawerFooter, DrawerHeader, DrawerTitle,
} from "@/components/ui/drawer";
import { useIsMobile } from "@/app/hooks/useIsMobile";
import type { CampaignMessageRevision } from "@/app/lib/types";

/** Inspects saved content as inert source text without executing HTML or loading its resources. */
export default function CampaignRevisionSheet({
    revision,
    isSms,
    onClose,
    onUse,
}: {
    revision: CampaignMessageRevision | null;
    isSms: boolean;
    onClose: () => void;
    onUse?: (revision: CampaignMessageRevision) => void;
}) {
    const t = useTranslations("CampaignMessages");
    const isMobile = useIsMobile();

    return (
        <Drawer open={revision !== null} onOpenChange={(open) => { if (!open) onClose(); }}
            swipeDirection={isMobile ? "down" : "right"}>
            <DrawerContent className="gap-0 p-0 sm:max-w-xl">
                <DrawerHeader className="border-b border-border pr-12">
                    <DrawerTitle>{t("inspectRevision", { version: revision?.version ?? 0 })}</DrawerTitle>
                    <DrawerDescription>{t("sourceInspectionHint")}</DrawerDescription>
                </DrawerHeader>
                {revision && (
                    <div className="flex-1 space-y-4 overflow-y-auto p-4">
                        <div className="grid gap-1.5">
                            <Label htmlFor="saved-revision-locale">{t("locale")}</Label>
                            <Input id="saved-revision-locale" value={revision.locale} readOnly />
                        </div>
                        {!isSms && (
                            <>
                                <div className="grid gap-1.5">
                                    <Label htmlFor="saved-revision-subject">{t("subject")}</Label>
                                    <Textarea id="saved-revision-subject" value={revision.subject ?? ""} readOnly
                                        className="min-h-16 resize-y" />
                                </div>
                                <div className="grid gap-1.5">
                                    <Label htmlFor="saved-revision-html">{t("bodyHtml")}</Label>
                                    <Textarea id="saved-revision-html" value={revision.bodyHtml ?? ""} readOnly
                                        className="min-h-48 resize-y font-mono" />
                                </div>
                            </>
                        )}
                        <div className="grid gap-1.5">
                            <Label htmlFor="saved-revision-text">{isSms ? t("smsBody") : t("bodyText")}</Label>
                            <Textarea id="saved-revision-text" value={revision.bodyText ?? ""} readOnly
                                className="min-h-28 resize-y" />
                        </div>
                    </div>
                )}
                <DrawerFooter className="border-t border-border">
                    {revision && onUse && (
                        <Button onClick={() => onUse(revision)}>{t("startFromRevision")}</Button>
                    )}
                    <Button variant="outline" onClick={onClose}>{t("closeInspection")}</Button>
                </DrawerFooter>
            </DrawerContent>
        </Drawer>
    );
}
