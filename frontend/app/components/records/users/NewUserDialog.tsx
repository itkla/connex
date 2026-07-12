"use client";

import { type ComponentType, type ReactNode, type SVGProps, useState } from "react";
import { useRouter } from "next/navigation";
import { useTranslations } from "next-intl";
import { PlusIcon } from "@heroicons/react/24/solid";
import {
    AtSymbolIcon,
    EnvelopeIcon,
    LockClosedIcon,
    UserIcon,
} from "@heroicons/react/24/outline";
import { Loader2Icon } from "lucide-react";

import {
    ResponsiveDialog,
    ResponsiveDialogClose,
    ResponsiveDialogContent,
    ResponsiveDialogDescription,
    ResponsiveDialogFooter,
    ResponsiveDialogHeader,
    ResponsiveDialogTitle,
    ResponsiveDialogTrigger,
} from "@/components/ui/responsive-dialog";
import { Button } from "@/components/ui/button";
import { Label } from "@/components/ui/label";
import {
    DialogStatusCover,
    resolveDialogStatus,
    fieldInputClass,
    fieldErrorClass,
    fieldLeadIconClass,
} from "@/components/ui/dialog-status-cover";
import { cn } from "@/lib/utils";
import { ApiError, createUser } from "@/app/lib/api";
import { toastError, toastSuccess } from "@/app/lib/toast";
import { type RegisterPayload } from "@/app/lib/types";

const EMPTY: RegisterPayload = { displayName: "", username: "", email: "", password: "" };

type FieldIcon = ComponentType<SVGProps<SVGSVGElement>>;

function Field({
    id,
    label,
    type,
    value,
    onChange,
    placeholder,
    error,
    autoFocus,
    icon,
}: {
    id: string;
    label: string;
    type: string;
    value: string;
    onChange: (value: string) => void;
    placeholder?: string;
    error?: string;
    autoFocus?: boolean;
    icon?: FieldIcon;
}) {
    const LeadIcon = icon ?? null;
    const errorId = `${id}-error`;
    return (
        <div className="grid gap-1.5">
            <Label htmlFor={id}>{label}</Label>
            <div className="group relative">
                {LeadIcon ? <LeadIcon className={fieldLeadIconClass} aria-hidden /> : null}
                <input
                    id={id}
                    type={type}
                    value={value}
                    onChange={(e) => onChange(e.target.value)}
                    className={cn(fieldInputClass, "pl-9 pr-3", error && fieldErrorClass)}
                    placeholder={placeholder}
                    autoFocus={autoFocus}
                    aria-invalid={error ? true : undefined}
                    aria-describedby={error ? errorId : undefined}
                />
            </div>
            {error && (
                <p id={errorId} className="text-xs text-destructive">
                    {error}
                </p>
            )}
        </div>
    );
}

export default function NewUserDialog() {
    const t = useTranslations("UsersNewUserDialog");
    const router = useRouter();
    const [open, setOpen] = useState(false);
    const [payload, setPayload] = useState<RegisterPayload>(EMPTY);
    const [errors, setErrors] = useState<Record<string, string>>({});
    const [isCreating, setIsCreating] = useState(false);
    const [succeeded, setSucceeded] = useState(false);

    const set = (patch: Partial<RegisterPayload>) => setPayload((prev) => ({ ...prev, ...patch }));

    const onOpenChange = (next: boolean) => {
        setOpen(next);
        if (!next) {
            setPayload(EMPTY);
            setErrors({});
            setSucceeded(false);
        }
    };

    const canSubmit =
        payload.displayName.trim() &&
        payload.username.trim() &&
        payload.email.trim() &&
        payload.password.length > 0;

    const submit = async () => {
        setIsCreating(true);
        setErrors({});
        try {
            await createUser({
                displayName: payload.displayName.trim(),
                username: payload.username.trim(),
                email: payload.email.trim(),
                password: payload.password,
            });
            toastSuccess(t("toastCreated"));
            setIsCreating(false);
            setSucceeded(true);
            setTimeout(() => onOpenChange(false), 900);
            router.refresh();
        } catch (err) {
            if (err instanceof ApiError && err.fieldErrors) {
                const fieldErrors = err.fieldErrors;
                setErrors(fieldErrors);
                const k = Object.keys(fieldErrors)[0];
                if (k) requestAnimationFrame(() => document.getElementById(k)?.focus());
            } else {
                toastError(err instanceof Error ? err.message : t("toastFailed"));
            }
        } finally {
            setIsCreating(false);
        }
    };

    const hasErrors = Object.keys(errors).length > 0;
    const status = resolveDialogStatus({ isLoading: isCreating, hasErrors, isSuccess: succeeded });

    const handleOpenChange = (next: boolean) => {
        if (!next && isCreating) return;
        onOpenChange(next);
    };

    return (
        <ResponsiveDialog open={open} onOpenChange={handleOpenChange}>
            <ResponsiveDialogTrigger asChild>
                <Button className="bg-brand text-white" aria-label={t("newAria")}>
                    <PlusIcon strokeWidth={2.5} />
                    {t("new")}
                </Button>
            </ResponsiveDialogTrigger>
            <ResponsiveDialogContent className="gap-0 overflow-hidden p-0 sm:max-w-lg">
                <DialogStatusCover status={status} />

                <div className="px-6 pb-6">
                    <ResponsiveDialogHeader className="ncd-rise -mt-12 mb-5" style={{ animationDelay: "40ms" }}>
                        <ResponsiveDialogTitle className="text-xl font-semibold tracking-tight">{t("dialogTitle")}</ResponsiveDialogTitle>
                        <ResponsiveDialogDescription>{t("description")}</ResponsiveDialogDescription>
                    </ResponsiveDialogHeader>

                    <form
                        onSubmit={(e) => {
                            e.preventDefault();
                            if (isCreating) return;
                            submit();
                        }}
                        className="grid gap-5"
                    >
                        <div className="ncd-rise" style={{ animationDelay: "90ms" }}>
                            <Field
                                id="displayName"
                                label={t("displayName")}
                                type="text"
                                value={payload.displayName}
                                onChange={(v) => set({ displayName: v })}
                                placeholder={t("displayNamePlaceholder")}
                                error={errors.displayName}
                                icon={UserIcon}
                                autoFocus
                            />
                        </div>
                        <div className="ncd-rise" style={{ animationDelay: "140ms" }}>
                            <Field
                                id="username"
                                label={t("username")}
                                type="text"
                                value={payload.username}
                                onChange={(v) => set({ username: v })}
                                placeholder={t("usernamePlaceholder")}
                                error={errors.username}
                                icon={AtSymbolIcon}
                            />
                        </div>
                        <div className="ncd-rise" style={{ animationDelay: "190ms" }}>
                            <Field
                                id="email"
                                label={t("email")}
                                type="email"
                                value={payload.email}
                                onChange={(v) => set({ email: v })}
                                placeholder={t("emailPlaceholder")}
                                error={errors.email}
                                icon={EnvelopeIcon}
                            />
                        </div>
                        <div className="ncd-rise" style={{ animationDelay: "240ms" }}>
                            <Field
                                id="password"
                                label={t("password")}
                                type="password"
                                value={payload.password}
                                onChange={(v) => set({ password: v })}
                                placeholder={t("passwordPlaceholder")}
                                error={errors.password}
                                icon={LockClosedIcon}
                            />
                        </div>

                        <ResponsiveDialogFooter className="ncd-rise mt-5" style={{ animationDelay: "290ms" }}>
                            <ResponsiveDialogClose asChild>
                                <Button type="button" variant="outline" disabled={isCreating}>
                                    {t("cancel")}
                                </Button>
                            </ResponsiveDialogClose>
                            <Button
                                type="submit"
                                disabled={isCreating || succeeded || !canSubmit}
                                className="min-w-24 bg-brand text-white shadow-sm transition hover:bg-brand-hover hover:shadow-md"
                            >
                                {isCreating ? <Loader2Icon className="size-4 animate-spin" /> : t("create")}
                            </Button>
                        </ResponsiveDialogFooter>
                    </form>
                </div>
            </ResponsiveDialogContent>
        </ResponsiveDialog>
    );
}