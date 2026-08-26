"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useState } from "react";
import { sameOriginPath } from "@/app/lib/sessionExpiry";
import { toastError, toastSuccess } from "@/app/lib/toast";
import { useTranslations } from "next-intl";
import {
    ArrowRightIcon,
    BuildingOffice2Icon,
    EyeIcon,
    EyeSlashIcon,
    FingerPrintIcon,
} from "@heroicons/react/24/outline";
import { LoaderCircle } from "lucide-react";
import { startAuthentication, WebAuthnError } from "@simplewebauthn/browser";

import {
    ApiError,
    beginPasskeyAuthentication,
    discoverSso,
    finishPasskeyAuthentication,
    login,
    register as registerUser,
    socialLoginStartPath,
    ssoStartPath,
} from "@/app/lib/api";
import { usePasskeySupport } from "@/app/hooks/usePasskeySupport";
import AuthBrandPanel from "@/app/components/auth/AuthBrandPanel";
import PermissionsUnavailable from "@/app/components/PermissionsUnavailable";
import WorkspaceUnavailableRetry from "@/app/components/WorkspaceUnavailableRetry";
import type { CapabilityAvailability } from "@/app/lib/capabilityAvailability";

type AuthMode = "login" | "register";
type FieldKey = "username" | "email" | "displayName" | "password";
type FieldErrors = Partial<Record<FieldKey, string>>;

const FIELD_KEYS = ["username", "email", "displayName", "password"] as const;
const SSO_ENFORCED_CODE = "SSO_ENFORCED";
const BREACHED_PASSWORD_CODE = "BREACHED_PASSWORD";
const BREACHED_PASSWORD_CHECK_UNAVAILABLE_CODE = "BREACHED_PASSWORD_CHECK_UNAVAILABLE";

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

function isSsoEnforcedError(err: ApiError): boolean {
    return err.status === 403 && err.code === SSO_ENFORCED_CODE;
}

export function AuthForm({
    mode,
    redirectUrl,
    ssoError = false,
    ssoEnabled = false,
    socialProviders = {},
    ssoAvailability = "disabled",
}: {
    mode: AuthMode;
    redirectUrl: string | null;
    ssoError?: boolean;
    ssoEnabled?: boolean;
    socialProviders?: { google?: boolean; microsoft?: boolean };
    ssoAvailability?: CapabilityAvailability;
}) {
    const router = useRouter();
    const tForm = useTranslations("AuthForm");
    const tMode = useTranslations(mode === "login" ? "AuthLogin" : "AuthRegister");
    const tLogin = useTranslations("AuthLogin");
    const tCapability = useTranslations("CapabilityUnavailable");

    const fields = FORM_FIELDS[mode];
    const altHref = ALT_HREF[mode];

    const fieldLabels: Record<FieldKey, string> = {
        username: mode === "login" ? tForm("labelLoginIdentifier") : tForm("labelUsername"),
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
    const [error, setError] = useState<string | null>(
        ssoError && mode === "login" ? tLogin("ssoError") : null,
    );
    const [fieldErrors, setFieldErrors] = useState<FieldErrors>({});
    const [submitting, setSubmitting] = useState(false);
    const [showPassword, setShowPassword] = useState(false);
    const [passkeySubmitting, setPasskeySubmitting] = useState(false);
    const [ssoSubmitting, setSsoSubmitting] = useState(false);
    const passkeySupported = usePasskeySupport();

    function routeAfterAuth() {
        const hasWorkspace = /(?:^|;\s*)connex_workspace=/.test(document.cookie);
        const safeRedirect = sameOriginPath(redirectUrl);
        if (safeRedirect) {
            router.push(safeRedirect);
        } else if (!hasWorkspace) {
            router.replace("/onboarding");
        } else {
            router.replace("/dashboard");
        }
        router.refresh();
    }

    async function signInWithPasskey() {
        setError(null);
        setFieldErrors({});
        setPasskeySubmitting(true);
        try {
            const optionsJSON = await beginPasskeyAuthentication();
            const credential = await startAuthentication({ optionsJSON });
            await finishPasskeyAuthentication(credential);
            toastSuccess(tLogin("successMessage"));
            routeAfterAuth();
        } catch (err) {
            const canceled =
                (err instanceof WebAuthnError && err.cause instanceof Error && err.cause.name === "NotAllowedError") ||
                (err instanceof Error && err.name === "NotAllowedError");
            if (canceled) {
                return;
            }
            const message = getPasskeyErrorMessage(err);
            setError(message);
            toastError(message);
        } finally {
            setPasskeySubmitting(false);
        }
    }

    async function continueWithSso() {
        const identifier = values.username.trim();
        setError(null);
        setFieldErrors({});
        if (!identifier.includes("@")) {
            const message = tLogin("ssoNeedsEmail");
            setError(message);
            document.getElementById(`${mode}-username`)?.focus();
            return;
        }
        setSsoSubmitting(true);
        try {
            const discovery = await discoverSso(identifier);
            if (discovery.available && discovery.registrationId && discovery.protocol) {
                window.location.assign(ssoStartPath(discovery.registrationId, discovery.protocol));
                return;
            }
            const message = tLogin("ssoUnavailable");
            setError(message);
            toastError(message);
        } catch {
            const message = tForm("genericError");
            setError(message);
            toastError(message);
        } finally {
            setSsoSubmitting(false);
        }
    }

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

        if (mode === "login" && isSsoEnforcedError(err)) {
            return tLogin("ssoEnforced");
        }

        if (mode === "login" && err.status === 403) {
            return tForm("genericError");
        }

        if (hasFieldErrors) {
            return tForm("formHasErrors");
        }

        if (mode === "register" && err.status === 409) {
            return tMode("registerFailed");
        }

        return err.message;
    }

    function getPasskeyErrorMessage(err: unknown) {
        if (err instanceof ApiError && isSsoEnforcedError(err)) {
            return tLogin("ssoEnforced");
        }

        if (err instanceof ApiError && err.status === 401) {
            return tLogin("passkeyFailed");
        }

        return tForm("genericError");
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
            routeAfterAuth();
        } catch (err) {
            const nextFieldErrors = err instanceof ApiError && err.code === BREACHED_PASSWORD_CODE
                ? { password: tForm("breachedPassword") }
                : err instanceof ApiError && err.code === BREACHED_PASSWORD_CHECK_UNAVAILABLE_CODE
                    ? { password: tForm("passwordScreeningUnavailable") }
                    : err instanceof ApiError
                        ? pickFieldErrors(err.fieldErrors)
                        : {};
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

                            {mode === "login" && (
                                <div
                                    className="connex-rise flex justify-end"
                                    style={{ animationDelay: `${120 + fields.length * 60}ms` }}
                                >
                                    <Link
                                        href="/auth/forgot-password"
                                        className="text-sm font-medium text-muted-foreground transition-colors duration-150 ease-out hover:text-foreground"
                                    >
                                        {tLogin("forgotPassword")}
                                    </Link>
                                </div>
                            )}

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
                                        : "bg-brand text-brand-foreground hover:bg-brand-hover disabled:opacity-70"
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

                        {mode === "login" && (
                            passkeySupported
                            || ssoEnabled
                            || socialProviders.google
                            || socialProviders.microsoft
                            || ssoAvailability === "unavailable"
                        ) && (
                            <div
                                className="connex-rise mt-5"
                                style={{ animationDelay: `${180 + fields.length * 60}ms` }}
                            >
                                <div className="flex items-center gap-3" aria-hidden="true">
                                    <span className="h-px flex-1 bg-border" />
                                    <span className="text-xs font-medium text-muted-foreground">
                                        {tLogin("orDivider")}
                                    </span>
                                    <span className="h-px flex-1 bg-border" />
                                </div>
                                <div className="mt-4 space-y-3">
                                    {passkeySupported && (
                                        <button
                                            type="button"
                                            onClick={signInWithPasskey}
                                            disabled={passkeySubmitting || ssoSubmitting}
                                            aria-busy={passkeySubmitting}
                                            className="flex w-full items-center justify-center gap-2 rounded-full border border-input bg-background px-6 py-3.5 text-base font-semibold text-foreground transition-[transform,background-color,border-color] duration-150 ease-out hover:bg-input focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-brand active:scale-[0.98] disabled:cursor-not-allowed disabled:opacity-70"
                                        >
                                            {passkeySubmitting ? (
                                                <LoaderCircle className="size-4 animate-spin" />
                                            ) : (
                                                <FingerPrintIcon className="size-5" />
                                            )}
                                            {passkeySubmitting ? tLogin("passkeySigningIn") : tLogin("passkeyButton")}
                                        </button>
                                    )}
                                    {ssoAvailability === "unavailable" ? (
                                        <PermissionsUnavailable
                                            variant="inline"
                                            title={tCapability("title")}
                                            body={tCapability("body")}
                                            action={(
                                                <WorkspaceUnavailableRetry
                                                    label={tCapability("retry")}
                                                    pendingLabel={tCapability("retrying")}
                                                />
                                            )}
                                        />
                                    ) : null}
                                    {ssoEnabled && (
                                        <button
                                            type="button"
                                            onClick={continueWithSso}
                                            disabled={ssoSubmitting || passkeySubmitting}
                                            aria-busy={ssoSubmitting}
                                            className="flex w-full items-center justify-center gap-2 rounded-full border border-input bg-background px-6 py-3.5 text-base font-semibold text-foreground transition-[transform,background-color,border-color] duration-150 ease-out hover:bg-input focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-brand active:scale-[0.98] disabled:cursor-not-allowed disabled:opacity-70"
                                        >
                                            {ssoSubmitting ? (
                                                <LoaderCircle className="size-4 animate-spin" />
                                            ) : (
                                                <BuildingOffice2Icon className="size-5" />
                                            )}
                                            {ssoSubmitting ? tLogin("ssoSigningIn") : tLogin("ssoButton")}
                                        </button>
                                    )}
                                    {socialProviders.google && (
                                        <button
                                            type="button"
                                            onClick={() => window.location.assign(socialLoginStartPath("google"))}
                                            className="flex w-full items-center justify-center gap-2 rounded-full border border-input bg-background px-6 py-3.5 text-base font-semibold text-foreground transition-[transform,background-color,border-color] duration-150 ease-out hover:bg-input focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-brand active:scale-[0.98] disabled:cursor-not-allowed disabled:opacity-70"
                                        >
                                            <svg className="size-5" width={20} height={20} viewBox="0 0 48 48" aria-hidden="true">
                                                <path
                                                    fill="#EA4335"
                                                    d="M24 9.5c3.54 0 6.71 1.22 9.21 3.6l6.85-6.85C35.9 2.38 30.47 0 24 0 14.62 0 6.51 5.38 2.56 13.22l7.98 6.19C12.43 13.72 17.74 9.5 24 9.5z"
                                                />
                                                <path
                                                    fill="#4285F4"
                                                    d="M46.98 24.55c0-1.57-.15-3.09-.38-4.55H24v9.02h12.94c-.58 2.96-2.26 5.48-4.78 7.18l7.73 6c4.51-4.18 7.09-10.36 7.09-17.65z"
                                                />
                                                <path
                                                    fill="#FBBC05"
                                                    d="M10.53 28.59c-.48-1.45-.76-2.99-.76-4.59s.27-3.14.76-4.59l-7.98-6.19C.92 16.46 0 20.12 0 24c0 3.88.92 7.54 2.56 10.78l7.97-6.19z"
                                                />
                                                <path
                                                    fill="#34A853"
                                                    d="M24 48c6.48 0 11.93-2.13 15.89-5.81l-7.73-6c-2.15 1.45-4.92 2.3-8.16 2.3-6.26 0-11.57-4.22-13.47-9.91l-7.98 6.19C6.51 42.62 14.62 48 24 48z"
                                                />
                                            </svg>
                                            {tLogin("googleButton")}
                                        </button>
                                    )}
                                    {socialProviders.microsoft && (
                                        <button
                                            type="button"
                                            onClick={() => window.location.assign(socialLoginStartPath("microsoft"))}
                                            className="flex w-full items-center justify-center gap-2 rounded-full border border-input bg-background px-6 py-3.5 text-base font-semibold text-foreground transition-[transform,background-color,border-color] duration-150 ease-out hover:bg-input focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-brand active:scale-[0.98] disabled:cursor-not-allowed disabled:opacity-70"
                                        >
                                            <svg className="size-5" width={20} height={20} viewBox="0 0 23 23" aria-hidden="true">
                                                <path fill="#F25022" d="M1 1h10v10H1z" />
                                                <path fill="#7FBA00" d="M12 1h10v10H12z" />
                                                <path fill="#00A4EF" d="M1 12h10v10H1z" />
                                                <path fill="#FFB900" d="M12 12h10v10H12z" />
                                            </svg>
                                            {tLogin("microsoftButton")}
                                        </button>
                                    )}
                                </div>
                            </div>
                        )}

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
