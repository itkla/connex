"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useState } from "react";
import { toastError, toastSuccess } from "@/app/lib/toast";
import { useTranslations } from "next-intl";
import {
    ArrowRightIcon,
    EyeIcon,
    EyeSlashIcon,
} from "@heroicons/react/24/outline";
import { LoaderCircle } from "lucide-react";

import { ApiError, login, register as registerUser } from "@/app/lib/api";
import AuthBrandPanel from "@/app/components/auth/AuthBrandPanel";

type AuthMode = "login" | "register";
type FieldKey = "username" | "email" | "displayName" | "password";
type FieldErrors = Partial<Record<FieldKey, string>>;

const FIELD_KEYS = ["username", "email", "displayName", "password"] as const;

const FORM_FIELDS: Record<AuthMode, FieldKey[]> = {
    login: ["username", "password"],
    register: ["username", "email", "displayName", "password"],
};

const ALT_HREF: Record<AuthMode, string> = {
    login: "/auth/register",
    register: "/auth/login",
};

const FIELD_META: Record<FieldKey, { type: string; autoComplete: string }> = {
    username: { type: "text", autoComplete: "username" },
    email: { type: "email", autoComplete: "email" },
    displayName: { type: "text", autoComplete: "name" },
    password: { type: "password", autoComplete: "current-password" },
};

function pickFieldErrors(errors?: Record<string, string>): FieldErrors {
    if (!errors) {
        return {};
    }

    return FIELD_KEYS.reduce<FieldErrors>((picked, key) => {
        if (errors[key]) {
            picked[key] = errors[key];
        }

        return picked;
    }, {});
}

export function AuthForm({ mode, redirectUrl }: { mode: AuthMode; redirectUrl: string | null }) {
    const router = useRouter();
    const tForm = useTranslations("AuthForm");
    const tMode = useTranslations(mode === "login" ? "AuthLogin" : "AuthRegister");
    const tLogin = useTranslations("AuthLogin");

    const fields = FORM_FIELDS[mode];
    const altHref = ALT_HREF[mode];

    const fieldLabels: Record<FieldKey, string> = {
        username: tForm("labelUsername"),
        email: tForm("labelEmail"),
        displayName: tForm("labelDisplayName"),
        password: tForm("labelPassword"),
    };

    const [values, setValues] = useState<Record<FieldKey, string>>({
        username: "",
        email: "",
        displayName: "",
        password: "",
    });
    const [error, setError] = useState<string | null>(null);
    const [fieldErrors, setFieldErrors] = useState<FieldErrors>({});
    const [submitting, setSubmitting] = useState(false);
    const [showPassword, setShowPassword] = useState(false);

    function setField(key: FieldKey, value: string) {
        setValues((prev) => ({ ...prev, [key]: value }));
        setError(null);
        setFieldErrors((prev) => {
            if (!prev[key]) {
                return prev;
            }

            const next = { ...prev };
            delete next[key];
            return next;
        });
    }

    function getAuthErrorMessage(err: ApiError, hasFieldErrors: boolean) {
        if (mode === "login" && err.status === 401) {
            return tLogin("invalidCredentials");
        }

        if (hasFieldErrors) {
            return tForm("formHasErrors");
        }

        return err.message;
    }

    async function onSubmit(e: React.SubmitEvent<HTMLFormElement>) {
        e.preventDefault();
        setError(null);
        setFieldErrors({});
        setSubmitting(true);

        try {
            if (mode === "login") {
                await login({
                    username: values.username,
                    password: values.password,
                });
            } else {
                await registerUser({
                    username: values.username,
                    email: values.email,
                    displayName: values.displayName,
                    password: values.password,
                    timezone: Intl.DateTimeFormat().resolvedOptions().timeZone || "UTC",
                });
            }

            toastSuccess(tMode("successMessage"));
            // The backend sets connex_workspace when the account has a workspace; otherwise onboard.
            const hasWorkspace = /(?:^|;\s*)connex_workspace=/.test(document.cookie);
            if (redirectUrl) {
                router.push(redirectUrl);
            } else if (!hasWorkspace) {
                router.replace("/onboarding");
            } else {
                router.replace("/dashboard");
            }
            router.refresh();
        } catch (err) {
            const nextFieldErrors = err instanceof ApiError ? pickFieldErrors(err.fieldErrors) : {};
            const hasFieldErrors = Object.keys(nextFieldErrors).length > 0;
            const message =
                err instanceof ApiError
                    ? getAuthErrorMessage(err, hasFieldErrors)
                    : tForm("genericError");

            setError(message);
            setFieldErrors(nextFieldErrors);
            toastError(message);
        } finally {
            setSubmitting(false);
        }
    }

    const hasFieldErrors = Object.keys(fieldErrors).length > 0;

    return (
        <div className="min-h-[100dvh] w-full bg-background lg:grid lg:h-[100dvh] lg:grid-cols-[1fr_1.05fr] lg:grid-rows-[100dvh] lg:overflow-hidden">
            <div className="relative flex min-h-[100dvh] flex-col px-6 py-10 sm:px-10 lg:h-full lg:min-h-0 lg:overflow-y-auto lg:px-14 lg:py-12">
                <Link
                    href="/"
                    className="flex w-fit items-center gap-2.5 rounded-lg transition-opacity duration-150 ease-out hover:opacity-70 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-brand lg:hidden"
                >
                    <span className="size-3 rounded-[5px] bg-brand" aria-hidden="true" />
                    <span className="text-lg font-bold tracking-tight text-foreground">{tForm("brand")}</span>
                </Link>

                <div className="flex flex-1 items-center justify-center">
                    <div className="w-full max-w-[400px] py-10 lg:py-0">
                        <h1 className="connex-rise font-display font-black text-[clamp(2rem,4vw,2.75rem)] leading-[1.1] tracking-[-0.01em] text-balance text-foreground">
                            {tMode("title")}
                        </h1>
                        <p
                            className="connex-rise mt-3 text-base leading-relaxed text-muted-foreground text-pretty"
                            style={{ animationDelay: "60ms" }}
                        >
                            {tMode("subtitle")}
                        </p>

                        <form className="mt-9 space-y-3" onSubmit={onSubmit} noValidate>
                            {fields.map((key, i) => {
                                const meta = FIELD_META[key];
                                const isPassword = key === "password";
                                const autoComplete =
                                    isPassword && mode === "register" ? "new-password" : meta.autoComplete;
                                const inputType = isPassword
                                    ? showPassword
                                        ? "text"
                                        : "password"
                                    : meta.type;
                                const fieldError = fieldErrors[key];
                                const fieldId = `${mode}-${key}`;
                                const errorId = `${fieldId}-error`;

                                return (
                                    <div
                                        key={key}
                                        className="connex-rise"
                                        style={{ animationDelay: `${120 + i * 60}ms` }}
                                    >
                                        <div className="relative">
                                            <input
                                                id={fieldId}
                                                type={inputType}
                                                value={values[key]}
                                                onChange={(e) => setField(key, e.target.value)}
                                                placeholder=" "
                                                autoComplete={autoComplete}
                                                required
                                                aria-invalid={Boolean(fieldError)}
                                                aria-describedby={fieldError ? errorId : undefined}
                                                className={`peer h-14 w-full rounded-xl border bg-input px-4 pt-5 pb-1.5 text-base text-foreground outline-none transition-[border-color,box-shadow,background-color] duration-150 ease-out placeholder:text-transparent focus:bg-background focus:ring-4 ${
                                                    isPassword ? "pr-12" : ""
                                                } ${
                                                    fieldError
                                                        ? "border-destructive focus:border-destructive focus:ring-destructive/15"
                                                        : "border-input focus:border-brand focus:ring-brand/20"
                                                }`}
                                            />
                                            <label
                                                htmlFor={fieldId}
                                                className={`pointer-events-none absolute left-4 top-1/2 -translate-y-1/2 text-base transition-all duration-150 ease-out peer-focus:top-2.5 peer-focus:translate-y-0 peer-focus:text-xs peer-[:not(:placeholder-shown)]:top-2.5 peer-[:not(:placeholder-shown)]:translate-y-0 peer-[:not(:placeholder-shown)]:text-xs ${
                                                    fieldError
                                                        ? "text-destructive"
                                                        : "text-muted-foreground peer-focus:text-foreground"
                                                }`}
                                            >
                                                {fieldLabels[key]}
                                            </label>

                                            {isPassword && (
                                                <button
                                                    type="button"
                                                    onClick={() => setShowPassword((s) => !s)}
                                                    aria-label={
                                                        showPassword
                                                            ? tForm("hidePassword")
                                                            : tForm("showPassword")
                                                    }
                                                    aria-pressed={showPassword}
                                                    className="absolute right-2 top-1/2 inline-flex size-9 -translate-y-1/2 items-center justify-center rounded-lg text-muted-foreground transition-[color,transform] duration-150 ease-out hover:text-foreground focus-visible:text-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-brand active:scale-[0.94]"
                                                >
                                                    {showPassword ? (
                                                        <EyeSlashIcon className="size-5" />
                                                    ) : (
                                                        <EyeIcon className="size-5" />
                                                    )}
                                                </button>
                                            )}
                                        </div>
                                        {fieldError && (
                                            <p id={errorId} className="mt-1.5 px-1 text-sm text-destructive">
                                                {fieldError}
                                            </p>
                                        )}
                                    </div>
                                );
                            })}

                            {error && !hasFieldErrors && (
                                <p
                                    role="alert"
                                    className="rounded-xl bg-red-50 px-3.5 py-2.5 text-sm text-red-700 dark:bg-red-950/40 dark:text-red-300"
                                >
                                    {error}
                                </p>
                            )}

                            <button
                                type="submit"
                                disabled={submitting || hasFieldErrors}
                                style={{ animationDelay: `${120 + fields.length * 60}ms` }}
                                className={`connex-rise mt-2 flex w-full items-center justify-center gap-2 rounded-full px-6 py-3.5 text-base font-semibold transition-[transform,background-color,opacity] duration-150 ease-out active:scale-[0.98] disabled:cursor-not-allowed ${
                                    hasFieldErrors
                                        ? "bg-muted text-muted-foreground"
                                        : "bg-brand text-neutral-950 hover:bg-brand-hover disabled:opacity-70"
                                }`}
                            >
                                {submitting ? (
                                    <>
                                        <LoaderCircle className="size-4 animate-spin" />
                                        {tMode("submittingLabel")}
                                    </>
                                ) : hasFieldErrors ? (
                                    tForm("resolveErrors")
                                ) : (
                                    <>
                                        {tMode("submitLabel")}
                                        <ArrowRightIcon className="size-4" />
                                    </>
                                )}
                            </button>
                        </form>

                        <p
                            className="connex-rise mt-7 text-center text-sm text-muted-foreground"
                            style={{ animationDelay: `${180 + fields.length * 60}ms` }}
                        >
                            {tMode("altPrompt")}{" "}
                            <Link
                                href={altHref}
                                className="font-semibold text-foreground underline decoration-brand decoration-2 underline-offset-4 transition-colors duration-150 ease-out hover:decoration-brand-hover"
                            >
                                {tMode("altLabel")}
                            </Link>
                        </p>
                    </div>
                </div>
            </div>

            <AuthBrandPanel className="hidden lg:block lg:h-full" />
        </div>
    );
}
