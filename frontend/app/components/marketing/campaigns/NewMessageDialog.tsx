"use client";

import { type Dispatch, type FormEvent, type SetStateAction, useState } from "react";
import {
    ResponsiveDialog,
    ResponsiveDialogContent,
    ResponsiveDialogHeader,
    ResponsiveDialogTitle,
    ResponsiveDialogDescription,
    ResponsiveDialogFooter,
    ResponsiveDialogClose,
} from "@/components/ui/responsive-dialog";
import { Button } from "@/components/ui/button";
import { Label } from "@/components/ui/label";
import {
    Select,
    SelectTrigger,
    SelectValue,
    SelectContent,
    SelectItem,
} from "@/components/ui/select";
import { DialogStatusCover } from "@/components/ui/dialog-status-cover";
import { cn } from "@/lib/utils";
import { type CampaignChannel, type CampaignMessagePayload } from "@/app/lib/types";
import { isFieldError } from "@/app/lib/api";
import { useFieldErrors } from "@/app/hooks/useFieldErrors";
import { DevicePhoneMobileIcon, EnvelopeIcon, InboxStackIcon } from "@heroicons/react/24/outline";
import { Loader2Icon } from "lucide-react";
import { useTranslations } from "next-intl";

const inputBase =
    "w-full rounded-lg bg-muted py-2 text-sm text-foreground placeholder:text-muted-foreground outline-none ring-1 ring-border transition focus:ring-2 focus:ring-brand";
const inputError = "ring-2 ring-destructive focus:ring-destructive";
const leadIcon =
    "pointer-events-none absolute left-3 top-1/2 size-4 -translate-y-1/2 text-muted-foreground transition-colors group-focus-within:text-brand";

const CHANNELS: CampaignChannel[] = ["email", "sms"];

type Props = {
    open: boolean;
    onOpenChange: (open: boolean) => void;
    payload: CampaignMessagePayload;
    setPayload: Dispatch<SetStateAction<CampaignMessagePayload>>;
    isCreating: boolean;
    isSuccess?: boolean;
    createMessage: () => void | Promise<void>;
};

/**
 * Controlled create-message dialog. The parent owns the payload and the submit handler, which
 * re-throws field errors so this form can surface them per field.
 */
export default function NewMessageDialog({
    open,
    onOpenChange,
    payload,
    setPayload,
    isCreating,
    isSuccess = false,
    createMessage,
}: Props) {
    const t = useTranslations("CampaignMessages");
    const { fieldErrors, reset: resetFieldErrors, clearError, captureFieldErrors } = useFieldErrors();
    const ChannelIcon = payload.channel === "sms" ? DevicePhoneMobileIcon : EnvelopeIcon;

    const hasErrors = Object.keys(fieldErrors).length > 0;
    const status: "idle" | "loading" | "success" | "error" = isCreating
        ? "loading"
        : hasErrors
            ? "error"
            : isSuccess
                ? "success"
                : "idle";

    const [wasOpen, setWasOpen] = useState(open);
    if (open !== wasOpen) {
        setWasOpen(open);
        if (open) resetFieldErrors();
    }

    const handleOpenChange = (next: boolean) => {
        if (!next && isCreating) return;
        onOpenChange(next);
    };

    const handleSubmit = async (e: FormEvent) => {
        e.preventDefault();
        if (isCreating) return;
        resetFieldErrors();
        try {
            await createMessage();
        } catch (err) {
            captureFieldErrors(err);
            if (isFieldError(err)) {
                const firstKey = Object.keys(err.fieldErrors)[0];
                if (firstKey) {
                    requestAnimationFrame(() =>
                        document.getElementById(`message-${firstKey}`)?.focus(),
                    );
                }
            }
        }
    };

    return (
        <ResponsiveDialog open={open} onOpenChange={handleOpenChange}>
            <ResponsiveDialogContent className="gap-0 overflow-hidden p-0 sm:max-w-md">
                <DialogStatusCover status={status} />
                <div className="px-6 pt-6">
                    <ResponsiveDialogHeader className="mb-5">
                        <ResponsiveDialogTitle className="text-xl font-semibold tracking-tight">
                            {t("newTitle")}
                        </ResponsiveDialogTitle>
                        <ResponsiveDialogDescription>{t("newDescription")}</ResponsiveDialogDescription>
                    </ResponsiveDialogHeader>
                </div>

                <div className="px-6 pb-4">
                    <form id="new-message-form" onSubmit={handleSubmit} className="grid gap-5">
                        <div className="grid gap-1.5">
                            <Label htmlFor="message-name">
                                {t("name")} <span className="text-muted-foreground">*</span>
                            </Label>
                            <div className="group relative">
                                <InboxStackIcon className={leadIcon} />
                                <input
                                    id="message-name"
                                    type="text"
                                    value={payload.name}
                                    onChange={(e) => {
                                        setPayload((prev) => ({ ...prev, name: e.target.value }));
                                        clearError("name");
                                    }}
                                    className={cn(inputBase, "pl-9 pr-3", fieldErrors.name && inputError)}
                                    placeholder={t("namePlaceholder")}
                                    aria-invalid={Boolean(fieldErrors.name)}
                                    aria-describedby={fieldErrors.name ? "message-name-error" : undefined}
                                    autoFocus
                                    required
                                />
                            </div>
                            {fieldErrors.name && (
                                <p id="message-name-error" className="text-sm text-destructive">
                                    {fieldErrors.name}
                                </p>
                            )}
                        </div>

                        <div className="grid gap-1.5">
                            <Label htmlFor="message-channel">{t("channel")}</Label>
                            <div className="group relative">
                                <ChannelIcon className={leadIcon} />
                                <Select
                                    value={payload.channel}
                                    onValueChange={(value) =>
                                        setPayload((prev) => ({ ...prev, channel: value as CampaignChannel }))
                                    }
                                >
                                    <SelectTrigger id="message-channel" className="w-full pl-9">
                                        <SelectValue />
                                    </SelectTrigger>
                                    <SelectContent>
                                        {CHANNELS.map((value) => (
                                            <SelectItem key={value} value={value}>
                                                {t(`channels.${value}`)}
                                            </SelectItem>
                                        ))}
                                    </SelectContent>
                                </Select>
                            </div>
                            <p className="text-xs text-muted-foreground">{t("channelHint")}</p>
                        </div>
                    </form>
                </div>

                <ResponsiveDialogFooter className="border-t border-border/60 bg-popover px-6 py-4">
                    <ResponsiveDialogClose asChild>
                        <Button type="button" variant="outline" disabled={isCreating}>
                            {t("cancel")}
                        </Button>
                    </ResponsiveDialogClose>
                    <Button
                        type="submit"
                        form="new-message-form"
                        variant="brand"
                        disabled={isCreating || hasErrors || isSuccess}
                        className="min-w-24 shadow-sm transition hover:shadow-md"
                    >
                        {isCreating ? (
                            <>
                                <Loader2Icon className="size-4 animate-spin" />
                                {t("creating")}
                            </>
                        ) : (
                            t("submit")
                        )}
                    </Button>
                </ResponsiveDialogFooter>
            </ResponsiveDialogContent>
        </ResponsiveDialog>
    );
}
