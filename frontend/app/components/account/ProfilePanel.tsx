"use client";

import {
    type Dispatch,
    type FormEvent,
    useEffect,
    useMemo,
    useReducer,
    useRef,
    useState,
} from "react";
import { useRouter } from "next/navigation";
import { useLocale, useTranslations } from "next-intl";
import { CameraIcon } from "@heroicons/react/24/outline";
import { Loader2Icon } from "lucide-react";

import type { User } from "@/app/lib/types";
import { ApiError, updateMyTimezone, updateUser, uploadCurrentUserProfilePicture } from "@/app/lib/api";
import { persistAuthenticatedLocale } from "@/app/lib/locale-preference";
import { isManagedImageFile, MANAGED_IMAGE_ACCEPT } from "@/app/lib/managed-image";
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
import ChangeEmailDialog from "@/app/components/account/ChangeEmailDialog";
import ProtectedMediaImage from "@/app/components/ProtectedMediaImage";

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

type ProfileDraft = {
    displayName: string;
    username: string;
    timezone: string;
    preferredLocale: Locale;
};

type ProfileDraftAction =
    | { type: "reset"; value: ProfileDraft }
    | { type: "sync"; previous: ProfileDraft; value: ProfileDraft }
    | { type: "setText"; field: "displayName" | "username" | "timezone"; value: string }
    | { type: "setLocale"; value: Locale };

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

function profileDraft(user: User): ProfileDraft {
    return {
        displayName: user.displayName,
        username: user.username,
        timezone: user.timezone,
        preferredLocale: user.locale,
    };
}

function profileDraftReducer(state: ProfileDraft, action: ProfileDraftAction): ProfileDraft {
    switch (action.type) {
        case "reset":
            return action.value;
        case "sync":
            return {
                displayName: state.displayName === action.previous.displayName
                    ? action.value.displayName
                    : state.displayName,
                username: state.username === action.previous.username
                    ? action.value.username
                    : state.username,
                timezone: state.timezone === action.previous.timezone
                    ? action.value.timezone
                    : state.timezone,
                preferredLocale: state.preferredLocale === action.previous.preferredLocale
                    ? action.value.preferredLocale
                    : state.preferredLocale,
            };
        case "setText":
            return { ...state, [action.field]: action.value };
        case "setLocale":
            return { ...state, preferredLocale: action.value };
    }
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

type ProfilePhotoFieldProps = {
    previewUrl: string | null;
    initial: string;
    disabled: boolean;
    onSelect: (file: File | null) => void;
};

function ProfilePhotoField({ previewUrl, initial, disabled, onSelect }: ProfilePhotoFieldProps) {
    const t = useTranslations("AccountProfile");
    const fileInputRef = useRef<HTMLInputElement>(null);

    return (
        <div className="flex items-center gap-4">
            <button
                type="button"
                disabled={disabled}
                onClick={() => fileInputRef.current?.click()}
                aria-label={t("changePhotoAria")}
                className="group relative size-20 shrink-0 overflow-hidden rounded-full ring-1 ring-border outline-none transition-transform duration-150 ease-out focus-visible:ring-2 focus-visible:ring-brand active:scale-[0.97] disabled:cursor-not-allowed disabled:opacity-50"
            >
                <ProtectedMediaImage
                    src={previewUrl}
                    alt={t("photoAlt")}
                    className="size-full object-cover"
                    fallback={(
                        <span className="flex size-full items-center justify-center bg-brand-light text-2xl font-medium text-brand-dark">
                            {initial}
                        </span>
                    )}
                />
                <span className="absolute inset-0 grid place-items-center bg-black/50 text-white opacity-0 transition-opacity duration-150 ease-out group-hover:opacity-100 group-focus-visible:opacity-100 motion-reduce:transition-none">
                    <CameraIcon className="size-5" />
                </span>
            </button>
            <div className="space-y-1.5">
                <Button
                    type="button"
                    variant="outline"
                    size="sm"
                    disabled={disabled}
                    onClick={() => fileInputRef.current?.click()}
                >
                    {t("changePhoto")}
                </Button>
                <p className="text-xs text-muted-foreground">{t("photoHint")}</p>
            </div>
            <input
                ref={fileInputRef}
                type="file"
                accept={MANAGED_IMAGE_ACCEPT}
                disabled={disabled}
                tabIndex={-1}
                aria-hidden
                className="sr-only"
                onChange={(event) => {
                    const file = event.currentTarget.files?.[0] ?? null;
                    event.currentTarget.value = "";
                    onSelect(file);
                }}
            />
        </div>
    );
}

function ProfileDetails({ user }: Props) {
    const t = useTranslations("AccountProfile");
    const locale = useLocale();

    return (
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
    );
}

function ProfileFormActions({
    dirty,
    submitting,
    onReset,
}: {
    dirty: boolean;
    submitting: boolean;
    onReset: () => void;
}) {
    const t = useTranslations("AccountProfile");

    return (
        <div className="flex items-center justify-end gap-2 border-t border-border bg-muted/30 px-6 py-4">
            {dirty && (
                <Button type="button" variant="ghost" onClick={onReset} disabled={submitting}>
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
    );
}

type ProfileFormProps = {
    user: User;
    confirmedUser: User;
    draft: ProfileDraft;
    timeZones: string[];
    fieldErrors: Record<string, string>;
    previewUrl: string | null;
    initial: string;
    dirty: boolean;
    submitting: boolean;
    dispatchDraft: Dispatch<ProfileDraftAction>;
    onSelectPhoto: (file: File | null) => void;
    onReset: () => void;
    onSubmit: (event: FormEvent<HTMLFormElement>) => void;
    onChangeEmail: () => void;
};

function ProfileForm({
    user,
    confirmedUser,
    draft,
    timeZones,
    fieldErrors,
    previewUrl,
    initial,
    dirty,
    submitting,
    dispatchDraft,
    onSelectPhoto,
    onReset,
    onSubmit,
    onChangeEmail,
}: ProfileFormProps) {
    const t = useTranslations("AccountProfile");
    const { displayName, username, timezone, preferredLocale } = draft;

    return (
        <Rise className="space-y-3">
            <div>
                <SectionHeader title={t("title")} />
                <p className="max-w-prose px-6 text-sm text-muted-foreground">{t("subtitle")}</p>
            </div>

            <form onSubmit={onSubmit} className="overflow-hidden rounded-2xl border border-border bg-card">
                <div className="space-y-6 p-6">
                    <ProfilePhotoField
                        previewUrl={previewUrl}
                        initial={initial}
                        disabled={submitting}
                        onSelect={onSelectPhoto}
                    />

                    <div className="grid gap-2">
                        <Label htmlFor="account-display-name">{t("displayNameLabel")}</Label>
                        <Input
                            id="account-display-name"
                            value={displayName}
                            onChange={(event) => dispatchDraft({
                                type: "setText",
                                field: "displayName",
                                value: event.target.value,
                            })}
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
                            onChange={(event) => dispatchDraft({
                                type: "setText",
                                field: "username",
                                value: event.target.value,
                            })}
                            maxLength={255}
                            required
                            aria-invalid={fieldErrors.username ? true : undefined}
                        />
                        {fieldErrors.username && (
                            <p className="text-sm text-destructive">{fieldErrors.username}</p>
                        )}
                    </div>

                    <div className="grid gap-2">
                        <div className="flex items-center justify-between gap-2">
                            <Label htmlFor="account-email">{t("emailLabel")}</Label>
                            <Button
                                type="button"
                                variant="link"
                                size="sm"
                                className="h-auto p-0"
                                onClick={onChangeEmail}
                            >
                                {t("changeEmail")}
                            </Button>
                        </div>
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
                            onValueChange={(value) => dispatchDraft({
                                type: "setText",
                                field: "timezone",
                                value: value ?? confirmedUser.timezone,
                            })}
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
                                    dispatchDraft({ type: "setLocale", value });
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

                <ProfileFormActions dirty={dirty} submitting={submitting} onReset={onReset} />
            </form>
        </Rise>
    );
}

export default function ProfilePanel({ user }: Props) {
    const t = useTranslations("AccountProfile");
    const router = useRouter();

    const [draft, dispatchDraft] = useReducer(profileDraftReducer, user, profileDraft);
    const [confirmation, setConfirmation] = useState({ source: user, value: user });
    const [photo, setPhoto] = useState<File | null>(null);
    const [photoPreview, setPhotoPreview] = useState<string | null>(null);
    const [uploadedPhotoUrl, setUploadedPhotoUrl] = useState<string | null>(null);
    const previewUrlRef = useRef<string | null>(null);
    const [submitting, setSubmitting] = useState(false);
    const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({});
    const [changeEmailOpen, setChangeEmailOpen] = useState(false);
    const confirmedUser = confirmation.value;
    const { displayName, username, timezone, preferredLocale } = draft;

    if (confirmation.source !== user) {
        dispatchDraft({ type: "sync", previous: profileDraft(confirmedUser), value: profileDraft(user) });
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

    const selectPhoto = async (file: File | null) => {
        if (file && !await isManagedImageFile(file)) {
            toastError(t("photoUnsupported"));
            return;
        }
        if (previewUrlRef.current) URL.revokeObjectURL(previewUrlRef.current);
        const nextPreview = file ? URL.createObjectURL(file) : null;
        previewUrlRef.current = nextPreview;
        setPhotoPreview(nextPreview);
        setPhoto(file);
        setUploadedPhotoUrl(null);
    };

    const reset = () => {
        dispatchDraft({ type: "reset", value: profileDraft(confirmedUser) });
        void selectPhoto(null);
        setFieldErrors({});
    };

    const confirmUser = (nextUser: User) => {
        setConfirmation((current) => ({ ...current, value: nextUser }));
    };

    const handleSubmit = async (event: FormEvent<HTMLFormElement>) => {
        event.preventDefault();
        if (!dirty || submitting) return;
        setSubmitting(true);
        setFieldErrors({});

        const savedUser = confirmedUser;
        let completedMutation = false;
        let completedProfilePictureUrl: string | null = null;
        let photoUploadFailed = false;
        try {
            const localeMutation: Promise<ProfileMutationChanges | null> =
                preferredLocale !== savedUser.locale
                    ? persistAuthenticatedLocale(preferredLocale).then((locale) => ({ locale }))
                    : Promise.resolve(null);
            const profileMutation = (async (): Promise<ProfileMutationChanges | null> => {
                let profilePictureUrl = savedUser.profilePictureUrl;
                if (photo) {
                    try {
                        profilePictureUrl = uploadedPhotoUrl ?? (await uploadCurrentUserProfilePicture(photo));
                    } catch (error) {
                        photoUploadFailed = true;
                        throw error;
                    }
                    completedProfilePictureUrl = profilePictureUrl;
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
            let nextUser = completedProfilePictureUrl == null
                ? savedUser
                : { ...savedUser, profilePictureUrl: completedProfilePictureUrl };
            if (completedProfilePictureUrl !== null
                && completedProfilePictureUrl !== savedUser.profilePictureUrl) {
                completedMutation = true;
            }
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

            void selectPhoto(null);
            toastSuccess(t("saved"));
            router.refresh();
        } catch (err) {
            if (err instanceof ApiError && err.fieldErrors) {
                setFieldErrors(err.fieldErrors);
            }
            const message = completedMutation
                ? t("partiallySaved")
                : photoUploadFailed
                  ? t("photoUploadFailed")
                  : t("saveFailed");
            toastError(message);
            if (completedMutation) {
                if (!photoUploadFailed) void selectPhoto(null);
                router.refresh();
            }
        } finally {
            setSubmitting(false);
        }
    };

    const previewUrl = photoPreview ?? confirmedUser.profilePictureUrl ?? null;
    const initial = confirmedUser.displayName?.slice(0, 1).toUpperCase() || "?";

    return (
        <div className="space-y-10">
            <ProfileForm
                user={user}
                confirmedUser={confirmedUser}
                draft={draft}
                timeZones={timeZones}
                fieldErrors={fieldErrors}
                previewUrl={previewUrl}
                initial={initial}
                dirty={dirty}
                submitting={submitting}
                dispatchDraft={dispatchDraft}
                onSelectPhoto={(file) => {
                    if (!submitting) void selectPhoto(file);
                }}
                onReset={reset}
                onSubmit={handleSubmit}
                onChangeEmail={() => setChangeEmailOpen(true)}
            />

            <ProfileDetails user={user} />

            <ChangeEmailDialog open={changeEmailOpen} onOpenChange={setChangeEmailOpen} />
        </div>
    );
}
