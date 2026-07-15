"use client";

import { useEffect, useMemo, useRef, useState } from "react";
import { useRouter } from "next/navigation";
import { useLocale, useTranslations } from "next-intl";
import { CameraIcon } from "@heroicons/react/24/outline";
import { Loader2Icon } from "lucide-react";

import type { User } from "@/app/lib/types";
import { ApiError, updateMyTimezone, updateUser } from "@/app/lib/api";
import { persistAuthenticatedLocale } from "@/app/lib/locale-preference";
import { toastError, toastSuccess } from "@/app/lib/toast";
import { formatDate, formatDateTime } from "@/app/lib/utils";
import type { Locale } from "@/i18n/config";
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
import {
    Select,
    SelectContent,
    SelectItem,
    SelectTrigger,
    SelectValue,
} from "@/components/ui/select";
import Rise from "@/app/components/motion/Rise";
import SectionHeader from "@/app/components/dashboard/SectionHeader";

type Props = {
    user: User;
};

type ProfileMutationChanges = Partial<Pick<
    User,
    "username" | "displayName" | "profilePictureUrl" | "timezone" | "locale"
>>;

type ProfileMutationResult =
    | { ok: true; value: ProfileMutationChanges | null }
    | { ok: false; error: unknown };

const comboboxInputClass =
    "rounded-lg border-0 bg-muted shadow-none ring-1 ring-border dark:bg-muted has-[[data-slot=input-group-control]:focus-visible]:ring-2 has-[[data-slot=input-group-control]:focus-visible]:ring-brand";
const selectTriggerClass =
    "w-full rounded-lg border-0 bg-muted shadow-none ring-1 ring-border focus-visible:ring-2 focus-visible:ring-brand dark:bg-muted";

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

async function settleProfileMutation(
    mutation: Promise<ProfileMutationChanges | null>,
): Promise<ProfileMutationResult> {
    try {
        return { ok: true, value: await mutation };
    } catch (error: unknown) {
        return { ok: false, error };
    }
}

export default function ProfilePanel({ user }: Props) {
    const t = useTranslations("AccountProfile");
    const locale = useLocale();
    const router = useRouter();
    const fileInputRef = useRef<HTMLInputElement>(null);

    const [displayName, setDisplayName] = useState(user.displayName);
    const [username, setUsername] = useState(user.username);
    const [timezone, setTimezone] = useState(user.timezone);
    const [preferredLocale, setPreferredLocale] = useState<Locale>(user.locale);
    const [confirmation, setConfirmation] = useState({ source: user, value: user });
    const [photo, setPhoto] = useState<File | null>(null);
    const [photoPreview, setPhotoPreview] = useState<string | null>(null);
    const [uploadedPhotoUrl, setUploadedPhotoUrl] = useState<string | null>(null);
    const previewUrlRef = useRef<string | null>(null);
    const [submitting, setSubmitting] = useState(false);
    const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({});
    const confirmedUser = confirmation.value;

    if (confirmation.source !== user) {
        setDisplayName((current) => current === confirmedUser.displayName ? user.displayName : current);
        setUsername((current) => current === confirmedUser.username ? user.username : current);
        setTimezone((current) => current === confirmedUser.timezone ? user.timezone : current);
        setPreferredLocale((current) => current === confirmedUser.locale ? user.locale : current);
        setConfirmation({ source: user, value: user });
    }

    const timeZones = useMemo(() => {
        const zones = supportedTimeZones();
        if (confirmedUser.timezone && !zones.includes(confirmedUser.timezone)) {
            return [confirmedUser.timezone, ...zones];
        }
        return zones;
    }, [confirmedUser.timezone]);

    useEffect(() => {
        return () => {
            if (previewUrlRef.current) URL.revokeObjectURL(previewUrlRef.current);
        };
    }, []);

    const dirty =
        displayName.trim() !== confirmedUser.displayName ||
        username.trim() !== confirmedUser.username ||
        timezone !== confirmedUser.timezone ||
        preferredLocale !== confirmedUser.locale ||
        photo !== null && uploadedPhotoUrl !== confirmedUser.profilePictureUrl;

    const selectPhoto = (file: File | null) => {
        if (previewUrlRef.current) URL.revokeObjectURL(previewUrlRef.current);
        const nextPreview = file ? URL.createObjectURL(file) : null;
        previewUrlRef.current = nextPreview;
        setPhotoPreview(nextPreview);
        setPhoto(file);
        setUploadedPhotoUrl(null);
    };

    const reset = () => {
        setDisplayName(confirmedUser.displayName);
        setUsername(confirmedUser.username);
        setTimezone(confirmedUser.timezone);
        setPreferredLocale(confirmedUser.locale);
        selectPhoto(null);
        setFieldErrors({});
    };

    const confirmUser = (nextUser: User) => {
        setConfirmation((current) => ({ ...current, value: nextUser }));
    };

    const handleSubmit = async (event: React.FormEvent<HTMLFormElement>) => {
        event.preventDefault();
        if (!dirty || submitting) return;
        setSubmitting(true);
        setFieldErrors({});

        const savedUser = confirmedUser;
        let completedMutation = false;
        try {
            const localeMutation: Promise<ProfileMutationChanges | null> =
                preferredLocale !== savedUser.locale
                    ? persistAuthenticatedLocale(preferredLocale).then((locale) => ({ locale }))
                    : Promise.resolve(null);
            const profileMutation = (async (): Promise<ProfileMutationChanges | null> => {
                let profilePictureUrl = savedUser.profilePictureUrl;
                if (photo) {
                    profilePictureUrl = uploadedPhotoUrl ?? (await uploadProfilePicture(photo));
                    if (!uploadedPhotoUrl) setUploadedPhotoUrl(profilePictureUrl);
                }

                const profileChanged =
                    displayName.trim() !== savedUser.displayName ||
                    username.trim() !== savedUser.username ||
                    profilePictureUrl !== savedUser.profilePictureUrl;
                if (!profileChanged) return null;

                const updatedUser = await updateUser(savedUser.id, {
                    username: username.trim(),
                    displayName: displayName.trim(),
                    email: savedUser.email,
                    profilePictureUrl,
                });
                return {
                    username: updatedUser.username,
                    displayName: updatedUser.displayName,
                    profilePictureUrl: updatedUser.profilePictureUrl,
                };
            })();
            const profileThenTimezone = (async () => {
                const profileResult = await settleProfileMutation(profileMutation);
                const timezoneMutation: Promise<ProfileMutationChanges | null> =
                    timezone !== savedUser.timezone
                        ? updateMyTimezone(timezone).then((updatedUser) => ({ timezone: updatedUser.timezone }))
                        : Promise.resolve(null);
                const timezoneResult = await settleProfileMutation(timezoneMutation);
                return { profileResult, timezoneResult };
            })();

            const [sequentialResults, localeResult] = await Promise.all([
                profileThenTimezone,
                settleProfileMutation(localeMutation),
            ]);
            const results = [
                sequentialResults.profileResult,
                sequentialResults.timezoneResult,
                localeResult,
            ];
            let nextUser = savedUser;
            let mutationFailed = false;
            let mutationError: unknown;
            for (const result of results) {
                if (result.ok && result.value !== null) {
                    nextUser = { ...nextUser, ...result.value };
                    completedMutation = true;
                } else if (!result.ok && !mutationFailed) {
                    mutationFailed = true;
                    mutationError = result.error;
                }
            }
            if (completedMutation) confirmUser(nextUser);
            if (mutationFailed) throw mutationError;

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
            if (completedMutation) router.refresh();
        } finally {
            setSubmitting(false);
        }
    };

    const previewUrl = photoPreview ?? confirmedUser.profilePictureUrl ?? null;
    const initial = confirmedUser.displayName?.slice(0, 1).toUpperCase() || "?";

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
                                onValueChange={(value) => setTimezone((value as string | null) ?? confirmedUser.timezone)}
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

                        <div className="grid gap-2">
                            <Label htmlFor="account-language">{t("languageLabel")}</Label>
                            <Select
                                value={preferredLocale}
                                onValueChange={(value) => {
                                    if (value === "en" || value === "ja") {
                                        setPreferredLocale(value);
                                    }
                                }}
                            >
                                <SelectTrigger id="account-language" className={selectTriggerClass}>
                                    <SelectValue />
                                </SelectTrigger>
                                <SelectContent>
                                    <SelectItem value="en">{t("languageEnglish")}</SelectItem>
                                    <SelectItem value="ja">{t("languageJapanese")}</SelectItem>
                                </SelectContent>
                            </Select>
                            <p className="text-sm text-muted-foreground">{t("languageHint")}</p>
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
                            variant="brand"
                            disabled={!dirty || submitting}
                            aria-busy={submitting}
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
