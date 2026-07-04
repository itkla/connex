"use client";

import { useEffect, useMemo, useRef, useState } from "react";
import { useRouter } from "next/navigation";
import { useLocale, useTranslations } from "next-intl";
import { CameraIcon } from "@heroicons/react/24/outline";
import { Loader2Icon } from "lucide-react";

import type { User } from "@/app/lib/types";
import { ApiError, updateMyTimezone, updateUser } from "@/app/lib/api";
import { toastError, toastSuccess } from "@/app/lib/toast";
import { formatDate, formatDateTime } from "@/app/lib/utils";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import {
    Combobox,
    ComboboxContent,
    ComboboxEmpty,
    ComboboxInput,
    ComboboxItem,
    ComboboxList,
} from "@/components/ui/combobox";
import Rise from "@/app/components/motion/Rise";
import SectionHeader from "@/app/components/dashboard/SectionHeader";

type Props = {
    user: User;
};

const comboboxInputClass =
    "rounded-lg border-0 bg-muted shadow-none ring-1 ring-border dark:bg-muted has-[[data-slot=input-group-control]:focus-visible]:ring-2 has-[[data-slot=input-group-control]:focus-visible]:ring-brand";

function supportedTimeZones(): string[] {
    const intl = Intl as typeof Intl & { supportedValuesOf?: (key: string) => string[] };
    try {
        return intl.supportedValuesOf?.("timeZone") ?? [];
    } catch {
        return [];
    }
}

async function uploadProfilePicture(file: File): Promise<string> {
    const formData = new FormData();
    formData.append("profilePicture", file);
    const res = await fetch("/api/users/me/profile-picture", { method: "PUT", body: formData });
    if (!res.ok) {
        throw new Error("upload-failed");
    }
    const data = (await res.json()) as { profilePictureUrl: string };
    return data.profilePictureUrl;
}

export default function ProfilePanel({ user }: Props) {
    const t = useTranslations("AccountProfile");
    const locale = useLocale();
    const router = useRouter();
    const fileInputRef = useRef<HTMLInputElement>(null);

    const [displayName, setDisplayName] = useState(user.displayName);
    const [username, setUsername] = useState(user.username);
    const [timezone, setTimezone] = useState(user.timezone);
    const [photo, setPhoto] = useState<File | null>(null);
    const [photoPreview, setPhotoPreview] = useState<string | null>(null);
    const [submitting, setSubmitting] = useState(false);
    const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({});

    const timeZones = useMemo(() => {
        const zones = supportedTimeZones();
        if (user.timezone && !zones.includes(user.timezone)) {
            return [user.timezone, ...zones];
        }
        return zones;
    }, [user.timezone]);

    useEffect(() => {
        return () => {
            if (photoPreview) URL.revokeObjectURL(photoPreview);
        };
    }, [photoPreview]);

    const dirty =
        displayName.trim() !== user.displayName ||
        username.trim() !== user.username ||
        timezone !== user.timezone ||
        photo !== null;

    const selectPhoto = (file: File | null) => {
        setPhotoPreview((prev) => {
            if (prev) URL.revokeObjectURL(prev);
            return file ? URL.createObjectURL(file) : null;
        });
        setPhoto(file);
    };

    const reset = () => {
        setDisplayName(user.displayName);
        setUsername(user.username);
        setTimezone(user.timezone);
        selectPhoto(null);
        setFieldErrors({});
    };

    const handleSubmit = async (event: React.FormEvent<HTMLFormElement>) => {
        event.preventDefault();
        if (!dirty || submitting) return;
        setSubmitting(true);
        setFieldErrors({});

        try {
            let profilePictureUrl = user.profilePictureUrl;
            if (photo) {
                profilePictureUrl = await uploadProfilePicture(photo);
            }

            const profileChanged =
                displayName.trim() !== user.displayName ||
                username.trim() !== user.username ||
                photo !== null;

            if (profileChanged) {
                await updateUser(user.id, {
                    username: username.trim(),
                    displayName: displayName.trim(),
                    email: user.email,
                    profilePictureUrl,
                });
            }

            if (timezone !== user.timezone) {
                await updateMyTimezone(timezone);
            }

            selectPhoto(null);
            toastSuccess(t("saved"));
            router.refresh();
        } catch (err) {
            if (err instanceof ApiError && err.fieldErrors) {
                setFieldErrors(err.fieldErrors);
            }
            const message =
                err instanceof Error && err.message === "upload-failed"
                    ? t("photoUploadFailed")
                    : err instanceof ApiError
                      ? err.message
                      : t("saveFailed");
            toastError(message);
        } finally {
            setSubmitting(false);
        }
    };

    const previewUrl = photoPreview ?? user.profilePictureUrl ?? null;
    const initial = user.displayName?.slice(0, 1).toUpperCase() || "?";

    return (
        <div className="space-y-10">
            <Rise className="space-y-3">
                <div>
                    <SectionHeader title={t("title")} />
                    <p className="max-w-prose px-6 text-sm text-muted-foreground">{t("subtitle")}</p>
                </div>

                <form onSubmit={handleSubmit} className="overflow-hidden rounded-2xl border border-border bg-card">
                    <div className="space-y-6 p-6">
                        <div className="flex items-center gap-4">
                            <button
                                type="button"
                                onClick={() => fileInputRef.current?.click()}
                                aria-label={t("changePhotoAria")}
                                className="group relative size-20 shrink-0 overflow-hidden rounded-full ring-1 ring-border outline-none transition-transform duration-150 ease-out focus-visible:ring-2 focus-visible:ring-brand active:scale-[0.97]"
                            >
                                {previewUrl ? (
                                    <img src={previewUrl} alt={t("photoAlt")} className="size-full object-cover" />
                                ) : (
                                    <span className="flex size-full items-center justify-center bg-brand-light text-2xl font-medium text-brand-dark">
                                        {initial}
                                    </span>
                                )}
                                <span className="absolute inset-0 grid place-items-center bg-black/50 text-white opacity-0 transition-opacity duration-150 ease-out group-hover:opacity-100 group-focus-visible:opacity-100 motion-reduce:transition-none">
                                    <CameraIcon className="size-5" />
                                </span>
                            </button>
                            <div className="space-y-1.5">
                                <Button
                                    type="button"
                                    variant="outline"
                                    size="sm"
                                    onClick={() => fileInputRef.current?.click()}
                                >
                                    {t("changePhoto")}
                                </Button>
                                <p className="text-xs text-muted-foreground">{t("photoHint")}</p>
                            </div>
                            <input
                                ref={fileInputRef}
                                type="file"
                                accept="image/*"
                                tabIndex={-1}
                                aria-hidden
                                className="sr-only"
                                onChange={(e) => selectPhoto(e.target.files?.[0] ?? null)}
                            />
                        </div>

                        <div className="grid gap-2">
                            <Label htmlFor="account-display-name">{t("displayNameLabel")}</Label>
                            <Input
                                id="account-display-name"
                                value={displayName}
                                onChange={(e) => setDisplayName(e.target.value)}
                                maxLength={255}
                                required
                                aria-invalid={fieldErrors.displayName ? true : undefined}
                            />
                            {fieldErrors.displayName && (
                                <p className="text-sm text-destructive">{fieldErrors.displayName}</p>
                            )}
                        </div>

                        <div className="grid gap-2">
                            <Label htmlFor="account-username">{t("usernameLabel")}</Label>
                            <Input
                                id="account-username"
                                value={username}
                                onChange={(e) => setUsername(e.target.value)}
                                maxLength={255}
                                required
                                aria-invalid={fieldErrors.username ? true : undefined}
                            />
                            {fieldErrors.username && (
                                <p className="text-sm text-destructive">{fieldErrors.username}</p>
                            )}
                        </div>

                        <div className="grid gap-2">
                            <Label htmlFor="account-email">{t("emailLabel")}</Label>
                            <Input
                                id="account-email"
                                type="email"
                                value={user.email}
                                readOnly
                                aria-readonly
                                className="cursor-not-allowed text-muted-foreground"
                            />
                            <p className="text-sm text-muted-foreground">{t("emailReadonlyHint")}</p>
                        </div>

                        <div className="grid gap-2">
                            <Label htmlFor="account-timezone">{t("timezoneLabel")}</Label>
                            <Combobox
                                items={timeZones}
                                value={timezone}
                                onValueChange={(value) => setTimezone((value as string | null) ?? user.timezone)}
                                itemToStringLabel={(tz: string) => tz}
                            >
                                <ComboboxInput
                                    id="account-timezone"
                                    placeholder={t("timezonePlaceholder")}
                                    className={comboboxInputClass}
                                />
                                <ComboboxContent className="pointer-events-auto">
                                    <ComboboxList>
                                        <ComboboxEmpty>{t("timezoneEmpty")}</ComboboxEmpty>
                                        {timeZones.map((tz) => (
                                            <ComboboxItem key={tz} value={tz}>
                                                {tz}
                                            </ComboboxItem>
                                        ))}
                                    </ComboboxList>
                                </ComboboxContent>
                            </Combobox>
                            <p className="text-sm text-muted-foreground">{t("timezoneHint")}</p>
                        </div>
                    </div>

                    <div className="flex items-center justify-end gap-2 border-t border-border bg-muted/30 px-6 py-4">
                        {dirty && (
                            <Button type="button" variant="ghost" onClick={reset} disabled={submitting}>
                                {t("discard")}
                            </Button>
                        )}
                        <Button
                            type="submit"
                            disabled={!dirty || submitting}
                            aria-busy={submitting}
                            className="bg-brand text-white hover:bg-brand-hover"
                        >
                            {submitting ? <Loader2Icon className="size-4 animate-spin" /> : null}
                            {submitting ? t("saving") : t("save")}
                        </Button>
                    </div>
                </form>
            </Rise>

            <Rise className="space-y-3">
                <SectionHeader title={t("detailsTitle")} />
                <dl className="divide-y divide-border overflow-hidden rounded-2xl border border-border bg-card">
                    <div className="flex items-center justify-between gap-4 px-4 py-3">
                        <dt className="text-sm text-muted-foreground">{t("memberSince")}</dt>
                        <dd className="text-sm text-foreground">{formatDate(user.createdAt, locale)}</dd>
                    </div>
                    <div className="flex items-center justify-between gap-4 px-4 py-3">
                        <dt className="text-sm text-muted-foreground">{t("lastLogin")}</dt>
                        <dd className="text-sm text-foreground">
                            {user.lastLoginAt ? formatDateTime(user.lastLoginAt, locale) : t("lastLoginNever")}
                        </dd>
                    </div>
                </dl>
            </Rise>
        </div>
    );
}
